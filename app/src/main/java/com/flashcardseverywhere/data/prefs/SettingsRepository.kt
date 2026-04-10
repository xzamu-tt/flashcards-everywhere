/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    object Keys {
        val DAILY_GOAL = intPreferencesKey("daily_goal_cards")
        val PACING_INTERVAL_MIN = intPreferencesKey("pacing_interval_min")
        val QUIET_HOURS_START = intPreferencesKey("quiet_start_hour")
        val QUIET_HOURS_END = intPreferencesKey("quiet_end_hour")
        val ENABLED_DECKS = stringSetPreferencesKey("enabled_deck_ids")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    }

    val dailyGoal: Flow<Int> = store.data.map { it[Keys.DAILY_GOAL] ?: DEFAULT_DAILY_GOAL }
    val pacingInterval: Flow<Int> = store.data.map { it[Keys.PACING_INTERVAL_MIN] ?: DEFAULT_PACING_MIN }
    val enabledDecks: Flow<Set<String>> = store.data.map { it[Keys.ENABLED_DECKS] ?: emptySet() }
    val lastSyncAt: Flow<Long> = store.data.map { it[Keys.LAST_SYNC_AT] ?: 0L }

    suspend fun setDailyGoal(value: Int) = store.edit { it[Keys.DAILY_GOAL] = value }
    suspend fun setPacingInterval(min: Int) = store.edit { it[Keys.PACING_INTERVAL_MIN] = min }
    suspend fun setEnabledDecks(ids: Set<String>) = store.edit { it[Keys.ENABLED_DECKS] = ids }
    suspend fun markSyncedNow() = store.edit { it[Keys.LAST_SYNC_AT] = System.currentTimeMillis() }

    companion object {
        const val DEFAULT_DAILY_GOAL = 200
        const val DEFAULT_PACING_MIN = 10
    }
}
