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
import com.flashcardseverywhere.service.enforcement.EnforcementService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Restart pacing service.
        ContextCompat.startForegroundService(
            context, Intent(context, PacingService::class.java)
        )
        // Restart app blocker (legacy, for users who haven't migrated).
        AppBlockerService.start(context)
        // Restart unified enforcement service (budget + doom-scroll + grayscale).
        EnforcementService.start(context)
    }
}
