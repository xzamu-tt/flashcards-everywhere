/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Full-flavor binding for the shared onboarding screen's accessibility-service
 * permission row. Resolves to the real overlay service class.
 */
package com.flashcardseverywhere.surface.overlay

internal val CardOverlayAccessibilityServiceClass: Class<*> =
    CardOverlayAccessibilityService::class.java
