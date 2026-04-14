/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.service.pacing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.flashcardseverywhere.service.blocking.AppBlockerService
import com.flashcardseverywhere.service.enforcement.EnforcementService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Restart pacing service.
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, PacingService::class.java)
            )
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start PacingService", e)
        }
        // Restart app blocker (legacy).
        try {
            AppBlockerService.start(context)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start AppBlockerService", e)
        }
        // Restart enforcement service — wrapped in try-catch because
        // Android 12+ may deny foreground service starts from boot receiver
        // if the exemption window has expired.
        try {
            EnforcementService.start(context)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start EnforcementService", e)
        }
    }
}
