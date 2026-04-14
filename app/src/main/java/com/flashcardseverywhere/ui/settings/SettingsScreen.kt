/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Settings surface. Same Apple-minimal language as the reviewer:
 *   - Hairline dividers, never elevation.
 *   - Inputs are explicit text/steppers, never sliders with springs.
 *   - One section per concern, separated by 32dp.
 */
package com.flashcardseverywhere.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardseverywhere.data.anki.DeckRow
import com.flashcardseverywhere.data.anki.DeckTreeNode
import com.flashcardseverywhere.data.anki.FlatDeckItem
import com.flashcardseverywhere.data.anki.allNodeIds
import com.flashcardseverywhere.data.anki.buildDeckTree
import com.flashcardseverywhere.data.anki.flattenDeckTree
import com.flashcardseverywhere.data.anki.searchMatchingIds
import com.flashcardseverywhere.data.prefs.SettingsRepository
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    onRunOnboardingAgain: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            // ─── Active deck ───────────────────────────────────────────
            item {
                SectionHeader("Active deck")
                DeckSection(
                    decks = state.decks,
                    selectedDeckId = state.selectedDeckId,
                    ankiInstalled = state.ankiInstalled,
                    ankiPermissionGranted = state.ankiPermissionGranted,
                    deckLoadError = state.deckLoadError,
                    onSelect = vm::selectDeck,
                    onReload = vm::loadDecks,
                )
            }

            // ─── Daily goal ────────────────────────────────────────────
            item {
                SectionHeader("Daily goal")
                Stepper(
                    value = state.dailyGoal,
                    suffix = "cards",
                    step = 25,
                    range = 25..1000,
                    onChange = vm::setDailyGoal,
                )
            }

            // ─── Pacing interval ───────────────────────────────────────
            item {
                SectionHeader("Pacing interval")
                BodyText("How often a new card is surfaced while you're using your phone.")
                Spacer(Modifier.height(12.dp))
                Stepper(
                    value = state.pacingIntervalMin,
                    suffix = "minutes",
                    step = 1,
                    range = 1..120,
                    onChange = vm::setPacingInterval,
                )
            }

            // ─── Quiet hours ───────────────────────────────────────────
            item {
                SectionHeader("Quiet hours")
                BodyText("No cards arrive between these hours (unless aggressive mode is on).")
                Spacer(Modifier.height(12.dp))
                QuietHoursRow(
                    start = state.quietHoursStart,
                    end = state.quietHoursEnd,
                    onChange = vm::setQuietHours,
                )
            }

            // ─── Interruption surfaces ─────────────────────────────────
            item { HairlineDivider() }
            item {
                SectionHeader("Interruption surfaces")
                BodyText("Choose how flashcards interrupt you. Enable all for maximum interruption.")
                Spacer(Modifier.height(16.dp))

                ToggleRow(
                    label = "Notifications",
                    description = "Heads-up notification with grade buttons",
                    checked = state.notificationEnabled,
                    onToggle = vm::setNotificationEnabled,
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    label = "Lockscreen",
                    description = "Full-screen card on locked device",
                    checked = state.lockscreenEnabled,
                    onToggle = vm::setLockscreenEnabled,
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    label = "Overlay",
                    description = "Full-screen overlay on top of any app. Must grade to dismiss.",
                    checked = state.overlayEnabled,
                    onToggle = { on ->
                        if (on && !state.overlayPermissionGranted) {
                            vm.openOverlaySettings()
                        } else {
                            vm.setOverlayEnabled(on)
                        }
                    },
                )
                if (state.overlayEnabled && !state.overlayPermissionGranted) {
                    Spacer(Modifier.height(8.dp))
                    WarningText("Overlay permission not granted. Tap the toggle to open settings.")
                }
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    label = "Floating bubble",
                    description = "Floating flashcard bubble on top of every app (Android 11+)",
                    checked = state.bubbleEnabled,
                    onToggle = vm::setBubbleEnabled,
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    label = "Media session",
                    description = "Giant lockscreen media card with grade buttons as media controls",
                    checked = state.mediaSessionEnabled,
                    onToggle = vm::setMediaSessionEnabled,
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    label = "Screensaver (Dream)",
                    description = "Interactive flashcards when charging and idle. Set in Display settings.",
                    checked = state.dreamEnabled,
                    onToggle = vm::setDreamEnabled,
                )
            }

            // ─── Escalation ────────────────────────────────────────────
            item {
                SectionHeader("Escalation")
                BodyText("If you ignore a card, escalate to a more aggressive surface.")
                Spacer(Modifier.height(16.dp))

                ToggleRow(
                    label = "Auto-escalate",
                    description = "Notification → Lockscreen → Overlay when card is ignored",
                    checked = state.escalationEnabled,
                    onToggle = vm::setEscalationEnabled,
                )
                if (state.escalationEnabled) {
                    Spacer(Modifier.height(12.dp))
                    BodyText("Escalate after ignoring for:")
                    Spacer(Modifier.height(8.dp))
                    Stepper(
                        value = state.escalationTimeoutSec,
                        suffix = "seconds",
                        step = 30,
                        range = 30..600,
                        onChange = vm::setEscalationTimeout,
                    )
                }
            }

            // ─── App blocking ──────────────────────────────────────────
            item { HairlineDivider() }
            item {
                SectionHeader("App blocking")
                BodyText("Block apps until you review a flashcard. Requires overlay permission.")
                Spacer(Modifier.height(16.dp))

                ToggleRow(
                    label = "Enable app blocking",
                    description = "Shows a blocker overlay when you open blocked apps",
                    checked = state.appBlockingEnabled,
                    onToggle = { on ->
                        if (on && !state.overlayPermissionGranted) {
                            vm.openOverlaySettings()
                        } else {
                            vm.setAppBlockingEnabled(on)
                        }
                    },
                )
                if (state.appBlockingEnabled) {
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        label = "Block ALL apps",
                        description = "Nuclear option — every app requires a card to unlock",
                        checked = state.blockAllApps,
                        onToggle = vm::setBlockAllApps,
                    )
                    Spacer(Modifier.height(12.dp))
                    BodyText("Unlock duration after grading:")
                    Spacer(Modifier.height(8.dp))
                    Stepper(
                        value = state.blockUnlockDurationMin,
                        suffix = "minutes",
                        step = 1,
                        range = 1..60,
                        onChange = vm::setBlockUnlockDuration,
                    )
                    Spacer(Modifier.height(12.dp))
                    BodyText("Cards required before unlocking:")
                    Spacer(Modifier.height(8.dp))
                    Stepper(
                        value = state.cardsToUnlock,
                        suffix = "cards",
                        step = 1,
                        range = 1..20,
                        onChange = vm::setCardsToUnlock,
                    )
                    if (!state.blockAllApps) {
                        Spacer(Modifier.height(16.dp))
                        BodyText("Blocked apps: ${state.blockedPackages.size}")
                        if (state.blockedPackages.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                state.blockedPackages.sorted().forEach { pkg ->
                                    PackageRow(
                                        packageName = pkg,
                                        onRemove = { vm.removeBlockedPackage(pkg) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        QuietButton(
                            label = "Add all popular distraction apps",
                            onClick = vm::addAllPopularApps,
                        )
                        Spacer(Modifier.height(8.dp))
                        AddPackageButton(onAdd = vm::addBlockedPackage)
                    }
                }
            }

            // ─── Aggressive mode ───────────────────────────────────────
            item { HairlineDivider() }
            item {
                SectionHeader("Aggressive mode")
                BodyText("Maximum interruption. Ignores quiet hours. Cards fire relentlessly.")
                Spacer(Modifier.height(16.dp))

                ToggleRow(
                    label = "Aggressive mode",
                    description = "Bypass quiet hours, always interrupt",
                    checked = state.aggressiveMode,
                    onToggle = vm::setAggressiveMode,
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    label = "Vibrate on card",
                    description = "Vibrate when a card is surfaced",
                    checked = state.vibrateOnCard,
                    onToggle = vm::setVibrateOnCard,
                )
            }

            // ─── AnkiDroid sync ────────────────────────────────────────
            item { HairlineDivider() }
            item {
                SectionHeader("AnkiDroid")
                BodyText("Last synced: ${formatLastSync(state.lastSyncAt)}")
                Spacer(Modifier.height(12.dp))
                QuietButton(
                    label = "Sync to AnkiWeb now",
                    onClick = { vm.syncNow() },
                    enabled = state.ankiInstalled,
                )
            }

            // ─── Setup ────────────────────────────────────────────────
            item {
                SectionHeader("Setup")
                QuietButton(
                    label = "Run onboarding again",
                    onClick = onRunOnboardingAgain,
                )
            }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  Section components
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WarningText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun HairlineDivider() {
    Surface(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp),
    ) {}
}

// ─────────────────────────────────────────────────────────────────────────
//  Toggle row
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  Deck section
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun DeckSection(
    decks: List<DeckRow>?,
    selectedDeckId: Long,
    ankiInstalled: Boolean,
    ankiPermissionGranted: Boolean,
    deckLoadError: String?,
    onSelect: (Long) -> Unit,
    onReload: () -> Unit,
) {
    when {
        !ankiInstalled -> BodyText("Install AnkiDroid to choose a deck.")
        !ankiPermissionGranted -> BodyText("Grant access from the review tab to choose a deck.")
        decks == null -> BodyText("Loading decks…")
        deckLoadError != null -> Column {
            BodyText("Couldn't load decks: $deckLoadError")
            Spacer(Modifier.height(8.dp))
            QuietButton(label = "Retry", onClick = onReload)
        }
        decks.isEmpty() -> BodyText("AnkiDroid returned no decks.")
        else -> DeckTreePicker(
            decks = decks,
            selectedDeckId = selectedDeckId,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun DeckTreePicker(
    decks: List<DeckRow>,
    selectedDeckId: Long,
    onSelect: (Long) -> Unit,
) {
    val tree = remember(decks) { buildDeckTree(decks) }
    // Start with all nodes expanded so user sees the full hierarchy.
    var expandedIds by remember(tree) { mutableStateOf(allNodeIds(tree)) }
    var searchQuery by remember { mutableStateOf("") }

    // When searching, auto-expand matching branches and dim non-matches.
    val (matchIds, searchExpandedIds) = remember(tree, searchQuery) {
        if (searchQuery.isBlank()) emptySet<Long>() to emptySet()
        else searchMatchingIds(tree, searchQuery)
    }
    val effectiveExpanded = if (searchQuery.isBlank()) expandedIds else searchExpandedIds

    val flatItems = remember(tree, effectiveExpanded) {
        flattenDeckTree(tree, effectiveExpanded)
    }

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (selectedDeckId == SettingsRepository.NO_DECK) {
            BodyText("Pick the deck you want to review.")
            Spacer(Modifier.height(12.dp))
        }

        // Search field (show when there are enough decks to warrant it).
        if (decks.size > 8) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search decks") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }

        // Collapse / expand all toggle.
        Row {
            Surface(
                onClick = {
                    expandedIds = if (expandedIds.isEmpty()) allNodeIds(tree) else emptySet()
                    searchQuery = ""
                },
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    text = if (expandedIds.isEmpty() && searchQuery.isBlank()) "Expand all" else "Collapse all",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        flatItems.forEach { item ->
            val dimmed = searchQuery.isNotBlank() && item.node.id !in matchIds
            DeckTreeRow(
                item = item,
                selected = item.node.deck?.id == selectedDeckId,
                dimmed = dimmed,
                onToggle = { id ->
                    expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
                    searchQuery = ""   // clear search when manually toggling
                },
                onSelect = { item.node.deck?.id?.let(onSelect) },
            )
        }
    }
}

@Composable
private fun DeckTreeRow(
    item: FlatDeckItem,
    selected: Boolean,
    dimmed: Boolean,
    onToggle: (Long) -> Unit,
    onSelect: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (item.isExpanded) 90f else 0f,
        label = "chevron",
    )
    val indentDp = (item.depth * 20).dp
    val alpha = if (dimmed) 0.35f else 1f

    Surface(
        onClick = onSelect,
        enabled = item.node.deck != null,   // synthetic parents are not selectable
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = indentDp, end = 4.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Expand / collapse chevron
            if (item.hasChildren) {
                Surface(
                    onClick = { onToggle(item.node.id) },
                    color = Color.Transparent,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (item.isExpanded) "Collapse" else "Expand",
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(chevronRotation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        )
                    }
                }
            } else {
                Spacer(Modifier.width(28.dp))
            }

            // Radio button (only for real decks)
            if (item.node.deck != null) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
            }

            // Deck leaf name
            Text(
                text = item.node.leafName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.hasChildren) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha),
                modifier = Modifier.weight(1f),
            )

            // Card counts (Anki-style: blue new · orange learn · green review)
            item.node.deck?.let { deck ->
                DeckCardCounts(deck, alpha)
            }
        }
    }
}

