/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Unified enforcement foreground service. Runs a 1-second tick loop that
 * coordinates all radical phone-restriction features:
 *
 *   1. Screen-time budget — locks the phone when budget is exhausted
 *   2. App blocking — gates individual apps behind card review
 *   3. Doom-scroll detection — interrupts after 5+ min in one app
 *   4. Grayscale enforcement — strips color when phone is locked
 *
 * This service subsumes the former [AppBlockerService] polling loop and adds
 * budget + doom-scroll + grayscale on top.
 *
 * Full flavor only. Uses SYSTEM_ALERT_WINDOW.
 */
package com.flashcardseverywhere.service.enforcement

import android.app.Notification
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.flashcardseverywhere.R
import com.flashcardseverywhere.app.FlashcardsApp
import com.flashcardseverywhere.data.anki.ReviewState
import com.flashcardseverywhere.data.prefs.SettingsRepository
import com.flashcardseverywhere.domain.ReviewSession
import com.flashcardseverywhere.service.budget.ScreenTimeBudgetEngine
import com.flashcardseverywhere.service.doom.DoomScrollDetector
import com.flashcardseverywhere.service.grayscale.GrayscaleManager
import com.flashcardseverywhere.surface.overlay.CardOverlayManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class EnforcementService : LifecycleService() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var overlayManager: CardOverlayManager
    @Inject lateinit var session: ReviewSession
    @Inject lateinit var budgetEngine: ScreenTimeBudgetEngine
    @Inject lateinit var doomDetector: DoomScrollDetector
    @Inject lateinit var grayscale: GrayscaleManager

    private var pollerJob: Job? = null

    /** Packages temporarily unlocked (app blocker). Map<packageName, expiresAtMs>. */
    private val unlocked = mutableMapOf<String, Long>()

    @Volatile private var overlayInProgress = false
    private var cardsReviewedForCurrentBlock = 0

    /** Tracks what triggered the current overlay for correct handling. */
    private var currentTrigger: Trigger = Trigger.NONE

    /** Timestamp of last overlay dismissal — used for cooldown to prevent spam. */
    private var lastOverlayDismissedAt = 0L

    private enum class Trigger { NONE, BUDGET, APP_BLOCK, DOOM_SCROLL }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val n: Notification = NotificationCompat
            .Builder(this, FlashcardsApp.CHANNEL_PACING)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Enforcement active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_ID, n)
        }

        if (pollerJob == null) {
            pollerJob = lifecycleScope.launch {
                // Wait for DataStore writes from the ViewModel to commit
                // before the first tick. Without this, the first tick can
                // read stale/default values (e.g. budgetEnabled=false).
                delay(STARTUP_DELAY_MS)
                while (true) {
                    runCatching { tick() }
                        .onFailure { Log.w(TAG, "Enforcement tick error", it) }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

        return Service.START_STICKY
    }

    /**
     * Main enforcement tick. Priority order:
     *   1. Budget lockout (highest — if budget is exhausted, lock everything)
     *   2. App blocking (gate specific apps)
     *   3. Doom-scroll detection (interrupt after threshold)
     *   4. Grayscale management
     */
    private suspend fun tick() {
        if (overlayInProgress) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        // Never enforce anything while the user is inside our own app.
        // They need full access to settings, the reviewer, and the dashboard.
        val fg = getCurrentForegroundPackage()
        if (fg == packageName) return

        // Cooldown after last overlay dismissal — prevents rapid re-lock spam.
        val now = System.currentTimeMillis()
        if (now - lastOverlayDismissedAt < OVERLAY_COOLDOWN_MS) return

        // ── 1. Screen-time budget ────────────────────────────────────
        val budgetLocked = budgetEngine.tick()
        if (budgetLocked) {
            handleBudgetLockout()
            return
        } else {
            // Budget available — ensure grayscale is off (reward: color)
            grayscale.disableGrayscale()
        }

        // ── 2. App blocking (existing M7 logic) ─────────────────────
        if (checkAppBlocking(fg)) return

        // ── 3. Doom-scroll detection ─────────────────────────────────
        val doomEvent = doomDetector.tick()
        if (doomEvent != null) {
            handleDoomScroll(doomEvent)
            return
        }
    }

    // ── Budget lockout ───────────────────────────────────────────────

    private suspend fun handleBudgetLockout() {
        overlayInProgress = true
        currentTrigger = Trigger.BUDGET
        cardsReviewedForCurrentBlock = 0

        // Enable grayscale while locked.
        grayscale.enableGrayscale()

        val budgetState = budgetEngine.getBudgetState()
        val nextCardMin = String.format("%.1f", budgetState.nextCardValueMinutes)

        overlayManager.showBlocker(
            message = "Screen time budget exhausted.\n" +
                "You've used ${budgetState.consumedMinutes}min today.\n" +
                "Review a card to earn ~${nextCardMin}min of screen time.",
            cardsRemaining = 1,
            onReviewCard = { showBudgetCard() },
        )
    }

    private fun showBudgetCard() {
        lifecycleScope.launch {
            session.refresh(deckId = settings.selectedDeckId.first())
            val card = (session.state.value as? ReviewState.Card)?.card

            if (card != null) {
                overlayManager.showCard(card, onGraded = {
                    onBudgetCardGraded()
                })
            } else {
                // No cards due — give a freebie unlock (5 min).
                Log.d(TAG, "No cards due — granting freebie 5min budget")
                lifecycleScope.launch {
                    settings.creditBudgetCard(5 * 60_000L)
                    grayscale.disableGrayscale()
                }
                teardownOverlay()
            }
        }
    }

    private fun onBudgetCardGraded() {
        lifecycleScope.launch {
            val earnedMs = budgetEngine.calculateEarnedMsPerCard()
            settings.creditBudgetCard(earnedMs)
            settings.incrementStatsCardsReviewed()
            grayscale.disableGrayscale()
            Log.d(TAG, "Budget card graded — earned ${earnedMs / 60_000}min screen time")
        }
        teardownOverlay()
    }

    // ── App blocking (from former AppBlockerService) ─────────────────

    private suspend fun checkAppBlocking(fg: String?): Boolean {
        if (!settings.appBlockingEnabled.first()) return false
        if (fg == null) return false
        // Our own app is already excluded in tick(), but double-check here.
        if (fg == packageName) return false
        if (fg in WHITELISTED_PACKAGES || isWhitelistedPrefix(fg)) return false

        val blockAll = settings.blockAllApps.first()
        val blockedSet = settings.blockedPackages.first()
        if (!blockAll && !blockedSet.contains(fg)) return false

        val now = System.currentTimeMillis()
        val expiresAt = unlocked[fg]
        if (expiresAt != null && now < expiresAt) return false
        unlocked.remove(fg)

        overlayInProgress = true
        currentTrigger = Trigger.APP_BLOCK
        cardsReviewedForCurrentBlock = 0

        val requiredCards = settings.cardsToUnlock.first()
        val unlockMin = settings.blockUnlockDurationMin.first()

        showAppBlockOverlay(fg, requiredCards, unlockMin)
        return true
    }

    private fun showAppBlockOverlay(pkg: String, required: Int, unlockMin: Int) {
        val remaining = required - cardsReviewedForCurrentBlock
        overlayManager.showBlocker(
            message = "Review $remaining card${if (remaining != 1) "s" else ""} to unlock this app for $unlockMin minutes.",
            cardsRemaining = remaining,
            onReviewCard = { showAppBlockCard(pkg, required, unlockMin) },
        )
    }

    private fun showAppBlockCard(pkg: String, required: Int, unlockMin: Int) {
        lifecycleScope.launch {
            session.refresh(deckId = settings.selectedDeckId.first())
            val card = (session.state.value as? ReviewState.Card)?.card
            if (card != null) {
                overlayManager.showCard(card, onGraded = {
                    onAppBlockCardGraded(pkg, required, unlockMin)
                })
            } else {
                unlockApp(pkg, unlockMin)
            }
        }
    }

    private fun onAppBlockCardGraded(pkg: String, required: Int, unlockMin: Int) {
        cardsReviewedForCurrentBlock++
        lifecycleScope.launch {
            settings.incrementStatsCardsReviewed()
            // Also credit budget if budget mode is active.
            if (settings.budgetEnabled.first()) {
                val earnedMs = budgetEngine.calculateEarnedMsPerCard()
                settings.creditBudgetCard(earnedMs)
            }
        }
        if (cardsReviewedForCurrentBlock >= required) {
            unlockApp(pkg, unlockMin)
        } else {
            showAppBlockCard(pkg, required, unlockMin)
        }
    }

    private fun unlockApp(pkg: String, unlockMin: Int) {
        unlocked[pkg] = System.currentTimeMillis() + unlockMin * 60_000L
        teardownOverlay()
    }

    // ── Doom-scroll handling ─────────────────────────────────────────

    private suspend fun handleDoomScroll(event: DoomScrollDetector.DoomScrollEvent) {
        overlayInProgress = true
        currentTrigger = Trigger.DOOM_SCROLL

        // Enable grayscale for the doom-scroll interrupt.
        grayscale.enableGrayscale()

        val durationMin = (event.sessionDurationMs / 60_000L).toInt()
        overlayManager.showBlocker(
            message = "You've been scrolling for ${durationMin}min.\nReview a card to continue.",
            cardsRemaining = 1,
            onReviewCard = { showDoomScrollCard() },
        )
    }

    private fun showDoomScrollCard() {
        lifecycleScope.launch {
            session.refresh(deckId = settings.selectedDeckId.first())
            val card = (session.state.value as? ReviewState.Card)?.card
            if (card != null) {
                overlayManager.showCard(card, onGraded = {
                    onDoomScrollCardGraded()
                })
            } else {
                onDoomScrollCardGraded() // No cards — just dismiss
            }
        }
    }

    private fun onDoomScrollCardGraded() {
        doomDetector.onInterruptHandled()
        lifecycleScope.launch {
            settings.incrementStatsCardsReviewed()
            grayscale.disableGrayscale()
            if (settings.budgetEnabled.first()) {
                val earnedMs = budgetEngine.calculateEarnedMsPerCard()
                settings.creditBudgetCard(earnedMs)
            }
        }
        teardownOverlay()
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun teardownOverlay() {
        overlayManager.dismiss()
        overlayInProgress = false
        currentTrigger = Trigger.NONE
        cardsReviewedForCurrentBlock = 0
        lastOverlayDismissedAt = System.currentTimeMillis()
    }

    private fun getCurrentForegroundPackage(): String? {
        val usm = getSystemService<UsageStatsManager>() ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000, now) ?: return null
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

    private fun isWhitelistedPrefix(pkg: String): Boolean =
        WHITELISTED_PREFIXES.any { pkg.startsWith(it) }

    override fun onDestroy() {
        pollerJob?.cancel()
        pollerJob = null
        overlayManager.dismiss()
        overlayInProgress = false
        // Can't use lifecycleScope here — lifecycle is already destroyed.
        // Fire-and-forget on a global scope for cleanup.
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch { grayscale.disableGrayscale() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EnforcementService"
        private const val FOREGROUND_ID = 2003
        private const val POLL_INTERVAL_MS = 1_000L
        /** Wait for DataStore commits before the first tick. */
        private const val STARTUP_DELAY_MS = 3_000L
        /** Minimum ms between overlay dismissal and the next lockout. Prevents spam. */
        private const val OVERLAY_COOLDOWN_MS = 5_000L

        private val WHITELISTED_PACKAGES = setOf(
            "com.android.settings",
            "com.android.vending",
        )

        private val WHITELISTED_PREFIXES = listOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.huawei.android.launcher",
            "com.miui.home",
        )

        fun start(ctx: Context) {
            val intent = Intent(ctx, EnforcementService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EnforcementService::class.java))
        }
    }
}
