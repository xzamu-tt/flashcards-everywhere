/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Posts a floating bubble notification (API 30+) that shows a flashcard.
 * The bubble persists across app switches and floats on top of everything.
 *
 * The bubble hosts BubbleActivity which reuses the Compose ReviewerScreen.
 * setAutoExpandBubble(true) expands the bubble on first show.
 */
package com.flashcardseverywhere.surface.bubble

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.flashcardseverywhere.app.FlashcardsApp
import com.flashcardseverywhere.data.anki.DueCard
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BubbleOrchestrator @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val nm: NotificationManager? get() = ctx.getSystemService()
    private val sm: ShortcutManager? get() = ctx.getSystemService()

    fun postBubble(card: DueCard) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return // Bubbles require API 30+

        ensureShortcut()
        postBubbleNotification(card)
    }

    fun dismiss() {
        nm?.cancel(NOTIF_BUBBLE)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ensureShortcut() {
        val sm = sm ?: return
        val existing = sm.dynamicShortcuts.any { it.id == SHORTCUT_ID }
        if (existing) return

        val person = Person.Builder()
            .setName("Flashcard")
            .setBot(true)
            .build()

        val shortcut = ShortcutInfo.Builder(ctx, SHORTCUT_ID)
            .setLongLived(true)
            .setShortLabel("Study")
            .setLongLabel("Flashcards Everywhere")
            .setIcon(Icon.createWithResource(ctx, android.R.drawable.ic_dialog_info))
            .setIntent(
                Intent(ctx, BubbleActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                }
            )
            .setPerson(person)
            .build()

        sm.pushDynamicShortcut(shortcut)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun postBubbleNotification(card: DueCard) {
        val bubbleIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, BubbleActivity::class.java).apply {
                action = Intent.ACTION_VIEW
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val bubbleMetadata = Notification.BubbleMetadata.Builder(
            bubbleIntent,
            Icon.createWithResource(ctx, android.R.drawable.ic_dialog_info),
        )
            .setDesiredHeight(600)
            .setAutoExpandBubble(true)
            .setSuppressNotification(false)
            .build()

        val person = Person.Builder()
            .setName("Flashcard")
            .setBot(true)
            .setIcon(Icon.createWithResource(ctx, android.R.drawable.ic_dialog_info))
            .build()

        val style = Notification.MessagingStyle(person)
            .addMessage(
                stripHtml(card.frontHtml),
                System.currentTimeMillis(),
                person,
            )

        val notification = Notification.Builder(ctx, FlashcardsApp.CHANNEL_BUBBLE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style)
            .setShortcutId(SHORTCUT_ID)
            .setBubbleMetadata(bubbleMetadata)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

        nm?.notify(NOTIF_BUBBLE, notification)
    }

    private fun stripHtml(html: String): String =
        androidx.core.text.HtmlCompat
            .fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
            .toString()
            .trim()

    companion object {
        private const val NOTIF_BUBBLE = 1004
        private const val SHORTCUT_ID = "flashcard_bubble"
    }
}
