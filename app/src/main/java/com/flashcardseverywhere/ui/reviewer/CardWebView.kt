/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui.reviewer

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders a card's question/answer HTML inside a transparent WebView.
 *
 * Why a WebView and not Compose text? AnkiDroid's `Card.QUESTION` / `Card.ANSWER`
 * columns return fully-rendered HTML *with* the note type's `<style>` block,
 * including class-based selectors, embedded fonts, and `[sound:...]` markup.
 * Reproducing all that in Compose is unworkable. The same WebView pattern
 * AnkiDroid itself uses works fine here.
 *
 * Media bytes are not exposed by the ContentProvider — see
 * docs/ANKI_CONTRACT_REFERENCE.md §"Media bytes are not readable". For
 * cards that reference local image/audio files we will fall back to
 * Coil + a custom `WebViewClient.shouldInterceptRequest` once the user
 * has granted SAF access to the AnkiDroid `collection.media/` folder.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CardWebView(
    html: String,
    modifier: Modifier = Modifier,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val onBackground = MaterialTheme.colorScheme.onBackground.toArgbHex()
    val background = MaterialTheme.colorScheme.background.toArgbHex()

    val wrapped = remember(html, isDark, onBackground, background) {
        // Inline a small CSS preamble that overrides AnkiDroid's note-type
        // styles to fit the Apple-minimal palette. Users can disable this in
        // settings later if they want their original templates back.
        """
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            html, body {
                background: $background;
                color: $onBackground;
                font-family: -apple-system, "Inter", "Helvetica Neue", system-ui, sans-serif;
                font-size: 22px;
                line-height: 1.45;
                padding: 0;
                margin: 0;
                text-align: center;
            }
            img { max-width: 100%; height: auto; }
            .card { background: transparent !important; color: inherit !important; }
        </style>
        </head>
        <body><div class="card">$html</div></body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = false  // AnkiDroid templates may use JS — opt-in later
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
            }
        },
        update = { wv ->
            wv.loadDataWithBaseURL(
                /* baseUrl  = */ "file:///android_asset/",
                /* data     = */ wrapped,
                /* mimeType = */ "text/html",
                /* encoding = */ "utf-8",
                /* historyUrl = */ null,
            )
        },
    )
}

private fun androidx.compose.ui.graphics.Color.toArgbHex(): String {
    val argb = this.value.toLong().let { (it shr 32).toInt() }
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "#%02X%02X%02X".format(r, g, b)
}
