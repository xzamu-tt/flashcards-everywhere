/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Apple-minimal reviewer surface.
 *
 * Design rules (locked in plan file):
 *   - Two surfaces: ink (dark) or paper (light), nothing in between.
 *   - One accent, used only on the streak ring and current focus.
 *   - Hairline divider, never shadows.
 *   - 150ms ease-out, no springs, no bouncy material motion.
 *   - The card *is* the screen — chrome is invisible.
 */
package com.flashcardseverywhere.ui.reviewer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.flashcardseverywhere.R
import com.flashcardseverywhere.data.anki.DueCard
import com.flashcardseverywhere.data.anki.Ease
import com.flashcardseverywhere.data.anki.FlashCardsContract
import com.flashcardseverywhere.data.anki.ReviewState
import com.flashcardseverywhere.ui.reviewer.CardHtmlRenderer
import com.flashcardseverywhere.ui.theme.FlashcardsTheme

@Composable
fun ReviewerScreen(
    state: ReviewerUiState,
    onGrade: (Ease) -> Unit,
    onReveal: () -> Unit,
    onRefresh: () -> Unit,
    onSkip: () -> Unit = {},
    onPickDeck: () -> Unit = {},
) {
    // Re-check permission / installed-state when the user returns from
    // system settings or from installing AnkiDroid.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefresh() }

    // Modern Activity Result API for the AnkiDroid custom dangerous
    // permission. The legacy ActivityCompat.requestPermissions path was
    // silently dropping the result because MainActivity never overrode
    // onRequestPermissionsResult.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Whatever the user picked, re-evaluate the session state. If they
        // granted, we'll transition to Card; if they denied, we stay on
        // PermissionDenied (with the button still actionable).
        onRefresh()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            AnimatedContent(
                targetState = state.review,
                transitionSpec = {
                    fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                },
                label = "review-state",
            ) { current ->
                when (current) {
                    is ReviewState.Loading -> EmptyMessage("")
                    is ReviewState.AnkiDroidNotInstalled -> AnkiNotInstalled()
                    is ReviewState.PermissionDenied -> PermissionDenied(
                        onGrant = {
                            permissionLauncher.launch(FlashCardsContract.READ_WRITE_PERMISSION)
                        },
                    )
                    is ReviewState.NoDeckSelected -> NoDeckSelected(onPickDeck)
                    is ReviewState.NoDueCards -> NoDueCards(onRefresh)
                    is ReviewState.AnswerStuck -> AnswerStuck(onSkip = onSkip, onRetry = onRefresh)
                    is ReviewState.Error -> EmptyMessage(current.message)
                    is ReviewState.Card -> CardSurface(
                        card = current.card,
                        cardsLeft = current.cardsLeft,
                        revealed = state.revealed,
                        onReveal = onReveal,
                        onGrade = onGrade,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardSurface(
    card: DueCard,
    cardsLeft: Int,
    revealed: Boolean,
    onReveal: () -> Unit,
    onGrade: (Ease) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top row: count only. No app name. No icons.
        Text(
            text = "$cardsLeft",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(48.dp))

        // Before reveal: show QUESTION (front only).
        // After reveal: show ANSWER (front + <hr id=answer> + back, matching AnkiDroid).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val html = if (revealed) card.backHtml else card.frontHtml
            CardText(html = html, mediaFiles = card.mediaFiles)
        }

        Spacer(Modifier.height(32.dp))

        if (!revealed) {
            RevealRow(onReveal)
        } else {
            GradeRow(card = card, onGrade = onGrade)
        }
    }
}

@Composable
private fun CardText(html: String, mediaFiles: List<String> = emptyList()) {
    // Cards may contain rich HTML, embedded styles, and `<img>` tags. We render
    // them through a transparent WebView (see CardWebView.kt). For very short,
    // tag-free cards we still want the Compose-native typography, so we sniff
    // the HTML and pick the cheapest renderer.
    val looksRich = remember(html) { html.contains('<') }
    if (looksRich) {
        CardWebView(
            html = html,
            mediaFiles = mediaFiles,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
    } else {
        Text(
            text = CardHtmlRenderer.stripHtml(html),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RevealRow(onReveal: () -> Unit) {
    QuietButton(
        label = stringRes(R.string.reveal_answer),
        onClick = onReveal,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GradeRow(card: DueCard, onGrade: (Ease) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        QuietButton(
            label = stringRes(R.string.grade_again),
            onClick = { onGrade(Ease.AGAIN) },
            modifier = Modifier.weight(1f),
        )
        if (card.canShowHard) {
            QuietButton(
                label = stringRes(R.string.grade_hard),
                onClick = { onGrade(Ease.HARD) },
                modifier = Modifier.weight(1f),
            )
        }
        QuietButton(
            label = stringRes(R.string.grade_good),
            onClick = { onGrade(Ease.GOOD) },
            modifier = Modifier.weight(1f),
        )
        if (card.canShowEasy) {
            QuietButton(
                label = stringRes(R.string.grade_easy),
                onClick = { onGrade(Ease.EASY) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Apple-minimal button: 1px hairline, no shadow, no fill. Press state is
 * a 4% surface tint, not a ripple.
 */
@Composable
private fun QuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoDueCards(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringRes(R.string.no_due_cards),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        QuietButton(label = "Refresh", onClick = onRefresh)
    }
}

@Composable
private fun PermissionDenied(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringRes(R.string.anki_permission_denied),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(24.dp))
        QuietButton(
            label = "Grant access",
            onClick = onGrant,
        )
    }
}

@Composable
private fun NoDeckSelected(onPickDeck: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pick a deck",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Choose which AnkiDroid deck to review from. You can change it any time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
        QuietButton(label = "Open Settings", onClick = onPickDeck)
    }
}

@Composable
private fun AnswerStuck(onSkip: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "AnkiDroid didn't accept the answer",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The same card came back. Skip it for today, or retry the sync.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton(label = "Retry", onClick = onRetry, modifier = Modifier.weight(1f))
            QuietButton(label = "Skip card", onClick = onSkip, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AnkiNotInstalled() {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringRes(R.string.anki_not_installed),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(24.dp))
        QuietButton(
            label = stringRes(R.string.anki_install_prompt),
            onClick = {
                val i = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/packages/com.ichi2.anki/"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                runCatching { ctx.startActivity(i) }
            },
        )
    }
}

@Composable
private fun stringRes(id: Int): String =
    androidx.compose.ui.res.stringResource(id)

@Preview
@Composable
private fun PreviewCard() {
    FlashcardsTheme {
        ReviewerScreen(
            state = ReviewerUiState(
                review = ReviewState.Card(
                    card = DueCard(
                        noteId = 1L,
                        cardOrd = 0,
                        buttonCount = 4,
                        nextReviewTimes = listOf("<10m", "10m", "1d", "4d"),
                        mediaFiles = emptyList(),
                        frontHtml = "qué significa<br>'merengue'?",
                        backHtml = "a sweet dessert made of whipped egg whites and sugar",
                    ),
                    cardsLeft = 47,
                ),
                revealed = false,
            ),
            onGrade = {},
            onReveal = {},
            onRefresh = {},
        )
    }
}
