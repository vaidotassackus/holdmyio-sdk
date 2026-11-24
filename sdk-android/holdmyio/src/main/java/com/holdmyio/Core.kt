package com.holdmyio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Queue, sessions, disk persistence and sending. All state is mutated only on the `executor` thread
 * (like the Swift SDK serial queue). No Android classes: tested on a plain JVM.
 */
internal class Core(
    private val appKey: String,
    private val endpoint: String,
    private val options: HoldMyIO.Options,
    private val debug: Boolean,
    private val stateFile: File,
    private val deviceContext: () -> DeviceContext,
    /** Sends the JSON body and returns the HTTP status (null on a network error). */
    private val transport: (url: String, appKey: String, body: String) -> Int?,
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the network call runs (in tests: immediately, on the same thread). */
    private val background: (() -> Unit) -> Unit = { task -> Thread(task, "holdmyio-send").apply { isDaemon = true }.start() },
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "holdmyio").apply { isDaemon = true }
    },
) {
    companion object {
        const val SDK_VERSION = "0.2.1"
    }

    private var state: PersistedState = load()
    private var sessionId = ""
    private var lastActivity = 0L
    private var currentScreen = ""
    private var sending = false
    private var persistScheduled = false
    private var failures = 0
    private var retryAfter = 0L
    private var timer: ScheduledFuture<*>? = null

    private fun load(): PersistedState =
        runCatching { PersistedState.fromJson(stateFile.readText()) }.getOrNull()
            ?: PersistedState(UUID.randomUUID().toString().uppercase(), true, mutableListOf())

    fun start() {
        async {
            persistNow() // so the install ID survives even if the app is closed right away
            timer = executor.scheduleWithFixedDelay(
                { runSafely { flushLocked() } },
                options.flushIntervalMs, options.flushIntervalMs, TimeUnit.MILLISECONDS,
            )
            flushLocked() // send whatever is left from the previous run
        }
    }

    // --- Events

    fun record(name: String, props: Map<String, Any> = emptyMap(), revenue: Revenue? = null, setScreen: String? = null) {
        val at = now() // timestamp is taken at call time, not when the queue gets to it
        async {
            if (!state.enabled) return@async
            if (setScreen != null) currentScreen = setScreen
            touchSession(at)
            append(makeEvent(name, at, props, revenue))
        }
    }

    private fun makeEvent(name: String, at: Long, props: Map<String, Any> = emptyMap(), revenue: Revenue? = null) =
        Event(UUID.randomUUID().toString(), name, at, sessionId, currentScreen, props, revenue)

    /** A new session starts after `sessionTimeoutMs` of inactivity. */
    private fun touchSession(at: Long) {
        if (sessionId.isNotEmpty() && at - lastActivity < options.sessionTimeoutMs) {
            lastActivity = at
            return
        }
        sessionId = UUID.randomUUID().toString()
        lastActivity = at
        append(makeEvent("session_start", at))
    }

    private fun append(event: Event) {
        state.queue += event
        val overflow = state.queue.size - options.maxQueueSize
        if (overflow > 0) repeat(overflow) { state.queue.removeAt(0) } // oldest are dropped first
        schedulePersist()
        if (state.queue.size >= options.flushAt) flushLocked()
    }

    // --- Sending

    fun flush(force: Boolean = false) {
        async {
            if (force) retryAfter = 0
            persistNow()
            flushLocked()
        }
    }

    private fun flushLocked() {
        if (sending || state.queue.isEmpty() || now() < retryAfter) return
        val batch = state.queue.take(options.batchSize)
        val ids = batch.map { it.id }.toSet()
        val body = runCatching { batchJson(batch) }.getOrElse {
            state.queue.removeAll { it.id in ids } // a batch that cannot be encoded will never succeed
            return
        }

        sending = true
        // Network runs off this thread so the queue does not wait; the response comes back to it.
        background {
            val code = runCatching { transport("${endpoint.trimEnd('/')}/v1/events", appKey, body) }.getOrNull() ?: 0
            async { onSent(ids, code) }
        }
    }

    private fun onSent(ids: Set<String>, code: Int) {
        sending = false
        val delivered = code in 200..299
        val rejectedForGood = code == 400 || code == 413
        if (delivered || rejectedForGood) {
            state.queue.removeAll { it.id in ids }
            failures = 0
            retryAfter = 0
            persistNow()
            flushLocked() // in case more than one batch is queued
        } else {
            // Network or server error: keep in the queue, wait longer after each attempt.
            // The server removes duplicates by event id.
            failures += 1
            val delaySec = min(3600.0, 30.0 * 2.0.pow(failures - 1))
            retryAfter = now() + (delaySec * 1000).toLong()
            persistNow() // unsent events must survive even if the app crashes right after
        }
    }

    private fun batchJson(batch: List<Event>): String {
        val ctx = deviceContext()
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("install_id", state.installId)
                put("app_version", ctx.appVersion)
                put("build", ctx.build)
                put("platform", ctx.platform)
                put("os_version", ctx.osVersion)
                put("device_model", ctx.deviceModel)
                put("locale", ctx.locale)
                put("sdk_version", SDK_VERSION)
                put("debug", debug)
            })
            put("events", JSONArray().apply { batch.forEach { put(it.toJson()) } })
        }.toString()
    }

    // --- Opt-out

    fun setEnabled(enabled: Boolean) {
        async {
            state.enabled = enabled
            if (!enabled) state.queue.clear()
            persistNow()
        }
    }

    val isEnabled: Boolean get() = executor.submit<Boolean> { state.enabled }.get()

    // --- Disk

    private fun schedulePersist() {
        if (persistScheduled) return
        persistScheduled = true
        executor.schedule({ runSafely { persistScheduled = false; persistNow() } }, 1, TimeUnit.SECONDS)
    }

    private fun persistNow() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(state.toJson())
            if (!tmp.renameTo(stateFile)) {
                stateFile.delete()
                tmp.renameTo(stateFile)
            }
        } // Analytics must never crash the app; we will try again next time
    }

    // --- Helpers

    private fun async(block: () -> Unit) {
        runCatching { executor.execute { runSafely(block) } }
    }

    private inline fun runSafely(block: () -> Unit) {
        try { block() } catch (_: Throwable) { /* SDK errors are swallowed silently */ }
    }

    // --- For tests

    internal val queuedEvents: List<Event> get() = executor.submit<List<Event>> { state.queue.toList() }.get()
    internal val installId: String get() = executor.submit<String> { state.installId }.get()

    /** Waits until the queue has processed everything already submitted. */
    internal fun drain() { executor.submit {}.get() }

    internal fun shutdown() {
        timer?.cancel(false)
        executor.shutdown()
    }
}
