/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Activity hosted inside a notification bubble. Reuses ReviewerScreen.
 * allowEmbedded + resizeableActivity allow it to render inside the bubble.
 */
package com.flashcardseverywhere.surface.bubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardseverywhere.ui.reviewer.ReviewerScreen
import com.flashcardseverywhere.ui.reviewer.ReviewerViewModel
import com.flashcardseverywhere.ui.theme.FlashcardsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BubbleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FlashcardsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: ReviewerViewModel = hiltViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    ReviewerScreen(
                        state = state,
                        onGrade = vm::grade,
                        onReveal = vm::reveal,
                        onRefresh = vm::refresh,
                    )
                }
            }
        }
    }
}