@Composable
private fun DeckCardCounts(deck: DeckRow, alpha: Float) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (deck.newCount > 0) {
            Text(
                text = "${deck.newCount}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF1565C0).copy(alpha = alpha),    // blue — new
            )
        }
        if (deck.learnCount > 0) {
            Text(
                text = "${deck.learnCount}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFC35617).copy(alpha = alpha),    // orange — learn
            )
        }
        if (deck.reviewCount > 0) {
            Text(
                text = "${deck.reviewCount}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2E7D32).copy(alpha = alpha),    // green — review
            )
        }
        if (deck.isDynamic) {
            Text(
                text = "⚡",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  Package row (for blocked apps)
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun PackageRow(packageName: String, onRemove: () -> Unit) {
    val displayName = SettingsRepository.POPULAR_DISTRACTION_APPS[packageName] ?: packageName
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        Surface(
            onClick = onRemove,
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.error,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) {
            Text(
                text = "Remove",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun AddPackageButton(onAdd: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    if (editing) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Package name") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            QuietButton(
                label = "Add",
                onClick = {
                    val pkg = text.trim()
                    if (pkg.isNotEmpty()) {
                        onAdd(pkg)
                        text = ""
                        editing = false
                    }
                },
            )
        }
    } else {
        QuietButton(
            label = "Add blocked app",
            onClick = { editing = true },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  Stepper / hour pickers
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun Stepper(
    value: Int,
    suffix: String,
    step: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        StepButton(label = "−", enabled = value - step >= range.first) {
            onChange((value - step).coerceIn(range))
        }
        Spacer(Modifier.size(16.dp))
        Text(
            text = "$value $suffix",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f),
        )
        Spacer(Modifier.size(16.dp))
        StepButton(label = "+", enabled = value + step <= range.last) {
            onChange((value + step).coerceIn(range))
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = if (enabled) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuietHoursRow(
    start: Int,
    end: Int,
    onChange: (Int, Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        HourBox(
            label = "Start",
            hour = start,
            onChange = { onChange(it, end) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = "→",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        HourBox(
            label = "End",
            hour = end,
            onChange = { onChange(start, it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HourBox(
    label: String,
    hour: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onChange((hour + 1) % 24) },
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatHour(hour),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun formatHour(hour: Int): String = "%02d:00".format(hour)

private fun formatLastSync(ts: Long): String =
    if (ts <= 0L) "never"
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))

// ─────────────────────────────────────────────────────────────────────────
//  Shared button (matches the reviewer's QuietButton)
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun QuietButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
