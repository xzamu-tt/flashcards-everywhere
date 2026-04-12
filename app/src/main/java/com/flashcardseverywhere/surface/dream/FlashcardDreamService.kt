/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Android Dream (screensaver) that shows flashcards when the device is
 * charging and idle. The user can interact with the dream to grade cards.
 *
 * Activated in: Settings → Display → Screen saver → Flashcards Everywhere.
 *
 * Uses `isInteractive = true` so touch events are delivered to the content
 * view rather than dismissing the dream immediately.
 */
package com.flashcardseverywhere.surface.dream

import android.graphics.Color
import android.net.Uri
import android.service.dreams.DreamService
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flashcardseverywhere.data.anki.DueCard
import com.flashcardseverywhere.data.anki.Ease
import com.flashcardseverywhere.data.anki.ReviewState
import com.flashcardseverywhere.domain.ReviewSession
import com.flashcardseverywhere.data.prefs.SettingsRepository
import com.flashcardseverywhere.surface.notification.GradeReceiver
import com.flashcardseverywhere.ui.reviewer.CardHtmlRenderer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FlashcardDreamService : DreamService() {

    @Inject lateinit var session: ReviewSession
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var revealed = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true

        scope.launch {
            session.refresh(deckId = settings.selectedDeckId.first())
            val state = session.state.value
            val card = (state as? ReviewState.Card)?.card
            if (card != null) {
                setContentView(buildCardView(card))
            } else {
                setContentView(buildEmptyView())
            }
        }
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private fun buildCardView(card: DueCard): android.view.View {
        val dp = resources.displayMetrics.density

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D1A.toInt())
            setPadding((32 * dp).toInt(), (48 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())

            // Header
            addView(TextView(this@FlashcardDreamService).apply {
                text = "Flashcards Everywhere"
                setTextColor(0x88FFFFFF.toInt())
                textSize = 12f
                setPadding(0, 0, 0, (24 * dp).toInt())
            })

            // Front
            val scroll = ScrollView(this@FlashcardDreamService).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            val content = LinearLayout(this@FlashcardDreamService).apply {
                orientation = LinearLayout.VERTICAL
            }

            content.addView(createDreamWebView(card).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (32 * dp).toInt() }
                loadDataWithBaseURL(
                    "file:///android_asset/",
                    CardHtmlRenderer.render(
                        html = card.frontHtml,
                        cardOrd = card.cardOrd,
                        nightMode = true,
                        background = "transparent",
                        foreground = "#ffffff",
                        fontSize = 24,
                    ),
                    "text/html",
                    "UTF-8",
                    null,
                )
            })

            // Divider
            content.addView(android.view.View(this@FlashcardDreamService).apply {
                setBackgroundColor(0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                ).apply { bottomMargin = (32 * dp).toInt() }
            })

            // Back (hidden initially)
            val backWebView = createDreamWebView(card).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                visibility = android.view.View.GONE
                loadDataWithBaseURL(
                    "file:///android_asset/",
                    CardHtmlRenderer.render(
                        html = card.backHtml,
                        cardOrd = card.cardOrd,
                        nightMode = true,
                        background = "transparent",
                        foreground = "#ffffff",
                        fontSize = 24,
                    ),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            content.addView(backWebView)
            scroll.addView(content)
            addView(scroll)

            // Buttons container
            val btnContainer = LinearLayout(this@FlashcardDreamService).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (16 * dp).toInt(), 0, 0)
            }

            // "Show Answer" button
            val showAnswerBtn = Button(this@FlashcardDreamService).apply {
                text = "Show Answer"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF444466.toInt())
                textSize = 18f
                setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
            }

            // Grade buttons row (hidden initially)
            val gradeRow = LinearLayout(this@FlashcardDreamService).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = android.view.View.GONE
            }

            val eases = buildList {
                add("Again" to Ease.AGAIN)
                if (card.canShowHard) add("Hard" to Ease.HARD)
                add("Good" to Ease.GOOD)
                if (card.canShowEasy) add("Easy" to Ease.EASY)
            }
            for ((label, ease) in eases) {
                gradeRow.addView(Button(this@FlashcardDreamService).apply {
                    text = label
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(
                        when (ease) {
                            Ease.AGAIN -> 0xFFCC3333.toInt()
                            Ease.HARD -> 0xFFCC9933.toInt()
                            Ease.GOOD -> 0xFF339933.toInt()
                            Ease.EASY -> 0xFF3366CC.toInt()
                        }
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt() }
                    setOnClickListener {
                        GradeReceiver.sendGrade(
                            this@FlashcardDreamService, card.noteId, card.cardOrd, ease
                        )
                        // Load next card after short delay.
                        scope.launch {
                            delay(500)
                            session.refresh(deckId = settings.selectedDeckId.first())
                            val next = session.state.value
                            val nextCard = (next as? ReviewState.Card)?.card
                            if (nextCard != null) {
                                setContentView(buildCardView(nextCard))
                            } else {
                                setContentView(buildEmptyView())
                            }
                        }
                    }
                })
            }

            showAnswerBtn.setOnClickListener {
                backWebView.visibility = android.view.View.VISIBLE
                showAnswerBtn.visibility = android.view.View.GONE
                gradeRow.visibility = android.view.View.VISIBLE
                revealed = true
            }

            btnContainer.addView(showAnswerBtn)
            btnContainer.addView(gradeRow)
            addView(btnContainer)
        }
    }

    private fun buildEmptyView(): android.view.View {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D1A.toInt())
            gravity = Gravity.CENTER
            setPadding((32 * dp).toInt(), (48 * dp).toInt(), (32 * dp).toInt(), (48 * dp).toInt())

            addView(TextView(this@FlashcardDreamService).apply {
                text = "No cards due right now"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 20f
                gravity = Gravity.CENTER
            })
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun createDreamWebView(card: DueCard): WebView = WebView(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.loadsImagesAutomatically = true
        settings.allowContentAccess = true

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val filename = request.url.lastPathSegment ?: return null
                if (card.mediaFiles.contains(filename)) {
                    val mediaUri = Uri.withAppendedPath(
                        Uri.parse("content://com.ichi2.anki.flashcards/media"), filename
                    )
                    val inputStream =
                        contentResolver.openInputStream(mediaUri) ?: return null
                    val mimeType = when {
                        filename.endsWith(".jpg", true) ||
                            filename.endsWith(".jpeg", true) -> "image/jpeg"
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
}
