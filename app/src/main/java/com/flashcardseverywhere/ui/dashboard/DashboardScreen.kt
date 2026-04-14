/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * The Shame Dashboard. Brutal, honest daily analytics.
 * Shows screen time vs study time with zero sugarcoating.
 */
package com.flashcardseverywhere.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DashboardScreen(
    vm: DashboardViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Text(
                    text = "Today",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            // Verdict — the brutal one-liner
            item {
                Text(
                    text = state.verdict,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.ratioPercent < 10) Color(0xFFCC3333)
                            else if (state.ratioPercent < 25) Color(0xFFCC9933)
                            else Color(0xFF339933),
                    fontWeight = FontWeight.Medium,
                )
            }

            // Big numbers: screen time vs study time
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BigStatCard(
                        label = "Screen time",
                        value = formatMinutes(state.screenTimeMinutes),
                        color = Color(0xFFCC3333),
                        modifier = Modifier.weight(1f),
                    )
                    BigStatCard(
                        label = "Study time",
                        value = formatMinutes(state.studyTimeMinutes),
                        color = Color(0xFF339933),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Study ratio bar
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Study ratio",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${state.ratioPercent}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    RatioBar(ratio = state.ratioPercent)
                }
            }

            // Stats grid
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatRow(label = "Cards reviewed", value = "${state.cardsReviewed}")
                    StatRow(label = "Budget lockouts", value = "${state.lockoutsToday}")
                    StatRow(label = "Doom-scroll interrupts", value = "${state.doomScrollInterrupts}")
                    StatRow(
                        label = "Budget remaining",
                        value = if (state.budgetLocked) "LOCKED" else "${state.budgetRemainingMin}min",
                    )
                    StatRow(label = "Budget earned total", value = "${state.budgetEarnedMin}min")
                }
            }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

@Composable
private fun BigStatCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RatioBar(ratio: Int) {
    val fraction = (ratio / 100f).coerceIn(0f, 1f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (fraction > 0f) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(12.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        ratio < 10 -> Color(0xFFCC3333)
                        ratio < 25 -> Color(0xFFCC9933)
                        else -> Color(0xFF339933)
                    },
                ) {}
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

private fun formatMinutes(min: Int): String {
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
