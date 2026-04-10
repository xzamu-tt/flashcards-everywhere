/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui.reviewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Composable entry point used by the navigation graph. Splits the stateful
 * ViewModel concerns from the stateless [ReviewerScreen] so previews and
 * tests can render the screen without Hilt.
 */
@Composable
fun ReviewerRoute(vm: ReviewerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ReviewerScreen(
        state = state,
        onGrade = vm::grade,
        onReveal = vm::reveal,
        onRefresh = vm::refresh,
    )
}
