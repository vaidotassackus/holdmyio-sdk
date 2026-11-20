package com.holdmyio

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoreTest {
    private class FakeNetwork(var status: Int? = 202) {
        val bodies = mutableListOf<JSONObject>()
        val urls = mutableListOf<String>()
        val keys = mutableListOf<String>()
        fun send(url: String, key: String, body: String): Int? {
            urls += url; keys += key; bodies += JSONObject(body)
            return status
        }
    }

    private lateinit var dir: File
    private val cores = mutableListOf<Core>()
    private var clock = 1_700_000_000_000L

    @Before fun setUp() { dir = Files.createTempDirectory("holdmyio").toFile() }
    @After fun tearDown() { cores.forEach { it.shutdown() }; dir.deleteRecursively() }

    private fun makeCore(network: FakeNetwork, options: HoldMyIO.Options = HoldMyIO.Options(flushAt = 2, flushIntervalMs = 3_600_000)): Core {
        val core = Core(
            appKey = "pk_test_0123456789abcdef",
            endpoint = "https://in.example.com/",
            options = options,
            debug = false,
            stateFile = File(dir, "state.json"),
            deviceContext = { DeviceContext("2.0.0", "8", "android", "16", "Google Pixel 9", "lt_LT") },
            transport = network::send,
            now = { clock },
            background = { it() },
        )
        cores += core
        return core
    }

    @Test fun trackSendsBatchAndClearsQueue() {
        val network = FakeNetwork()
        val core = makeCore(network)
        core.record("export_done", mapOf("seconds" to 42, "format" to "reel", "first" to true))
        core.drain(); core.drain()

        assertEquals(1, network.bodies.size)
        assertEquals("https://in.example.com/v1/events", network.urls[0])
        assertEquals("pk_test_0123456789abcdef", network.keys[0])
        val body = network.bodies[0]
        val context = body.getJSONObject("context")
        assertEquals(core.installId, context.getString("install_id"))
        assertEquals("android", context.getString("platform"))
        assertEquals("2.0.0", context.getString("app_version"))
        assertEquals("8", context.getString("build"))
        assertEquals(Core.SDK_VERSION, context.getString("sdk_version"))
        assertFalse(context.getBoolean("debug"))

        val events = body.getJSONArray("events")
        assertEquals(listOf("session_start", "export_done"), (0 until events.length()).map { events.getJSONObject(it).getString("name") })
        val event = events.getJSONObject(1)
        assertEquals(clock, event.getLong("ts"))
        assertEquals(42, event.getJSONObject("props").getInt("seconds"))
        assertTrue(event.getJSONObject("props").getBoolean("first"))
        assertEquals(event.getString("session_id"), events.getJSONObject(0).getString("session_id"))
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(event.getString("id")))
        assertTrue(core.queuedEvents.isEmpty())
    }

    @Test fun failureKeepsEventsAndSurvivesRestart() {
        val network = FakeNetwork(status = null)
        val first = makeCore(network)
        first.record("app_open")
        first.drain(); first.drain()
        assertEquals(2, first.queuedEvents.size) // session_start + app_open
        val installId = first.installId
        first.shutdown()

        val second = makeCore(FakeNetwork())
        second.drain()
        assertEquals(installId, second.installId)
        assertEquals(listOf("session_start", "app_open"), second.queuedEvents.map { it.name })
    }

    @Test fun poisonBatchIsDropped() {
        val network = FakeNetwork(status = 400)
        val core = makeCore(network)
        core.record("app_open")
        core.drain(); core.drain()
        assertEquals(1, network.bodies.size)
        assertTrue(core.queuedEvents.isEmpty())
    }

    @Test fun serverErrorBacksOff() {
        val network = FakeNetwork(status = 503)
        val core = makeCore(network)
        core.record("a")
        core.drain(); core.drain()
        core.flush()
        core.drain(); core.drain()
        assertEquals("antras bandymas laukia backoff", 1, network.bodies.size)
        core.flush(force = true)
        core.drain(); core.drain()
        assertEquals(2, network.bodies.size)
        assertEquals(2, core.queuedEvents.size)
    }

    @Test fun newSessionAfterTimeout() {
        val core = makeCore(FakeNetwork(status = null), HoldMyIO.Options(flushAt = 100, flushIntervalMs = 3_600_000))
        core.record("a")
        core.drain()
        clock += 31 * 60 * 1000
        core.record("b")
        core.drain()
        val events = core.queuedEvents
        assertEquals(listOf("session_start", "a", "session_start", "b"), events.map { it.name })
        assertNotEquals(events[1].sessionId, events[3].sessionId)
    }

    @Test fun screenIsAttachedToLaterEvents() {
        val core = makeCore(FakeNetwork(status = null), HoldMyIO.Options(flushAt = 100, flushIntervalMs = 3_600_000))
        core.record("screen_view", setScreen = "library")
        core.record("paywall_viewed", mapOf("reason" to "pack"))
        core.drain()
        // Kaip Swift SDK: ekranas nustatomas prieš sesijos pradžią, tad ir session_start jį turi.
        assertEquals(listOf("session_start", "screen_view", "paywall_viewed"), core.queuedEvents.map { it.name })
        assertEquals(listOf("library", "library", "library"), core.queuedEvents.map { it.screen })
    }

    @Test fun purchaseCarriesRevenue() {
        val network = FakeNetwork()
        val core = makeCore(network)
        core.record("purchase", revenue = Revenue(19.99, "EUR", "liffer_pro"))
        core.drain(); core.drain()
        val revenue = network.bodies[0].getJSONArray("events").getJSONObject(1).getJSONObject("revenue")
        assertEquals(19.99, revenue.getDouble("amount"), 0.0001)
        assertEquals("EUR", revenue.getString("currency"))
        assertEquals("liffer_pro", revenue.getString("product_id"))
    }

    @Test fun disabledCollectsNothing() {
        val network = FakeNetwork()
        val core = makeCore(network, HoldMyIO.Options(flushAt = 100, flushIntervalMs = 3_600_000))
        core.record("a")
        core.setEnabled(false)
        core.record("b")
        core.flush()
        core.drain(); core.drain()
        assertTrue(core.queuedEvents.isEmpty())
        assertTrue(network.bodies.isEmpty())
        assertFalse(core.isEnabled)
    }

    @Test fun queueIsCappedOldestFirst() {
        val core = makeCore(FakeNetwork(status = null), HoldMyIO.Options(flushAt = 1000, maxQueueSize = 3, flushIntervalMs = 3_600_000))
        listOf("a", "b", "c", "d").forEach { core.record(it) }
        core.drain()
        assertEquals(listOf("b", "c", "d"), core.queuedEvents.map { it.name })
    }

    @Test fun cleanPropsKeepsOnlySimpleValues() {
        val props = cleanProps(mapOf("s" to "x", "i" to 3, "b" to false, "d" to 1.5, "n" to null, "list" to listOf(1), "short" to 2.toShort()))
        assertEquals(mapOf("s" to "x", "i" to 3, "b" to false, "d" to 1.5, "short" to 2.0), props)
    }
}

class ForegroundTrackerTest {
    @Test fun opensOnlyOnLaunchAndReturnFromBackground() {
        var opens = 0
        var backgrounds = 0
        val tracker = ForegroundTracker({ opens++ }, { backgrounds++ })

        tracker.activityStarted()                         // paleidimas
        tracker.activityStopped(isChangingConfigurations = true)  // pasukimas
        tracker.activityStarted()
        assertEquals(1, opens)

        tracker.activityStarted()                         // antras ekranas (pvz. pirkimo langas) virš pirmo
        tracker.activityStopped(false)
        assertEquals(0, backgrounds)

        tracker.activityStopped(false)                    // į foną
        assertEquals(1, backgrounds)
        tracker.activityStarted()                         // grįžo
        assertEquals(2, opens)
    }
}
