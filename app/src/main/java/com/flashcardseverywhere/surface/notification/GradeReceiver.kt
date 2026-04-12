/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.surface.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flashcardseverywhere.data.anki.AnkiBridge
import com.flashcardseverywhere.data.anki.Ease
import com.flashcardseverywhere.data.prefs.SettingsRepository
import com.flashcardseverywhere.surface.widget.WidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles grade-button presses from a heads-up notification, Glance widget,
 * or overlay. Submits the answer to AnkiDroid, resets escalation state,
 * and dismisses the notification.
 */
@AndroidEntryPoint
class GradeReceiver : BroadcastReceiver() {

    @Inject lateinit var bridge: AnkiBridge
    @Inject lateinit var widgetUpdater: WidgetUpdater
    @Inject lateinit var settings: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GRADE) return
        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        val cardOrd = intent.getIntExtra(EXTRA_CARD_ORD, -1)
        val easeInt = intent.getIntExtra(EXTRA_EASE, -1)
        if (noteId < 0 || cardOrd < 0 || easeInt !in 1..4) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ease = Ease.values().first { it.value == easeInt }
                bridge.answer(noteId, cardOrd, ease, timeTakenMs = 0L)
                settings.markCardGradedNow() // reset escalation FSM
                widgetUpdater.updateAll()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_GRADE = "com.flashcardseverywhere.action.GRADE"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_CARD_ORD = "card_ord"
        const val EXTRA_EASE = "ease"

        /** Fire a grade intent programmatically (used by overlay and blocker). */
        fun sendGrade(ctx: Context, noteId: Long, cardOrd: Int, ease: Ease) {
            val i = Intent(ctx, GradeReceiver::class.java).apply {
                action = ACTION_GRADE
                putExtra(EXTRA_NOTE_ID, noteId)
                putExtra(EXTRA_CARD_ORD, cardOrd)
                putExtra(EXTRA_EASE, ease.value)
            }
            ctx.sendBroadcast(i)
        }
    }
}
