/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.surface.lockscreen

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashcardseverywhere.ui.reviewer.ReviewerScreen
import com.flashcardseverywhere.ui.reviewer.ReviewerViewModel
import com.flashcardseverywhere.ui.theme.FlashcardsTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activity hoisted above the keyguard via `setShowWhenLocked` + `setTurnScreenOn`.
 * Re-uses the in-app Compose reviewer UI verbatim — same component, different
 * surface.
 *
 * Triggered by [com.flashcardseverywhere.surface.notification.NotificationOrchestrator]
 * via a fullScreenIntent.
 */
@AndroidEntryPoint
class LockscreenReviewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService<KeyguardManager>()?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContent {
            FlashcardsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: ReviewerViewModel = hiltViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    ReviewerScreen(
                        state = state,
                        onGrade = { ease ->
                            vm.grade(ease)
                            // Auto-finish after grading so we don't trap the user.
                            finish()
                        },
                        onReveal = vm::reveal,
                        onRefresh = vm::refresh,
                    )
                }
            }
        }
    }
}
