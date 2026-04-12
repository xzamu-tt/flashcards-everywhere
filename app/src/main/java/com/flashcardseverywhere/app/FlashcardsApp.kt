/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.getSystemService
import com.flashcardseverywhere.R
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FlashcardsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannels()
    }

    private fun registerNotificationChannels() {
        val nm = getSystemService<NotificationManager>() ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PERSISTENT,
                getString(R.string.notif_channel_persistent),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DUE,
                getString(R.string.notif_channel_due),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { setShowBadge(true) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LOCKSCREEN,
                getString(R.string.notif_channel_lockscreen),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PACING,
                getString(R.string.notif_channel_pacing),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BLOCKER,
                "App blocker",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BUBBLE,
                "Flashcard bubbles",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setAllowBubbles(true)
                setShowBadge(true)
            }
        )
    }

    companion object {
        const val CHANNEL_PERSISTENT = "due_persistent"
        const val CHANNEL_DUE = "due_heads_up"
        const val CHANNEL_LOCKSCREEN = "lockscreen_fullscreen"
        const val CHANNEL_PACING = "pacing_foreground"
        const val CHANNEL_BLOCKER = "app_blocker"
        const val CHANNEL_BUBBLE = "flashcard_bubble"
    }
}
