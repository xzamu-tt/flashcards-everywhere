/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Foreground service that polls UsageStatsManager to detect which app is in
 * the foreground. When a blocked app is detected, it shows a full-screen
 * overlay requiring the user to grade N flashcards before the app unlocks.
 *
 * The unlock is temporary — controlled by `block_unlock_duration_min` in
 * settings. After the duration expires, the blocker re-engages.
 *
 * Full flavor only. Uses SYSTEM_ALERT_WINDOW (not AccessibilityService).
 */
package com.flashcardseverywhere.service.blocking

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
import com.flashcardseverywhere.surface.overlay.CardOverlayManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AppBlockerService : LifecycleService() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var overlayManager: CardOverlayManager
    @Inject lateinit var session: ReviewSession

    private var pollerJob: Job? = null

    /** Packages temporarily unlocked. Map<packageName, unlockExpiresAtMs>. */
    private val unlocked = mutableMapOf<String, Long>()

    /**
     * True while a blocker or card overlay is on screen. Prevents the poll
     * loop from firing duplicate blockers due to race conditions.
     */
    @Volatile
    private var blockingInProgress = false

    /** How many cards the user has graded in the current blocking session. */
    private var cardsReviewedForCurrentBlock = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val n: Notification = NotificationCompat
            .Builder(this, FlashcardsApp.CHANNEL_PACING)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("App blocker active")
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
                while (true) {
                    runCatching { pollForegroundApp() }
                        .onFailure { Log.w(TAG, "Poll error", it) }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

        return Service.START_STICKY
    }

    private suspend fun pollForegroundApp() {
        // Don't re-trigger while a blocker/card is already on screen.
        if (blockingInProgress) return

        if (!settings.appBlockingEnabled.first()) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        val fg = getCurrentForegroundPackage() ?: return

        // Never block ourselves, system UI, launchers, Settings, or Play Store.
        if (fg == packageName) return
        if (fg in WHITELISTED_PACKAGES || isWhitelistedPrefix(fg)) return

        // Check if this app should be blocked.
        val blockAll = settings.blockAllApps.first()
        val blockedSet = settings.blockedPackages.first()
        val shouldBlock = blockAll || blockedSet.contains(fg)
        if (!shouldBlock) return

        // Check temporary unlock.
        val now = System.currentTimeMillis()
        val expiresAt = unlocked[fg]
        if (expiresAt != null && now < expiresAt) return
        unlocked.remove(fg) // expired or absent

        // Prevent re-entry: mark blocking in progress before showing anything.
        blockingInProgress = true
        cardsReviewedForCurrentBlock = 0

        val requiredCards = settings.cardsToUnlock.first()
        val unlockMin = settings.blockUnlockDurationMin.first()

        Log.d(TAG, "Blocking $fg — requires $requiredCards card(s) to unlock for ${unlockMin}m")

        showBlockerOverlay(fg, requiredCards, unlockMin)
    }

    /**
     * Shows the initial blocker screen with a "Start reviewing" button.
     */
    private fun showBlockerOverlay(
        foregroundPkg: String,
        requiredCards: Int,
        unlockMin: Int,
    ) {
        val remaining = requiredCards - cardsReviewedForCurrentBlock
        overlayManager.showBlocker(
            message = "Review $remaining card${if (remaining != 1) "s" else ""} to unlock this app for $unlockMin minutes.",
            cardsRemaining = remaining,
            onReviewCard = {
                // User tapped "Start reviewing" — show the first card.
                showNextCard(foregroundPkg, requiredCards, unlockMin)
            },
        )
    }

    /**
     * Fetches the next due card and shows it. When the user grades it,
     * the [onCardGraded] callback decides whether to show another card
     * or unlock the app.
     */
    private fun showNextCard(
        foregroundPkg: String,
        requiredCards: Int,
        unlockMin: Int,
    ) {
        lifecycleScope.launch {
            session.refresh(deckId = settings.selectedDeckId.first())
            val state = session.state.value
            val card = (state as? ReviewState.Card)?.card

            if (card != null) {
                val remaining = requiredCards - cardsReviewedForCurrentBlock
                Log.d(TAG, "Showing card ${cardsReviewedForCurrentBlock + 1}/$requiredCards for $foregroundPkg")
                overlayManager.showCard(card, onGraded = {
                    onCardGraded(foregroundPkg, requiredCards, unlockMin)
                })
            } else {
                // No cards due — unlock anyway so the user isn't stuck.
                Log.d(TAG, "No cards due — unlocking $foregroundPkg")
                unlockApp(foregroundPkg, unlockMin)
            }
        }
    }

    /**
     * Called each time the user grades a card in the current blocking session.
     * If enough cards have been reviewed, unlocks the app. Otherwise shows
     * the next card.
     */
    private fun onCardGraded(
        foregroundPkg: String,
        requiredCards: Int,
        unlockMin: Int,
    ) {
        cardsReviewedForCurrentBlock++
        Log.d(TAG, "Card graded ($cardsReviewedForCurrentBlock/$requiredCards) for $foregroundPkg")

        if (cardsReviewedForCurrentBlock >= requiredCards) {
            unlockApp(foregroundPkg, unlockMin)
        } else {
            // Show the next card directly (no blocker screen in between).
            showNextCard(foregroundPkg, requiredCards, unlockMin)
        }
    }

    /**
     * Grants a temporary unlock for [foregroundPkg] and tears down the overlay.
     */
    private fun unlockApp(foregroundPkg: String, unlockMin: Int) {
        val expiresAt = System.currentTimeMillis() + unlockMin * 60_000L
        unlocked[foregroundPkg] = expiresAt
        Log.d(TAG, "Unlocked $foregroundPkg until ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(expiresAt)}")
        overlayManager.dismiss()
        blockingInProgress = false
        cardsReviewedForCurrentBlock = 0
    }

    /**
     * Queries UsageStatsManager for the most recent ACTIVITY_RESUMED event
     * in the last 10 seconds.
     */
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

    override fun onDestroy() {
        pollerJob?.cancel()
        pollerJob = null
        overlayManager.dismiss()
        blockingInProgress = false
        super.onDestroy()
    }

    private fun isWhitelistedPrefix(pkg: String): Boolean =
        WHITELISTED_PREFIXES.any { pkg.startsWith(it) }

    companion object {
        private const val TAG = "AppBlockerService"
        private const val FOREGROUND_ID = 2002
        private const val POLL_INTERVAL_MS = 1_000L // 1 second

        /** Exact-match whitelist. */
        private val WHITELISTED_PACKAGES = setOf(
            "com.android.settings",
            "com.android.vending", // Play Store
        )

        /** Prefix-match whitelist for system UI and launchers. */
        private val WHITELISTED_PREFIXES = listOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher", // Samsung
            "com.huawei.android.launcher", // Huawei
            "com.miui.home", // Xiaomi
        )

        fun start(ctx: Context) {
            val intent = Intent(ctx, AppBlockerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AppBlockerService::class.java))
        }
    }
}
