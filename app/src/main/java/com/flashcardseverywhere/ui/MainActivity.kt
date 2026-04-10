/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardseverywhere.ui.reviewer.ReviewerScreen
import com.flashcardseverywhere.ui.reviewer.ReviewerViewModel
import com.flashcardseverywhere.ui.theme.FlashcardsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlashcardsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }
}

@Composable
private fun Root(vm: ReviewerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ReviewerScreen(
        state = state,
        onGrade = vm::grade,
        onReveal = vm::reveal,
        onRequestPermission = vm::requestPermissionFromActivity,
        onRefresh = vm::refresh,
    )
}
