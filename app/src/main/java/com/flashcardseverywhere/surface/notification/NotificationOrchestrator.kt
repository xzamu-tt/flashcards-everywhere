/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.surface.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.flashcardseverywhere.R
import com.flashcardseverywhere.app.FlashcardsApp
import com.flashcardseverywhere.data.anki.DueCard
import com.flashcardseverywhere.data.anki.Ease
import com.flashcardseverywhere.surface.lockscreen.LockscreenReviewerActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts notifications for every notification surface:
 *   - persistent low-priority "you have N due"
 *   - high-priority heads-up card with grade actions
 *   - lockscreen full-screen-intent card
 */
@Singleton
class NotificationOrchestrator @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val nm: NotificationManager? get() = ctx.getSystemService()

    fun postPersistent(dueCount: Int, streak: Int, goalDone: Int, goalTotal: Int) {
        val n = NotificationCompat.Builder(ctx, FlashcardsApp.CHANNEL_PERSISTENT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(ctx.getString(R.string.notif_persistent_title, dueCount))
            .setContentText(
                ctx.getString(R.string.notif_persistent_body, streak, goalDone, goalTotal)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm?.notify(NOTIF_PERSISTENT, n)
    }

    /** Heads-up "here is a card, grade it" without opening the app. */
    fun postDueCard(card: DueCard, vibrate: Boolean = true) {
        val builder = NotificationCompat.Builder(ctx, FlashcardsApp.CHANNEL_DUE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(stripHtml(card.frontHtml))
            .setContentText("Tap to reveal answer")
            .setStyle(NotificationCompat.BigTextStyle().bigText(stripHtml(card.frontHtml)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setVibrate(if (vibrate) longArrayOf(0, 200, 100, 200) else longArrayOf(0))
            .addAction(0, ctx.getString(R.string.grade_again), gradeIntent(card, Ease.AGAIN))
            .addAction(0, ctx.getString(R.string.grade_good), gradeIntent(card, Ease.GOOD))

        if (card.canShowHard)
            builder.addAction(0, ctx.getString(R.string.grade_hard), gradeIntent(card, Ease.HARD))
        if (card.canShowEasy)
            builder.addAction(0, ctx.getString(R.string.grade_easy), gradeIntent(card, Ease.EASY))

        nm?.notify(NOTIF_DUE_CARD, builder.build())
    }

    /**
     * Fire the lockscreen full-screen-intent card. Falls back to a heads-up
     * notification if the user has not granted USE_FULL_SCREEN_INTENT (the
     * permission has been restricted since Android 14 / API 34, enforcement
     * live since 2025-01-22).
     *
     * Reference: docs/ANDROID_SURFACES_REFERENCE.md §2.
     */
    fun postLockscreenCard(card: DueCard? = null) {
        val canFsi = canUseFullScreenIntent()
        val fullscreen = Intent(ctx, LockscreenReviewerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, fullscreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, FlashcardsApp.CHANNEL_LOCKSCREEN)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(card?.let { stripHtml(it.frontHtml) } ?: "Time for a card")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setAutoCancel(true)

        if (canFsi) {
            builder.setFullScreenIntent(pi, true)
        }
        // If we don't have FSI permission, the same builder degrades to a
        // heads-up notification — still useful, just not full-screen.

        nm?.notify(NOTIF_LOCKSCREEN, builder.build())
    }

    /** True iff we can post full-screen-intent notifications on this device. */
    fun canUseFullScreenIntent(): Boolean {
        // canUseFullScreenIntent() exists since API 34. On older devices the
        // permission is normal-protection and always granted.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        return nm?.canUseFullScreenIntent() == true
    }

    private fun gradeIntent(card: DueCard, ease: Ease): PendingIntent {
        val i = Intent(ctx, GradeReceiver::class.java).apply {
            action = GradeReceiver.ACTION_GRADE
            putExtra(GradeReceiver.EXTRA_NOTE_ID, card.noteId)
            putExtra(GradeReceiver.EXTRA_CARD_ORD, card.cardOrd)
            putExtra(GradeReceiver.EXTRA_EASE, ease.value)
        }
        return PendingIntent.getBroadcast(
            ctx,
            ((card.noteId * 31) + ease.value).toInt(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stripHtml(html: String): String =
        androidx.core.text.HtmlCompat
            .fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
            .toString()
            .trim()

    companion object {
        private const val NOTIF_PERSISTENT = 1001
        private const val NOTIF_DUE_CARD = 1002
        private const val NOTIF_LOCKSCREEN = 1003
    }
}
