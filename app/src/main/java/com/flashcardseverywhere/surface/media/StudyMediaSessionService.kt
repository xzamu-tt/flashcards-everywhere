/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * A MediaSession-backed foreground service that shows the current flashcard
 * as a media notification. On Android 11+ the media notification appears as
 * a prominent card in the notification shade and on the lockscreen Quick
 * Settings area, giving us maximum real estate.
 *
 * Grade buttons are mapped to media transport actions:
 *   - Rewind  → Again
 *   - Previous → Hard
 *   - Play/Pause → Show Answer / Good
 *   - Next → Easy
 *   - Stop → Dismiss session
 *
 * A silent audio focus is held to keep the session alive.
 */
package com.flashcardseverywhere.surface.media

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.flashcardseverywhere.R
import com.flashcardseverywhere.app.FlashcardsApp
import com.flashcardseverywhere.data.anki.DueCard
import com.flashcardseverywhere.data.anki.Ease
import com.flashcardseverywhere.data.anki.ReviewState
import com.flashcardseverywhere.data.prefs.SettingsRepository
import com.flashcardseverywhere.domain.ReviewSession
import com.flashcardseverywhere.surface.notification.GradeReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class StudyMediaSessionService : LifecycleService() {

    @Inject lateinit var session: ReviewSession
    @Inject lateinit var settings: SettingsRepository

    private lateinit var mediaSession: MediaSessionCompat
    private var audioFocusRequest: AudioFocusRequest? = null
    private var revealed = false
    private var currentCard: DueCard? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "FlashcardsStudy").apply {
            setCallback(mediaCallback)
            isActive = true
        }
        acquireAudioFocus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        lifecycleScope.launch {
            session.refresh(deckId = settings.selectedDeckId.first())
            val state = session.state.value
            currentCard = (state as? ReviewState.Card)?.card
            revealed = false
            updateMediaNotification()
        }

        return START_STICKY
    }

    private fun updateMediaNotification() {
        val card = currentCard

        // Build metadata.
        val metadata = MediaMetadataCompat.Builder().apply {
            if (card != null) {
                putString(MediaMetadataCompat.METADATA_KEY_TITLE, stripHtml(card.frontHtml))
                putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    if (revealed) stripHtml(card.backHtml) else "Tap play to reveal answer"
                )
                putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Flashcards Everywhere")
                putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            } else {
                putString(MediaMetadataCompat.METADATA_KEY_TITLE, "No cards due")
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "All caught up!")
                putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Flashcards Everywhere")
            }
        }.build()
        mediaSession.setMetadata(metadata)

        // Build playback state with actions.
        val stateBuilder = PlaybackStateCompat.Builder()
        var actions = PlaybackStateCompat.ACTION_STOP

        if (card != null && !revealed) {
            // Before reveal: only Play (to reveal).
            actions = actions or PlaybackStateCompat.ACTION_PLAY
            stateBuilder.setState(PlaybackStateCompat.STATE_PAUSED, 0L, 0f)
        } else if (card != null && revealed) {
            // After reveal: grade buttons via transport controls.
            actions = actions or
                PlaybackStateCompat.ACTION_REWIND or        // Again
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or  // Good
                PlaybackStateCompat.ACTION_FAST_FORWARD      // Easy
            if (card.canShowHard) {
                actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS // Hard
            }
            stateBuilder.setState(PlaybackStateCompat.STATE_PLAYING, 0L, 0f)

            // Custom actions for clearer labels.
            stateBuilder.addCustomAction("again", "Again", android.R.drawable.ic_menu_revert)
            if (card.canShowHard) {
                stateBuilder.addCustomAction("hard", "Hard", android.R.drawable.ic_menu_close_clear_cancel)
            }
            stateBuilder.addCustomAction("good", "Good", android.R.drawable.ic_media_play)
            if (card.canShowEasy) {
                stateBuilder.addCustomAction("easy", "Easy", android.R.drawable.ic_media_ff)
            }
        } else {
            stateBuilder.setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
        }

        stateBuilder.setActions(actions)
        mediaSession.setPlaybackState(stateBuilder.build())

        // Build notification.
        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = NotificationCompat.Builder(this, FlashcardsApp.CHANNEL_DUE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(card?.let { stripHtml(it.frontHtml) } ?: "No cards due")
            .setContentText(
                if (card != null && revealed) stripHtml(card.backHtml)
                else if (card != null) "Tap to reveal"
                else "All caught up!"
            )
            .setStyle(style)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Add action buttons for the notification.
        if (card != null && !revealed) {
            builder.addAction(
                android.R.drawable.ic_media_play, "Reveal",
                buildMediaAction(PlaybackStateCompat.ACTION_PLAY)
            )
        } else if (card != null && revealed) {
            builder.addAction(
                android.R.drawable.ic_menu_revert, "Again",
                buildMediaAction(PlaybackStateCompat.ACTION_REWIND)
            )
            builder.addAction(
                android.R.drawable.ic_media_play, "Good",
                buildMediaAction(PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            )
            builder.addAction(
                android.R.drawable.ic_media_ff, "Easy",
                buildMediaAction(PlaybackStateCompat.ACTION_FAST_FORWARD)
            )
        }

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private val mediaCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            // Reveal answer.
            revealed = true
            updateMediaNotification()
        }

        override fun onRewind() = gradeAndAdvance(Ease.AGAIN)
        override fun onSkipToPrevious() = gradeAndAdvance(Ease.HARD)
        override fun onSkipToNext() = gradeAndAdvance(Ease.GOOD)
        override fun onFastForward() = gradeAndAdvance(Ease.EASY)

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                "again" -> gradeAndAdvance(Ease.AGAIN)
                "hard" -> gradeAndAdvance(Ease.HARD)
                "good" -> gradeAndAdvance(Ease.GOOD)
                "easy" -> gradeAndAdvance(Ease.EASY)
            }
        }

        override fun onStop() {
            mediaSession.isActive = false
            stopSelf()
        }
    }

    private fun gradeAndAdvance(ease: Ease) {
        val card = currentCard ?: return
        GradeReceiver.sendGrade(this, card.noteId, card.cardOrd, ease)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(300)
            session.refresh(deckId = settings.selectedDeckId.first())
            val state = session.state.value
            currentCard = (state as? ReviewState.Card)?.card
            revealed = false
            updateMediaNotification()
        }
    }

    private fun buildMediaAction(action: Long): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setPackage(packageName)
        }
        return PendingIntent.getBroadcast(
            this, action.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun acquireAudioFocus() {
        val am = getSystemService<AudioManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { /* no-op — we hold focus silently */ }
                .build()
            am.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus({}, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun releaseAudioFocus() {
        val am = getSystemService<AudioManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus {}
        }
    }

    override fun onDestroy() {
        releaseAudioFocus()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun stripHtml(html: String): String =
        androidx.core.text.HtmlCompat
            .fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
            .toString()
            .trim()

    companion object {
        private const val FOREGROUND_ID = 2003

        fun start(ctx: Context) {
            val intent = Intent(ctx, StudyMediaSessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, StudyMediaSessionService::class.java))
        }
    }
}
