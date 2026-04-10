/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.surface.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.flashcardseverywhere.data.anki.DueCard
import com.flashcardseverywhere.data.anki.Ease
import com.flashcardseverywhere.data.anki.ReviewState
import com.flashcardseverywhere.domain.ReviewSession
import com.flashcardseverywhere.surface.notification.GradeReceiver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Glance widget that mirrors the current card from [ReviewSession]. Looks the
 * session up via Hilt's [EntryPointAccessors] since `GlanceAppWidget` isn't
 * itself an `@AndroidEntryPoint`.
 *
 * Reference: docs/ANDROID_SURFACES_REFERENCE.md §1.
 */
class CardWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun reviewSession(): ReviewSession
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext, WidgetEntryPoint::class.java
        )
        val session = ep.reviewSession()

        provideContent {
            GlanceTheme {
                val state by session.state.collectAsState(initial = ReviewState.Loading)
                Body(context, state)
            }
        }
    }

    @Composable
    private fun Body(ctx: Context, state: ReviewState) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is ReviewState.Card -> CardBody(ctx, state.card, state.cardsLeft)
                is ReviewState.NoDueCards -> EmptyBody("No cards due")
                is ReviewState.NoDeckSelected -> EmptyBody("Pick a deck in app")
                is ReviewState.AnswerStuck -> EmptyBody("Open app to fix")
                is ReviewState.PermissionDenied -> EmptyBody("Tap app to grant access")
                is ReviewState.AnkiDroidNotInstalled -> EmptyBody("Install AnkiDroid")
                is ReviewState.Error -> EmptyBody(state.message)
                is ReviewState.Loading -> EmptyBody("…")
            }
        }
    }

    @Composable
    private fun CardBody(ctx: Context, card: DueCard, cardsLeft: Int) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$cardsLeft",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(GlanceModifier.height(8.dp))
            Box(
                modifier = GlanceModifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = card.frontHtml.htmlToText(),
                    maxLines = 4,
                    style = TextStyle(
                        color = GlanceTheme.colors.onBackground,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Spacer(GlanceModifier.height(12.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                GradeChip(ctx, "Again", card, Ease.AGAIN)
                Spacer(GlanceModifier.width(6.dp))
                if (card.canShowHard) {
                    GradeChip(ctx, "Hard", card, Ease.HARD)
                    Spacer(GlanceModifier.width(6.dp))
                }
                GradeChip(ctx, "Good", card, Ease.GOOD)
                if (card.canShowEasy) {
                    Spacer(GlanceModifier.width(6.dp))
                    GradeChip(ctx, "Easy", card, Ease.EASY)
                }
            }
        }
    }

    @Composable
    private fun GradeChip(ctx: Context, label: String, card: DueCard, ease: Ease) {
        val intent = Intent(GradeReceiver.ACTION_GRADE).apply {
            component = ComponentName(ctx, GradeReceiver::class.java)
            putExtra(GradeReceiver.EXTRA_NOTE_ID, card.noteId)
            putExtra(GradeReceiver.EXTRA_CARD_ORD, card.cardOrd)
            putExtra(GradeReceiver.EXTRA_EASE, ease.value)
        }
        Text(
            text = label,
            modifier = GlanceModifier.clickable(actionSendBroadcast(intent)),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Medium,
            ),
        )
    }

    @Composable
    private fun EmptyBody(message: String) {
        Text(
            text = message,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

class CardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CardWidget()
}

private fun String.htmlToText(): String =
    androidx.core.text.HtmlCompat
        .fromHtml(this, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .trim()
