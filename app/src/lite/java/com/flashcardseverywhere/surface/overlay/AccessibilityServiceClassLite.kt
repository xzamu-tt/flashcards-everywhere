/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Lite-flavor placeholder. The lite flavor never enables an accessibility
 * service, so onboarding will never query this — but the shared code path
 * still has to compile, so we point at a harmless type.
 */
package com.flashcardseverywhere.surface.overlay

internal val CardOverlayAccessibilityServiceClass: Class<*> = LiteOverlayStub::class.java
