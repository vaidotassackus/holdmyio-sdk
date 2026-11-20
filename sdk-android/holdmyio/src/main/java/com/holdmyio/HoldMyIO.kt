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
 * Viešas SDK API (tas pats kaip Swift SDK).
 *
 *     HoldMyIO.configure(application, appKey = "pk_live_...", endpoint = "https://io.holdmy.company")
 *     HoldMyIO.screen("editor")
 *     HoldMyIO.track("export_done", mapOf("format" to "reel", "seconds" to 42))
 *
 * Automatiškai siunčiami `session_start` ir `app_open`. Įvykiai kaupiami diske ir siunčiami
 * paketais, todėl veikia ir be interneto. Klaidos nuryjamos: analitika niekada nenulaužia appso.
 */
object HoldMyIO {
    data class Options(
        /** Kas kiek milisekundžių bandoma išsiųsti eilę. */
        val flushIntervalMs: Long = 30_000,
        /** Eilėje susikaupus tiek įvykių, siunčiama iškart. */
        val flushAt: Int = 20,
        /** Įvykių skaičius viename pakete (serveris priima iki 100). */
        val batchSize: Int = 50,
        /** Daugiausia įvykių, laikomų diske be interneto. */
        val maxQueueSize: Int = 1000,
        /** Po tiek milisekundžių neveiklumo pradedama nauja sesija. */
        val sessionTimeoutMs: Long = 30 * 60 * 1000,
        /** Debug buildų įvykiai pažymimi ir į statistiką neįtraukiami. null: pagal appso debuggable vėliavą. */
        val debug: Boolean? = null,
    )

    @Volatile private var core: Core? = null

    /** Kviesk vieną kartą `Application.onCreate()`, prieš kitus kvietimus. */
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
                // Analitika niekada neturi nulaužti appso
            }
        }
    }

    /** Bet koks įvykis. Pavadinimas: raidės, skaičiai, `_` ir `.`, iki 64 simbolių. Reikšmės: tekstas, skaičius, bool. */
    @JvmStatic
    @JvmOverloads
    fun track(name: String, props: Map<String, Any?> = emptyMap()) {
        core?.record(name, cleanProps(props))
    }

    /** Ekrano peržiūra. Vėlesni įvykiai priskiriami šiam ekranui, kol nepakviesi su kitu. */
    @JvmStatic
    @JvmOverloads
    fun screen(name: String, props: Map<String, Any?> = emptyMap()) {
        core?.record("screen_view", cleanProps(props), setScreen = name)
    }

    /** Pirkimas. `price` yra sumokėta kaina, `currency` ISO 4217 kodas, pvz. "EUR". */
    @JvmStatic
    @JvmOverloads
    fun purchase(productId: String, price: Double, currency: String, props: Map<String, Any?> = emptyMap()) {
        if (price < 0 || price.isNaN()) return
        core?.record("purchase", cleanProps(props), Revenue(price, currency.uppercase(Locale.ROOT), productId))
    }

    /** Išsiunčia eilę dabar, nelaukiant laikmačio. */
    @JvmStatic
    fun flush() {
        core?.flush(force = true)
    }

    /** Naudotojo atsisakymas: išjungus nieko nerenkama, o neišsiųsti įvykiai ištrinami. */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        core?.setEnabled(enabled)
    }

    @JvmStatic
    val isEnabled: Boolean get() = runCatching { core?.isEnabled }.getOrNull() ?: false

    // --- Android

    private fun observeForeground(application: Application, core: Core) {
        val tracker = ForegroundTracker(
            onOpen = { core.record("app_open") },
            onBackground = { core.flush(force = true) },
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
