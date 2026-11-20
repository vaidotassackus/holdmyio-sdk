package com.holdmyio

/**
 * Kada appsas tikrai atidaromas ir kada išeina į foną, pagal startuotų Activity skaičių.
 * Sistemos langai (leidimai) Activity nesustabdo, o ekrano pasukimas (`changingConfigurations`)
 * sustabdo ir iškart vėl startuoja tą patį ekraną: nė vienas iš jų nėra atidarymas.
 * Paleidimas fone (WorkManager, pranešimai) Activity nestartuoja ir atidarymu nelaikomas.
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
