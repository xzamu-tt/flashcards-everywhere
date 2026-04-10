/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.service.pacing

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.flashcardseverywhere.R
import com.flashcardseverywhere.app.FlashcardsApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that:
 *   - Tracks screen-on time via `UsageStatsManager` (lite) or via the
 *     full-flavor AccessibilityService bus.
 *   - Every N minutes of active use, asks ReviewSession for the next card and
 *     hands it to NotificationOrchestrator.
 *   - Escalates if the user ignores cards (notification → lockscreen → overlay).
 *
 * v0 implementation: ships the foreground notification + a placeholder ticker.
 * Real pacing logic lands in M5.
 */
@AndroidEntryPoint
class PacingService : LifecycleService() {

    @Inject lateinit var engine: PacingEngine

    private var tickerJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val n: Notification = NotificationCompat
            .Builder(this, FlashcardsApp.CHANNEL_PACING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Pacing your reviews")
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

        if (tickerJob == null) {
            tickerJob = lifecycleScope.launch {
                while (true) {
                    runCatching { engine.tick() }
                    delay(TICK_INTERVAL_MS)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        tickerJob = null
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_ID = 2001
        private const val TICK_INTERVAL_MS = 60_000L  // 1 min
    }
}
