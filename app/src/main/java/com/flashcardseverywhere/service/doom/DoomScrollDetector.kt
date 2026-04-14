/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Detects continuous single-app usage ("doom scrolling") and triggers
 * a flashcard overlay interrupt after the configured threshold.
 *
 * Uses UsageStatsManager.queryEvents() to track per-app foreground sessions.
 * A "doom-scroll session" resets when the user:
 *   - Switches to a different app
 *   - Turns the screen off for more than 30 seconds
 *   - Reviews a card (interrupt was shown)
 *
 * Polling is 5-second intervals (adequate for 5-minute threshold detection).
 * Called from the enforcement service tick loop.
 */
package com.flashcardseverywhere.service.doom

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import com.flashcardseverywhere.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DoomScrollDetector @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
) {

    private val usm: UsageStatsManager? get() = ctx.getSystemService()

    /** Package currently being tracked for doom-scrolling. */
    private var trackedPackage: String? = null

    /** Timestamp when the tracked package first entered foreground. */
    private var sessionStartMs: Long = 0L

    /** Set to true after an interrupt fires, to avoid re-triggering until app switch. */
    private var interruptFired = false

    /**
     * Runs one detection tick. Returns a [DoomScrollEvent] if the user has been
     * in a single app past the threshold, or `null` if no action needed.
     */
    suspend fun tick(): DoomScrollEvent? {
        if (!settings.doomScrollEnabled.first()) return null

        val thresholdMs = settings.doomScrollThresholdMin.first() * 60_000L
        val currentFg = getCurrentForegroundPackage() ?: return null

        // Ignore system UI, launchers, and our own app.
        if (isSystemPackage(currentFg) || currentFg == ctx.packageName) {
            // Don't reset — brief system UI visits (notification shade, quick settings)
            // shouldn't break the session.
            return null
        }

        val now = System.currentTimeMillis()

        if (currentFg != trackedPackage) {
            // App switch — start new tracking session.
            trackedPackage = currentFg
            sessionStartMs = now
            interruptFired = false
            return null
        }

        if (interruptFired) return null

        val sessionDurationMs = now - sessionStartMs
        if (sessionDurationMs >= thresholdMs) {
            interruptFired = true
            val durationMin = (sessionDurationMs / 60_000L).toInt()
            Log.d(TAG, "Doom-scroll detected: $currentFg for ${durationMin}m (threshold: ${thresholdMs / 60_000}m)")
            settings.incrementStatsDoomScrollInterrupts()
            return DoomScrollEvent(
                packageName = currentFg,
                sessionDurationMs = sessionDurationMs,
            )
        }

        return null
    }

    /** Call when the user grades a card from a doom-scroll interrupt. */
    fun onInterruptHandled() {
        // Reset tracking so a new session starts after the interrupt.
        trackedPackage = null
        sessionStartMs = 0L
        interruptFired = false
    }

    private fun getCurrentForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val events = usm?.queryEvents(now - 10_000, now) ?: return null
        val ev = UsageEvents.Event()
        var lastFg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastFg = ev.packageName
            }
        }
        return lastFg
    }

    private fun isSystemPackage(pkg: String): Boolean =
        pkg.startsWith("com.android.systemui") ||
            pkg.startsWith("com.android.launcher") ||
            pkg.startsWith("com.google.android.apps.nexuslauncher") ||
            pkg.startsWith("com.sec.android.app.launcher") ||
            pkg.startsWith("com.huawei.android.launcher") ||
            pkg.startsWith("com.miui.home") ||
            pkg == "com.android.settings"

    data class DoomScrollEvent(
        val packageName: String,
        val sessionDurationMs: Long,
    )

    companion object {
        private const val TAG = "DoomScrollDetector"
    }
}
