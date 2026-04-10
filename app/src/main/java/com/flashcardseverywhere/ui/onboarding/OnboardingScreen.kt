/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui.onboarding

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flashcardseverywhere.data.anki.AnkiBridge
import com.flashcardseverywhere.data.anki.AnkiPermissionHelper
import com.flashcardseverywhere.util.PermissionChecks

/**
 * Per-permission walkthrough. Each row is honest about what the permission
 * does and why we need it. Nothing is granted automatically; every step needs
 * an explicit user tap.
 */
@Composable
fun OnboardingScreen(
    bridge: AnkiBridge,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }

    val steps = remember(refreshTick) {
        buildList {
            add(
                Step(
                    title = "AnkiDroid installed",
                    body = "We read your due cards from AnkiDroid and submit reviews back. AnkiDroid stays in charge.",
                    granted = bridge.isAnkiDroidInstalled(),
                    cta = "Install AnkiDroid",
                    action = {
                        val i = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://f-droid.org/packages/com.ichi2.anki/"),
                        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                        runCatching { ctx.startActivity(i) }
                    }
                )
            )
            add(
                Step(
                    title = "Read your AnkiDroid collection",
                    body = "Lets the app pull due cards and write your answers back. Stays on-device.",
                    granted = bridge.hasPermission(),
                    cta = "Grant",
                    action = { (ctx as? Activity)?.let { AnkiPermissionHelper.request(it) } }
                )
            )
            add(
                Step(
                    title = "Notifications",
                    body = "Cards arrive as notifications you can grade in-place.",
                    granted = PermissionChecks.hasNotificationPermission(ctx),
                    cta = "Open settings",
                    action = { PermissionChecks.openAppNotificationSettings(ctx) }
                )
            )
            add(
                Step(
                    title = "Lockscreen cards",
                    body = "Lets a card appear above the lockscreen so you can review without unlocking.",
                    granted = PermissionChecks.canUseFullScreenIntent(ctx),
                    cta = "Open settings",
                    action = { PermissionChecks.openFullScreenIntentSettings(ctx) }
                )
            )
            add(
                Step(
                    title = "Usage access",
                    body = "Counts your screen-on time so cards arrive every 10 minutes of active use.",
                    granted = PermissionChecks.hasUsageAccess(ctx),
                    cta = "Open settings",
                    action = { PermissionChecks.openUsageAccessSettings(ctx) }
                )
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                "Set up surfaces",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(steps) { step ->
                    StepRow(step = step, onTap = {
                        step.action()
                        refreshTick++
                    })
                }
            }
            Spacer(Modifier.height(12.dp))
            DoneButton(onClick = onDone)
        }
    }
}

private data class Step(
    val title: String,
    val body: String,
    val granted: Boolean,
    val cta: String,
    val action: () -> Unit,
)

@Composable
private fun StepRow(step: Step, onTap: () -> Unit) {
    Surface(
        onClick = if (step.granted) ({}) else onTap,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (step.granted) "Granted" else step.cta,
                style = MaterialTheme.typography.labelLarge,
                color = if (step.granted)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Continue", style = MaterialTheme.typography.labelLarge)
        }
    }
}
