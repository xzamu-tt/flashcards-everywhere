/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * The brain of the pacing loop.
 *
 * Called periodically from [PacingService] (every 15 seconds). On each tick:
 *   1. Read current screen-on time delta from [UsagePulseSource].
 *   2. If inside quiet hours (and not aggressive mode), no-op.
 *   3. If accumulated screen-on time crosses the pacing interval, surface
 *      a card on ALL enabled surfaces simultaneously.
 *   4. Escalation FSM: if a surfaced card wasn't graded within the timeout,
 *      bump the escalation level and re-surface more aggressively.
 *
 * Stateless toward the OS — all timestamps and escalation level live in
 * DataStore via [SettingsRepository] so the engine survives process death.
 */
package com.flashcardseverywhere.service.pacing

import com.flashcardseverywhere.data.anki.DueCard
import com.flashcardseverywhere.data.anki.ReviewState
import com.flashcardseverywhere.data.prefs.SettingsRepository
import com.flashcardseverywhere.domain.ReviewSession
import com.flashcardseverywhere.surface.bubble.BubbleOrchestrator
import com.flashcardseverywhere.surface.notification.NotificationOrchestrator
import com.flashcardseverywhere.surface.overlay.CardOverlayManager
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PacingEngine @Inject constructor(
    private val pulse: UsagePulseSource,
    private val settings: SettingsRepository,
    private val session: ReviewSession,
    private val notifications: NotificationOrchestrator,
    private val overlayManager: CardOverlayManager,
    private val bubbles: BubbleOrchestrator,
) {

    /** Run one tick. Safe to call frequently; internal guards prevent spam. */
    suspend fun tick() {
        // Check escalation before deciding whether to surface a new card.
        checkEscalation()

        val intervalMin = settings.pacingInterval.first()
        val intervalMs = intervalMin * 60_000L

        val aggressive = settings.aggressiveMode.first()
        if (!aggressive && isInsideQuietHours()) return

        val now = System.currentTimeMillis()
        val anchor = settings.lastSyncAt.first().takeIf { it > 0L } ?: now
        val activeMs = pulse.foregroundMillisSince(anchor)

        if (activeMs < intervalMs) return

        // Time to fire. Pull a fresh card and surface on ALL enabled channels.
        session.refresh(deckId = settings.selectedDeckId.first())
        val state = session.state.value
        val card = (state as? ReviewState.Card)?.card ?: return

        val level = resolveEscalationLevel()
        surfaceCard(card, level)

        // Also fire supplementary surfaces (bubble, media session updates widget).
        fireSupplementarySurfaces(card)

        settings.markSyncedNow()
        settings.markCardSurfacedNow()
    }

    /**
     * Check if a previously surfaced card was ignored past the escalation
     * timeout. If so, bump the escalation level and re-surface.
     */
    private suspend fun checkEscalation() {
        if (!settings.escalationEnabled.first()) return

        val surfacedAt = settings.lastCardSurfacedAt.first()
        val gradedAt = settings.lastCardGradedAt.first()
        if (surfacedAt <= 0L) return
        if (gradedAt >= surfacedAt) return

        val timeoutMs = settings.escalationTimeoutSec.first() * 1_000L
        val elapsed = System.currentTimeMillis() - surfacedAt
        if (elapsed < timeoutMs) return

        val currentLevel = settings.escalationLevel.first()
        val maxLevel = EscalationLevel.entries.size - 1
        if (currentLevel >= maxLevel) return

        val newLevel = (currentLevel + 1).coerceAtMost(maxLevel)
        settings.setEscalationLevel(newLevel)
        settings.markCardSurfacedNow()

        session.refresh(deckId = settings.selectedDeckId.first())
        val state = session.state.value
        val card = (state as? ReviewState.Card)?.card ?: return
        surfaceCard(card, EscalationLevel.entries[newLevel])
    }

    /**
     * Surface a card on the primary escalation channel. Falls through to
     * the next-best surface if the preferred one is disabled.
     */
    private suspend fun surfaceCard(card: DueCard, level: EscalationLevel) {
        val vibrate = settings.vibrateOnCard.first()
        when (level) {
            EscalationLevel.NOTIFICATION -> {
                if (settings.notificationEnabled.first()) {
                    notifications.postDueCard(card, vibrate)
                }
            }
            EscalationLevel.LOCKSCREEN -> {
                if (settings.lockscreenEnabled.first()) {
                    notifications.postLockscreenCard(card)
                } else if (settings.notificationEnabled.first()) {
                    notifications.postDueCard(card, vibrate)
                }
            }
            EscalationLevel.OVERLAY -> {
                if (settings.overlayEnabled.first()) {
                    overlayManager.showCard(card)
                } else if (settings.lockscreenEnabled.first()) {
                    notifications.postLockscreenCard(card)
                } else if (settings.notificationEnabled.first()) {
                    notifications.postDueCard(card, vibrate)
                }
            }
        }
    }

    /**
     * Fire all supplementary (non-escalation) surfaces that are enabled.
     * These fire alongside the primary surface for maximum coverage.
     */
    private suspend fun fireSupplementarySurfaces(card: DueCard) {
        // Bubble — floats on top of everything.
        if (settings.bubbleEnabled.first()) {
            bubbles.postBubble(card)
        }

        // Media session is managed by its own service — it auto-updates via
        // ReviewSession observation, so we don't need to push to it here.
        // The pacing tick already called session.refresh() above.
    }

    private suspend fun resolveEscalationLevel(): EscalationLevel {
        if (!settings.escalationEnabled.first()) {
            return when {
                settings.overlayEnabled.first() -> EscalationLevel.OVERLAY
                settings.lockscreenEnabled.first() -> EscalationLevel.LOCKSCREEN
                else -> EscalationLevel.NOTIFICATION
            }
        }
        val level = settings.escalationLevel.first()
        return EscalationLevel.entries.getOrElse(level) { EscalationLevel.NOTIFICATION }
    }

    private suspend fun isInsideQuietHours(): Boolean {
        val start = settings.quietHoursStart.first()
        val end = settings.quietHoursEnd.first()
        if (start == end) return false
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start < end) hour in start until end
               else hour >= start || hour < end
    }

    enum class EscalationLevel { NOTIFICATION, LOCKSCREEN, OVERLAY }
}
