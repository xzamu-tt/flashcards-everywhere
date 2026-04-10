/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.service.pacing

import com.flashcardseverywhere.data.anki.ReviewState
import com.flashcardseverywhere.data.prefs.SettingsRepository
import com.flashcardseverywhere.domain.ReviewSession
import com.flashcardseverywhere.surface.notification.NotificationOrchestrator
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The brain of the "10-min pacing" loop.
 *
 * Called periodically from [PacingService] (every minute or so). On each tick:
 *   1. Read current screen-on time delta from [UsagePulseSource].
 *   2. If we're inside quiet hours, no-op.
 *   3. If accumulated screen-on time since the last surfaced card crosses the
 *      pacing interval, ask [ReviewSession] to refresh and surface the next
 *      card via [NotificationOrchestrator].
 *   4. Track delivery for the escalation FSM (next surface = lockscreen,
 *      next-next surface = full overlay).
 *
 * Stateless toward the OS — all "since when" timestamps live in DataStore via
 * [SettingsRepository] so the engine survives process death.
 */
@Singleton
class PacingEngine @Inject constructor(
    private val pulse: UsagePulseSource,
    private val settings: SettingsRepository,
    private val session: ReviewSession,
    private val notifications: NotificationOrchestrator,
) {

    /** Run one tick. Safe to call frequently; internal guards prevent spam. */
    suspend fun tick() {
        val intervalMin = settings.pacingInterval.first()
        val intervalMs = intervalMin * 60_000L

        if (isInsideQuietHours()) return

        val now = System.currentTimeMillis()
        val anchor = settings.lastSyncAt.first().takeIf { it > 0L } ?: now
        val activeMs = pulse.foregroundMillisSince(anchor)

        if (activeMs < intervalMs) return

        // Time to fire. Pull a fresh card and pick the right surface.
        // No-op if the user hasn't picked a deck — the reviewer will surface
        // NoDeckSelected and we shouldn't post a notification for an empty
        // queue.
        session.refresh(deckId = settings.selectedDeckId.first())
        val state = session.state.value
        val card = (state as? ReviewState.Card)?.card ?: return

        when (currentEscalationLevel()) {
            EscalationLevel.NOTIFICATION -> notifications.postDueCard(card)
            EscalationLevel.LOCKSCREEN -> notifications.postLockscreenCard(card)
            EscalationLevel.OVERLAY -> notifications.postLockscreenCard(card)
            // OVERLAY would call into surface/overlay/CardOverlayWindow on the
            // full flavor; for now we degrade to lockscreen so the lite flavor
            // still gets *something* aggressive.
        }
        settings.markSyncedNow()
    }

    private suspend fun isInsideQuietHours(): Boolean {
        val start = settings.quietHoursStart.first()
        val end = settings.quietHoursEnd.first()
        if (start == end) return false  // disabled when collapsed
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // Window may wrap around midnight (e.g. 23..7 → quiet at 23,0,1,...,6).
        return if (start < end) hour in start until end
               else hour >= start || hour < end
    }

    private fun currentEscalationLevel(): EscalationLevel {
        // TODO: track per-tick escalation in DataStore. v0: always notification.
        return EscalationLevel.NOTIFICATION
    }

    enum class EscalationLevel { NOTIFICATION, LOCKSCREEN, OVERLAY }
}
