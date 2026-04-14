/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Dynamic screen-time budget engine.
 *
 * Philosophy: the phone is LOCKED by default. Reviewing flashcards earns
 * screen time. The ratio is dynamic:
 *
 *   - Starts very restrictive (1 card = base minutes, default 2 min).
 *   - As daily screen time rises, the ratio TIGHTENS (you earn less per card).
 *   - As cards reviewed increase, the ratio LOOSENS slightly (rewarding study).
 *   - Net effect: morning use is cheap, afternoon use costs more, evening use
 *     is brutally expensive.
 *
 * The engine is stateless — all persistence lives in DataStore via
 * [SettingsRepository]. It survives process death.
 *
 * Called from [EnforcementService] on every tick (~1 second).
 */
package com.flashcardseverywhere.service.budget

import com.flashcardseverywhere.data.prefs.SettingsRepository
import com.flashcardseverywhere.service.pacing.UsagePulseSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenTimeBudgetEngine @Inject constructor(
    private val settings: SettingsRepository,
    private val usagePulse: UsagePulseSource,
) {

    /**
     * Runs one tick. Returns `true` if the phone should be LOCKED (budget
     * exhausted or never earned), `false` if the user has remaining budget.
     */
    suspend fun tick(): Boolean {
        if (!settings.budgetEnabled.first()) return false

        ensureDayRollover()

        val now = System.currentTimeMillis()
        val dayAnchor = settings.budgetDayAnchor.first()

        // Measure total screen-on time today via UsageStatsManager.
        val screenMsToday = usagePulse.foregroundMillisSince(dayAnchor)
        settings.updateStatsScreenMs(screenMsToday)

        val earnedMs = settings.budgetEarnedMs.first()

        // Budget exhausted: screen time consumed >= earned budget.
        // We use actual screen time as "consumed" — no need for a separate counter.
        val remaining = earnedMs - screenMsToday

        if (remaining <= 0L) {
            if (!settings.budgetLocked.first()) {
                settings.setBudgetLocked(true)
                settings.incrementStatsLockouts()
            }
            return true // LOCK
        }

        // Budget available — phone is unlocked.
        if (settings.budgetLocked.first()) {
            settings.setBudgetLocked(false)
        }
        return false
    }

    /**
     * Calculates how many milliseconds of screen time one card review is worth
     * RIGHT NOW, factoring in the dynamic ratio.
     *
     * Formula:
     *   earnedMs = baseMinutesPerCard * 60_000 * multiplier
     *
     * Where multiplier is:
     *   - Starts at 1.0
     *   - +0.02 per card reviewed today (study bonus, capped at +1.0)
     *   - -0.15 per hour of screen time today (screen time penalty, capped at -0.8)
     *   - Floored at 0.2 (minimum: you always earn SOMETHING)
     *
     * This means:
     *   - Morning (0h screen): 1 card = 2.0 min (base)
     *   - After 20 cards reviewed: 1 card = 2.8 min (study bonus)
     *   - After 2h screen time: 1 card = 1.4 min (penalty kicks in)
     *   - After 4h screen + few cards: 1 card = 0.8 min (brutal)
     *   - After 6h screen: 1 card = 0.4 min minimum floor
     */
    suspend fun calculateEarnedMsPerCard(): Long {
        val dayAnchor = settings.budgetDayAnchor.first().takeIf { it > 0L }
            ?: SettingsRepository.todayAnchorMs()
        val screenMsToday = usagePulse.foregroundMillisSince(dayAnchor)
        val cardsToday = settings.budgetCardsReviewedToday.first()
        val baseMin = settings.budgetBaseMinutesPerCard.first()

        val screenHours = screenMsToday / 3_600_000.0
        val studyBonus = (cardsToday * 0.02).coerceAtMost(1.0)
        val screenPenalty = (screenHours * 0.15).coerceAtMost(0.8)
        val multiplier = (1.0 + studyBonus - screenPenalty).coerceAtLeast(0.2)

        return (baseMin * 60_000L * multiplier).toLong()
    }

    /**
     * Returns the current budget state for UI display.
     */
    suspend fun getBudgetState(): BudgetState {
        val dayAnchor = settings.budgetDayAnchor.first().takeIf { it > 0L }
            ?: SettingsRepository.todayAnchorMs()
        val screenMsToday = usagePulse.foregroundMillisSince(dayAnchor)
        val earnedMs = settings.budgetEarnedMs.first()
        val cardsToday = settings.budgetCardsReviewedToday.first()
        val locked = settings.budgetLocked.first()
        val nextCardValue = calculateEarnedMsPerCard()

        return BudgetState(
            earnedMs = earnedMs,
            consumedMs = screenMsToday,
            remainingMs = (earnedMs - screenMsToday).coerceAtLeast(0L),
            cardsReviewedToday = cardsToday,
            isLocked = locked,
            nextCardValueMs = nextCardValue,
        )
    }

    /**
     * Resets budget counters if the day has rolled over (past midnight).
     */
    private suspend fun ensureDayRollover() {
        val anchor = settings.budgetDayAnchor.first()
        val todayAnchor = SettingsRepository.todayAnchorMs()
        if (anchor < todayAnchor) {
            settings.resetBudgetDay()
            settings.resetStatsDay()
        }
    }

    data class BudgetState(
        val earnedMs: Long,
        val consumedMs: Long,
        val remainingMs: Long,
        val cardsReviewedToday: Int,
        val isLocked: Boolean,
        val nextCardValueMs: Long,
    ) {
        val earnedMinutes: Int get() = (earnedMs / 60_000L).toInt()
        val consumedMinutes: Int get() = (consumedMs / 60_000L).toInt()
        val remainingMinutes: Int get() = (remainingMs / 60_000L).toInt()
        val nextCardValueMinutes: Double get() = nextCardValueMs / 60_000.0
    }
}
