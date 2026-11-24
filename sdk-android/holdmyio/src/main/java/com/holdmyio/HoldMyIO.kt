package com.holdmyio

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Public SDK API (same as the Swift SDK).
 *
 *     HoldMyIO.configure(application, appKey = "pk_live_...", endpoint = "https://io.holdmy.company")
 *     HoldMyIO.screen("editor")
 *     HoldMyIO.track("export_done", mapOf("format" to "reel", "seconds" to 42))
 *
 * `session_start` and `app_open` are sent automatically. Events are stored on disk and sent
 * in batches, so it works offline too. Errors are swallowed: analytics never crashes the app.
 */
object HoldMyIO {
    data class Options(
        /** How often (in milliseconds) to try sending the queue. */
        val flushIntervalMs: Long = 30_000,
        /** Once this many events are queued, they are sent immediately. */
        val flushAt: Int = 20,
        /** Number of events per batch (the server accepts up to 100). */
        val batchSize: Int = 50,
        /** Maximum number of events kept on disk while offline. */
        val maxQueueSize: Int = 1000,
        /** A new session starts after this many milliseconds of inactivity. */
        val sessionTimeoutMs: Long = 30 * 60 * 1000,
        /** Events from debug builds are flagged and excluded from statistics. null: use the app's debuggable flag. */
        val debug: Boolean? = null,
    )

    @Volatile private var core: Core? = null

    /** Call once in `Application.onCreate()`, before any other calls. */
    @JvmStatic
    @JvmOverloads
    fun configure(application: Application, appKey: String, endpoint: String, options: Options = Options()) {
        synchronized(this) {
            if (core != null) return
            try {
                val debug = options.debug ?: ((application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                val safeKey = appKey.filter { it.isLetterOrDigit() }
                val file = File(File(application.noBackupFilesDir, "HoldMyIO"), "state-$safeKey.json")
                val created = Core(appKey, endpoint, options, debug, file, deviceContext(application), ::post)
                core = created
                created.start()
                observeForeground(application, created)
            } catch (_: Throwable) {
                // Analytics must never crash the app
            }
        }
    }

    /** Any event. Name: letters, digits, `_` and `.`, up to 64 characters. Values: string, number, bool. */
    @JvmStatic
    @JvmOverloads
    fun track(name: String, props: Map<String, Any?> = emptyMap()) {
        core?.record(name, cleanProps(props))
    }

    /** A screen view. Later events are attributed to this screen until you call it with another. */
    @JvmStatic
    @JvmOverloads
    fun screen(name: String, props: Map<String, Any?> = emptyMap()) {
        core?.record("screen_view", cleanProps(props), setScreen = name)
    }

    /** A purchase. `price` is the price paid, `currency` an ISO 4217 code, e.g. "EUR". */
    @JvmStatic
    @JvmOverloads
    fun purchase(productId: String, price: Double, currency: String, props: Map<String, Any?> = emptyMap()) {
        if (price < 0 || price.isNaN()) return
        core?.record("purchase", cleanProps(props), Revenue(price, currency.uppercase(Locale.ROOT), productId))
    }

    /** Sends the queue now, without waiting for the timer. */
    @JvmStatic
    fun flush() {
        core?.flush(force = true)
    }

    /** User opt-out: when disabled nothing is collected and unsent events are deleted. */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        core?.setEnabled(enabled)
    }

    @JvmStatic
    val isEnabled: Boolean get() = runCatching { core?.isEnabled }.getOrNull() ?: false

    // --- Android

    private fun observeForeground(application: Application, core: Core) {
        val tracker = ForegroundTracker(
            onOpen = { core.appOpened() },
            onBackground = { core.appBackgrounded() },
        )
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = tracker.activityStarted()
            override fun onActivityStopped(activity: Activity) = tracker.activityStopped(activity.isChangingConfigurations)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun deviceContext(context: Context): () -> DeviceContext {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val version = info?.versionName.orEmpty()
        val build = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString() }.orEmpty()
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(64)
        return {
            DeviceContext(
                appVersion = version,
                build = build,
                platform = "android",
                osVersion = Build.VERSION.RELEASE.orEmpty(),
                deviceModel = model,
                locale = Locale.getDefault().toString(),
            )
        }
    }

    private fun post(url: String, appKey: String, body: String): Int? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-App-Key", appKey)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            runCatching { (if (code < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } }
            code
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
