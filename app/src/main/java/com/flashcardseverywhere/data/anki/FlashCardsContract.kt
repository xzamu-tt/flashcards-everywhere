/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.flashcardseverywhere.data.anki

import android.net.Uri

/**
 * Mirror of the constants AnkiDroid exposes through its `FlashCardsContract`
 * ContentProvider, hard-coded so we don't need to depend on AnkiDroid's API
 * artifact (which has historically been awkward to consume from Gradle).
 *
 * Source of truth (verified against `main` branch as of 2026-04):
 * https://github.com/ankidroid/Anki-Android/blob/main/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt
 *
 * If AnkiDroid ever bumps the contract these constants must be re-verified.
 */
object FlashCardsContract {

    const val AUTHORITY = "com.ichi2.anki.flashcards"
    const val AUTHORITY_DEBUG = "com.ichi2.anki.debug.flashcards"

    /** `protectionLevel="dangerous"` — must be requested at runtime on API 23+. */
    const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    /** AnkiDroid package id (release / debug / Amazon flavor). */
    const val ANKIDROID_PACKAGE = "com.ichi2.anki"
    const val ANKIDROID_PACKAGE_DEBUG = "com.ichi2.anki.debug"

    /** Sync trigger intent. Throttled to one fire per 2 minutes by AnkiDroid. */
    const val ACTION_SYNC = "com.ichi2.anki.DO_SYNC"

    /** Hard cap from AnkiDroid `IntentHandler.INTENT_SYNC_MIN_INTERVAL`. */
    const val SYNC_MIN_INTERVAL_MS = 2L * 60L * 1000L

    val AUTHORITY_URI: Uri = Uri.parse("content://$AUTHORITY")

    object Note {
        val CONTENT_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "notes")
        val CONTENT_URI_V2: Uri = Uri.withAppendedPath(AUTHORITY_URI, "notes_v2")
        const val _ID = "_id"
        const val GUID = "guid"
        const val MID = "mid"
        const val MOD = "mod"
        const val USN = "usn"
        const val TAGS = "tags"
        const val FLDS = "flds"
        const val SFLD = "sfld"
        const val CSUM = "csum"
        const val FLAGS = "flags"
        const val DATA = "data"
    }

    object Card {
        val CONTENT_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "cards")
        const val NOTE_ID = "note_id"
        const val CARD_ORD = "ord"
        const val CARD_NAME = "card_name"
        const val DECK_ID = "deck_id"
        const val QUESTION = "question"
        const val ANSWER = "answer"
        const val QUESTION_SIMPLE = "question_simple"
        const val ANSWER_SIMPLE = "answer_simple"
        const val ANSWER_PURE = "answer_pure"
    }

    object Model {
        val CONTENT_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "models")
        const val CURRENT_MODEL_ID = "current"
    }

    object Deck {
        val CONTENT_ALL_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "decks")
        val CONTENT_SELECTED_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "selected_deck")
        const val DECK_ID = "deck_id"
        const val DECK_NAME = "deck_name"
        const val DECK_COUNTS = "deck_count"
        const val OPTIONS = "options"
        const val DECK_DYN = "deck_dyn"
        const val DECK_DESC = "deck_desc"
    }

    object ReviewInfo {
        /** AKA the "review queue" / "schedule" URI. */
        val CONTENT_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "schedule")
        const val NOTE_ID = "note_id"
        const val CARD_ORD = "ord"
        const val BUTTON_COUNT = "button_count"
        const val NEXT_REVIEW_TIMES = "next_review_times"
        const val MEDIA_FILES = "media_files"
        const val EASE = "answer_ease"
        const val TIME_TAKEN = "time_taken"
        const val BURY = "buried"
        const val SUSPEND = "suspended"
    }

    object AnkiMedia {
        val CONTENT_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "media")
    }
}

/** AnkiDroid ease grades. The cursor's `BUTTON_COUNT` may restrict which are valid. */
enum class Ease(val value: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4),
}
