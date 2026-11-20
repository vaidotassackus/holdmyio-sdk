package com.holdmyio

/**
 * When the app is really opened and when it goes to the background, based on the started Activity count.
 * System dialogs (permissions) do not stop the Activity, and a rotation (`changingConfigurations`)
 * stops and immediately restarts the same screen: neither of them counts as an open.
 * A background launch (WorkManager, notifications) starts no Activity and is not counted as an open.
 */
internal class ForegroundTracker(private val onOpen: () -> Unit, private val onBackground: () -> Unit) {
    private var started = 0
    private var changingConfiguration = false

    fun activityStarted() {
        started += 1
        if (started == 1) {
            if (changingConfiguration) changingConfiguration = false else onOpen()
        }
    }

    fun activityStopped(isChangingConfigurations: Boolean) {
        started = maxOf(0, started - 1)
        if (isChangingConfigurations) changingConfiguration = true
        else if (started == 0) onBackground()
    }
}
