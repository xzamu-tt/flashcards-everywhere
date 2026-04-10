/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.surface.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pokes every active [CardWidget] instance to recompose. Called by
 * [com.flashcardseverywhere.surface.notification.GradeReceiver] after a grade,
 * by `ReviewSession` after a refresh, and by a `WorkManager` periodic.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    suspend fun updateAll() {
        runCatching {
            CardWidget().updateAll(ctx)
        }
    }

    suspend fun glanceIds(): List<androidx.glance.GlanceId> =
        GlanceAppWidgetManager(ctx).getGlanceIds(CardWidget::class.java)
}
