/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui.reviewer

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
    mediaFiles: List<String> = emptyList(),
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val onBackground = MaterialTheme.colorScheme.onBackground.toArgbHex()
    val background = MaterialTheme.colorScheme.background.toArgbHex()

    val wrapped = remember(html, isDark, onBackground, background) {
        CardHtmlRenderer.render(
            html = html,
            nightMode = isDark,
            background = background,
            foreground = onBackground,
            fontSize = 22,
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowContentAccess = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val filename = request.url.lastPathSegment ?: return null
                        if (mediaFiles.contains(filename)) {
                            val mediaUri = android.net.Uri.withAppendedPath(
                                android.net.Uri.parse("content://com.ichi2.anki.flashcards/media"),
                                filename
                            )
                            val inputStream = view.context.contentResolver.openInputStream(mediaUri)
                                ?: return null
                            val mimeType = when {
                                filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
                                filename.endsWith(".png", true) -> "image/png"
                                filename.endsWith(".gif", true) -> "image/gif"
                                filename.endsWith(".webp", true) -> "image/webp"
                                filename.endsWith(".svg", true) -> "image/svg+xml"
                                else -> "application/octet-stream"
                            }
                            return WebResourceResponse(mimeType, "UTF-8", inputStream)
                        }
                        return null
                    }
                }
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
