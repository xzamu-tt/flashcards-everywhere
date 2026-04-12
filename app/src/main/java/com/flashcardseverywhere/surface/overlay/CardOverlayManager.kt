/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Full-screen overlay drawn via SYSTEM_ALERT_WINDOW.
 *
 * Unlike AccessibilityService (dropped in M6 due to Play Protect restrictions),
 * SYSTEM_ALERT_WINDOW is a normal special-access permission that users grant in
 * Settings → Apps → Special access → Display over other apps. It works on all
 * Android versions ≥ 6.0 and is not restricted by Google Play policies.
 *
 * The overlay shows the current flashcard front/back and grade buttons.
 * The user MUST grade the card to dismiss the overlay — there is no close button.
 */
package com.flashcardseverywhere.surface.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.getSystemService
import com.flashcardseverywhere.data.anki.DueCard
import com.flashcardseverywhere.data.anki.Ease
import com.flashcardseverywhere.surface.notification.GradeReceiver
import com.flashcardseverywhere.ui.reviewer.CardHtmlRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CardOverlayManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WindowManager? get() = ctx.getSystemService()
    private var overlayView: View? = null

    fun showCard(card: DueCard, onGraded: (() -> Unit)? = null) {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        dismiss()

        val layout = buildOverlayLayout(card, onGraded)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            // No FLAG_NOT_FOCUSABLE, no FLAG_NOT_TOUCH_MODAL — overlay captures
            // all touch and key events so the user must grade the card.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        overlayView = layout
        wm?.addView(layout, params)
        vibrate()
    }

    fun showBlocker(message: String, cardsRemaining: Int, onReviewCard: () -> Unit) {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        dismiss()

        val layout = buildBlockerLayout(message, cardsRemaining, onReviewCard)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            // No FLAG_NOT_FOCUSABLE, no FLAG_NOT_TOUCH_MODAL — fully blocking.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.CENTER
        }

        overlayView = layout
        wm?.addView(layout, params)
        vibrate()
    }

    fun dismiss() {
        overlayView?.let {
            runCatching { wm?.removeView(it) }
            overlayView = null
        }
    }

    val isShowing: Boolean get() = overlayView != null

    /**
     * A [FrameLayout] that intercepts the back key so the user cannot dismiss
     * the overlay without grading.
     */
    private inner class BackBlockingFrameLayout(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                // Consume back press — do nothing.
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    // ── WebView helpers ─────────────────────────────────────────────────

    private fun createCardWebView(card: DueCard): WebView = WebView(ctx).apply {
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
                        ctx.contentResolver.openInputStream(mediaUri) ?: return null
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

    // ── Overlay layout ──────────────────────────────────────────────────

    private fun buildOverlayLayout(card: DueCard, onGraded: (() -> Unit)?): View {
        val dp = ctx.resources.displayMetrics.density

        return BackBlockingFrameLayout(ctx).apply {
            setBackgroundColor(0xFF0D0D1A.toInt())

            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (24 * dp).toInt(), (64 * dp).toInt(),
                    (24 * dp).toInt(), (32 * dp).toInt(),
                )
            }

            // Title
            inner.addView(TextView(ctx).apply {
                text = "\u26A1 Time to review!"
                setTextColor(0xFFE0E0E0.toInt())
                textSize = 14f
                setPadding(0, 0, 0, (16 * dp).toInt())
            })

            // Scrollable card content
            val scroll = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                )
            }
            val cardContent = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

            // Front (WebView, always visible)
            val frontWebView = createCardWebView(card).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            frontWebView.loadDataWithBaseURL(
                "file:///android_asset/",
                CardHtmlRenderer.render(
                    html = card.frontHtml,
                    cardOrd = card.cardOrd,
                    nightMode = true,
                    background = "transparent",
                    foreground = "#ffffff",
                    fontSize = 18,
                ),
                "text/html",
                "UTF-8",
                null,
            )
            cardContent.addView(frontWebView)

            // Divider (initially GONE, shown with answer)
            val divider = View(ctx).apply {
                setBackgroundColor(0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt(),
                ).apply {
                    topMargin = (24 * dp).toInt()
                    bottomMargin = (24 * dp).toInt()
                }
                visibility = View.GONE
            }
            cardContent.addView(divider)

            // Back (WebView, initially GONE)
            val backWebView = createCardWebView(card).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                visibility = View.GONE
            }
            backWebView.loadDataWithBaseURL(
                "file:///android_asset/",
                CardHtmlRenderer.render(
                    html = card.backHtml,
                    cardOrd = card.cardOrd,
                    nightMode = true,
                    background = "transparent",
                    foreground = "#ffffff",
                    fontSize = 18,
                ),
                "text/html",
                "UTF-8",
                null,
            )
            cardContent.addView(backWebView)

            scroll.addView(cardContent)
            inner.addView(scroll)

            // "Show Answer" button (initially VISIBLE)
            val showAnswerBtn = Button(ctx).apply {
                text = "Show Answer"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF3366CC.toInt())
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (24 * dp).toInt()
                }
            }
            inner.addView(showAnswerBtn)

            // Grade buttons row (initially GONE)
            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (24 * dp).toInt(), 0, 0)
                visibility = View.GONE
            }
            val eases = buildList {
                add("Again" to Ease.AGAIN)
                if (card.canShowHard) add("Hard" to Ease.HARD)
                add("Good" to Ease.GOOD)
                if (card.canShowEasy) add("Easy" to Ease.EASY)
            }
            for ((label, ease) in eases) {
                btnRow.addView(Button(ctx).apply {
                    text = label
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(
                        when (ease) {
                            Ease.AGAIN -> 0xFFCC3333.toInt()
                            Ease.HARD  -> 0xFFCC9933.toInt()
                            Ease.GOOD  -> 0xFF339933.toInt()
                            Ease.EASY  -> 0xFF3366CC.toInt()
                        }
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply {
                        marginStart = (4 * dp).toInt()
                        marginEnd = (4 * dp).toInt()
                    }
                    setOnClickListener {
                        GradeReceiver.sendGrade(ctx, card.noteId, card.cardOrd, ease)
                        onGraded?.invoke()
                        dismiss()
                    }
                })
            }
            inner.addView(btnRow)

            // Wire up "Show Answer" tap
            showAnswerBtn.setOnClickListener {
                divider.visibility = View.VISIBLE
                backWebView.visibility = View.VISIBLE
                btnRow.visibility = View.VISIBLE
                showAnswerBtn.visibility = View.GONE
            }

            addView(
                inner,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun buildBlockerLayout(
        message: String,
        cardsRemaining: Int,
        onReviewCard: () -> Unit,
    ): View {
        val dp = ctx.resources.displayMetrics.density

        return BackBlockingFrameLayout(ctx).apply {
            setBackgroundColor(0xFF0D0D1A.toInt())

            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (32 * dp).toInt(), (64 * dp).toInt(),
                    (32 * dp).toInt(), (64 * dp).toInt(),
                )
                gravity = Gravity.CENTER
            }

            // Lock icon
            inner.addView(TextView(ctx).apply {
                text = "\uD83D\uDD12"
                textSize = 48f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (24 * dp).toInt())
            })

            // Title
            inner.addView(TextView(ctx).apply {
                text = "App Blocked"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 28f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (16 * dp).toInt())
            })

            // Message
            inner.addView(TextView(ctx).apply {
                text = message
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (16 * dp).toInt())
            })

            // Cards remaining counter
            inner.addView(TextView(ctx).apply {
                text = "Review $cardsRemaining card${if (cardsRemaining != 1) "s" else ""} to unlock"
                setTextColor(0xFFE0E0E0.toInt())
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (32 * dp).toInt())
            })

            // Start reviewing button
            inner.addView(Button(ctx).apply {
                text = "Start reviewing"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF3366CC.toInt())
                textSize = 16f
                setPadding(
                    (24 * dp).toInt(), (16 * dp).toInt(),
                    (24 * dp).toInt(), (16 * dp).toInt(),
                )
                setOnClickListener { onReviewCard() }
            })

            addView(
                inner,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService<Vibrator>()
        }
        @Suppress("DEPRECATION")
        vibrator?.vibrate(200)
    }
}
