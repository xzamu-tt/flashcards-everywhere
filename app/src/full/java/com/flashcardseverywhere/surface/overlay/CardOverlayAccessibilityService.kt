/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Full-flavor only. NOT distributable via Play Store: Google's Jan 2026
 * AccessibilityService policy bans non-assistive use.
 *
 * Reference: docs/ANDROID_SURFACES_REFERENCE.md §4.
 */
package com.flashcardseverywhere.surface.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flashcardseverywhere.data.anki.DueCard
import com.flashcardseverywhere.data.anki.ReviewState
import com.flashcardseverywhere.domain.ReviewSession
import com.flashcardseverywhere.ui.theme.FlashcardsTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Detects foreground app changes via `TYPE_WINDOW_STATE_CHANGED` and draws a
 * Compose overlay using `TYPE_ACCESSIBILITY_OVERLAY` (no SYSTEM_ALERT_WINDOW
 * required since we're inside an AccessibilityService).
 *
 * Compose-in-Service requires us to implement [LifecycleOwner],
 * [ViewModelStoreOwner], and [SavedStateRegistryOwner] manually so the
 * `ViewTree*Owner` calls on the host ComposeView resolve.
 */
@AndroidEntryPoint
class CardOverlayAccessibilityService :
    AccessibilityService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    @Inject lateinit var session: ReviewSession

    // Lifecycle plumbing
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStoreInternal = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreInternal
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val ioScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var overlayView: View? = null
    private var lastShownAt: Long = 0L
    private val cooldownPerAppMs = 5L * 60L * 1000L
    private val lastShownPerApp = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return  // ignore ourselves

        val now = System.currentTimeMillis()
        val lastForPkg = lastShownPerApp[pkg] ?: 0L
        if (now - lastForPkg < cooldownPerAppMs) return

        // TODO: consult settings (whitelist / blacklist / "every app")
        lastShownPerApp[pkg] = now
        showCardOverlay()
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        removeOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStoreInternal.clear()
        ioScope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────

    private fun showCardOverlay() {
        ioScope.launch {
            session.refresh()
            val state = session.state.first { it !is ReviewState.Loading }
            val card = (state as? ReviewState.Card)?.card ?: return@launch
            attachOverlay(card)
        }
    }

    private fun attachOverlay(card: DueCard) {
        removeOverlay()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@CardOverlayAccessibilityService)
            setViewTreeViewModelStoreOwner(this@CardOverlayAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@CardOverlayAccessibilityService)
            setContent {
                FlashcardsTheme {
                    OverlayCard(card = card, onDismiss = ::removeOverlay)
                }
            }
        }

        overlayView = composeView
        wm.addView(composeView, params)
        lastShownAt = System.currentTimeMillis()
    }

    private fun removeOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        overlayView?.let {
            runCatching { wm.removeViewImmediate(it) }
        }
        overlayView = null
    }
}

@Composable
private fun OverlayCard(card: DueCard, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier.padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = androidx.core.text.HtmlCompat
                    .fromHtml(card.frontHtml, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .toString()
                    .trim(),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
    // TODO M6.5: tap to reveal back, swipe to grade, dismiss timer.
    @Suppress("UNUSED_EXPRESSION") onDismiss
}
