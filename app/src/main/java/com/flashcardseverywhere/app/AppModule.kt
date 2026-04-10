/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.preferenceDataStore: DataStore<Preferences> by preferencesDataStore("flashcards-prefs")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePrefs(@ApplicationContext ctx: Context): DataStore<Preferences> =
        ctx.preferenceDataStore
}
