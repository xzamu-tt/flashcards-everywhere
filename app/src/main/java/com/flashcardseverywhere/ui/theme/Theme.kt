/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    background = Ink,
    surface = Ink,
    surfaceVariant = InkRaised,
    onBackground = PrimaryOnDark,
    onSurface = PrimaryOnDark,
    onSurfaceVariant = SecondaryOnDark,
    primary = PrimaryOnDark,
    onPrimary = Ink,
    secondary = SecondaryOnDark,
    onSecondary = Ink,
    tertiary = Accent,
    onTertiary = PrimaryOnDark,
    outline = InkHairline,
    outlineVariant = InkHairline,
)

private val LightScheme = lightColorScheme(
    background = Paper,
    surface = Paper,
    surfaceVariant = PaperRaised,
    onBackground = PrimaryOnLight,
    onSurface = PrimaryOnLight,
    onSurfaceVariant = SecondaryOnLight,
    primary = PrimaryOnLight,
    onPrimary = Paper,
    secondary = SecondaryOnLight,
    onSecondary = Paper,
    tertiary = Accent,
    onTertiary = Paper,
    outline = PaperHairline,
    outlineVariant = PaperHairline,
)

@Composable
fun FlashcardsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = FlashcardsTypography,
        content = content,
    )
}
