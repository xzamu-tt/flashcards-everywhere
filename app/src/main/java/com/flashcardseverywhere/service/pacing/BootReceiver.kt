/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.service.pacing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.flashcardseverywhere.service.blocking.AppBlockerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Restart pacing service.
        ContextCompat.startForegroundService(
            context, Intent(context, PacingService::class.java)
        )
        // Restart app blocker if it was enabled (it reads its own pref on tick).
        AppBlockerService.start(context)
    }
}
