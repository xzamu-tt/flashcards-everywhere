/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Apple-minimal / Linear-style palette.
 *   - Two neutral surfaces (ink, paper).
 *   - Single accent.
 *   - Hairline divider tone.
 *   - No gradients, no glass, no Material You override.
 */
package com.flashcardseverywhere.ui.theme

import androidx.compose.ui.graphics.Color

// Dark surface tones
val Ink = Color(0xFF0B0B0F)
val InkRaised = Color(0xFF131318)
val InkHairline = Color(0xFF1F1F23)

// Light surface tones
val Paper = Color(0xFFFAF9F6)
val PaperRaised = Color(0xFFFFFFFF)
val PaperHairline = Color(0xFFE8E6E0)

// Text
val PrimaryOnDark = Color(0xFFF4F4F5)
val SecondaryOnDark = Color(0xFFA1A1AA)
val PrimaryOnLight = Color(0xFF09090B)
val SecondaryOnLight = Color(0xFF52525B)

// Single accent — used sparingly (focus rings, current-card highlight, streak)
val Accent = Color(0xFF3B82F6)
val AccentMuted = Color(0xFF1E3A8A)

// Grade-button neutrals — deliberately monochrome.
// Color is conveyed by position + label, never hue.
val GradeAgain = Color(0xFFB4B4BB)
val GradeHard = Color(0xFFB4B4BB)
val GradeGood = Color(0xFFB4B4BB)
val GradeEasy = Color(0xFFB4B4BB)
