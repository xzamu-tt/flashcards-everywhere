/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Foreground service that polls UsageStatsManager to detect which app is in
 * the foreground. When a blocked app is detected, it shows a full-screen
 * overlay requiring the user to grade a flashcard before the app unlocks.
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
import android.os.IBinder
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
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

        return Service.START_STICKY
    }

    private suspend fun pollForegroundApp() {
        if (!settings.appBlockingEnabled.first()) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        val fg = getCurrentForegroundPackage() ?: return

        // Never block ourselves, system UI, or the launcher.
        if (fg == packageName) return
        if (fg.startsWith("com.android.systemui")) return
        if (fg.startsWith("com.android.launcher")) return
        if (fg.startsWith("com.google.android.apps.nexuslauncher")) return

        // Check if this app should be blocked.
        val blockAll = settings.blockAllApps.first()
        val blockedSet = settings.blockedPackages.first()
        val shouldBlock = blockAll || blockedSet.contains(fg)
        if (!shouldBlock) return

        // Check temporary unlock.
        val now = System.currentTimeMillis()
        val expiresAt = unlocked[fg]
        if (expiresAt != null && now < expiresAt) return
        unlocked.remove(fg) // expired

        // Show blocker overlay.
        if (overlayManager.isShowing) return // already showing

        val unlockMin = settings.blockUnlockDurationMin.first()
        overlayManager.showBlocker(
            message = "Review a flashcard to unlock this app for $unlockMin minutes.",
            onGradeCard = {
                overlayManager.dismiss()
                // Show a flashcard overlay instead.
                lifecycleScope.launch {
                    session.refresh(deckId = settings.selectedDeckId.first())
                    val state = session.state.value
                    val card = (state as? ReviewState.Card)?.card
                    if (card != null) {
                        overlayManager.showCard(card)
                    } else {
                        // No cards due — unlock anyway.
                        unlocked[fg] = System.currentTimeMillis() + unlockMin * 60_000L
                    }
                }
            }
        )
    }

    private fun getCurrentForegroundPackage(): String? {
        val usm = getSystemService<UsageStatsManager>() ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 5_000, now) ?: return null
        val ev = UsageEvents.Event()
        var lastFg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastFg = ev.packageName
            }
        }
        return lastFg
    }

    override fun onDestroy() {
        pollerJob?.cancel()
        pollerJob = null
        overlayManager.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_ID = 2002
        private const val POLL_INTERVAL_MS = 2_000L // 2 seconds

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
