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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.getSystemService
import com.flashcardseverywhere.data.anki.DueCard
import com.flashcardseverywhere.data.anki.Ease
import com.flashcardseverywhere.surface.notification.GradeReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CardOverlayManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WindowManager? get() = ctx.getSystemService()
    private var overlayView: android.view.View? = null

    fun showCard(card: DueCard) {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        dismiss()

        val layout = buildOverlayLayout(card)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        // Make the overlay capture touch so user must interact with it.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()

        overlayView = layout
        wm?.addView(layout, params)
        vibrate()
    }

    fun showBlocker(message: String, onGradeCard: () -> Unit) {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        dismiss()

        val layout = buildBlockerLayout(message, onGradeCard)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
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

    private fun buildOverlayLayout(card: DueCard): android.view.View {
        val dp = ctx.resources.displayMetrics.density

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A2E.toInt()) // dark navy
            setPadding((24 * dp).toInt(), (64 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())

            // Title
            addView(TextView(ctx).apply {
                text = "⚡ Time to review!"
                setTextColor(0xFFE0E0E0.toInt())
                textSize = 14f
                setPadding(0, 0, 0, (16 * dp).toInt())
            })

            // Front
            val scroll = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            val cardContent = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            cardContent.addView(TextView(ctx).apply {
                text = stripHtml(card.frontHtml)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 20f
                setPadding(0, 0, 0, (24 * dp).toInt())
            })
            cardContent.addView(android.view.View(ctx).apply {
                setBackgroundColor(0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                ).apply { bottomMargin = (24 * dp).toInt() }
            })
            cardContent.addView(TextView(ctx).apply {
                text = stripHtml(card.backHtml)
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 18f
            })
            scroll.addView(cardContent)
            addView(scroll)

            // Grade buttons row
            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (24 * dp).toInt(), 0, 0)
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
                            Ease.HARD -> 0xFFCC9933.toInt()
                            Ease.GOOD -> 0xFF339933.toInt()
                            Ease.EASY -> 0xFF3366CC.toInt()
                        }
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt() }
                    setOnClickListener {
                        GradeReceiver.sendGrade(ctx, card.noteId, card.cardOrd, ease)
                        dismiss()
                    }
                })
            }
            addView(btnRow)
        }
    }

    private fun buildBlockerLayout(message: String, onGradeCard: () -> Unit): android.view.View {
        val dp = ctx.resources.displayMetrics.density

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D1A.toInt())
            setPadding((32 * dp).toInt(), (64 * dp).toInt(), (32 * dp).toInt(), (64 * dp).toInt())
            gravity = Gravity.CENTER

            addView(TextView(ctx).apply {
                text = "🔒"
                textSize = 48f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (24 * dp).toInt())
            })

            addView(TextView(ctx).apply {
                text = "App Blocked"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 28f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (16 * dp).toInt())
            })

            addView(TextView(ctx).apply {
                text = message
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (32 * dp).toInt())
            })

            addView(Button(ctx).apply {
                text = "Review a flashcard to unlock"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF3366CC.toInt())
                textSize = 16f
                setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
                setOnClickListener { onGradeCard() }
            })
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

    private fun stripHtml(html: String): String =
        androidx.core.text.HtmlCompat
            .fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
            .toString()
            .trim()
}
