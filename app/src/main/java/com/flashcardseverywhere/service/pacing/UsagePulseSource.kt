/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.service.pacing

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts foreground / interactive screen time using `UsageStatsManager`.
 *
 * Used in BOTH flavors. The full flavor *also* gets real-time foreground app
 * change events from [com.flashcardseverywhere.surface.overlay.CardOverlayAccessibilityService],
 * but the screen-time totals here are still authoritative for the daily goal
 * and pacing tickers.
 *
 * Reference: docs/ANDROID_SURFACES_REFERENCE.md §5.
 */
@Singleton
class UsagePulseSource @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val usm: UsageStatsManager? get() = ctx.getSystemService()

    /**
     * Total foreground time across all apps in the half-open window
     * `[since, now)`. Returns 0 if usage access is not granted or the API
     * returns no events (which it does on locked devices on API 30+).
     */
    fun foregroundMillisSince(since: Long): Long {
        val now = System.currentTimeMillis()
        val events = usm?.queryEvents(since, now) ?: return 0L
        var total = 0L
        var lastForegroundTs: Long? = null
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            when (ev.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (lastForegroundTs == null) lastForegroundTs = ev.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = lastForegroundTs
                    if (start != null) {
                        total += (ev.timeStamp - start).coerceAtLeast(0L)
                        lastForegroundTs = null
                    }
                }
            }
        }
        // Account for the in-progress foreground session.
        lastForegroundTs?.let { total += (now - it).coerceAtLeast(0L) }
        return total
    }
}
