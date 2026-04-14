/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Toggles system-wide grayscale display via the accessibility daltonizer API.
 *
 * Color is dopamine. Remove it during enforcement; restore it only while
 * reviewing flashcards.
 *
 * Requires WRITE_SECURE_SETTINGS, granted once via ADB:
 *   adb shell pm grant com.flashcardseverywhere android.permission.WRITE_SECURE_SETTINGS
 *
 * Uses Settings.Secure keys:
 *   - accessibility_display_daltonizer_enabled (0/1)
 *   - accessibility_display_daltonizer (0 = grayscale/monochromacy, -1 = off)
 *
 * Works on Android 8.0+ (API 26+). Same API used by Digital Wellbeing's
 * Bedtime mode and third-party "Grayscale" apps on the Play Store.
 */
package com.flashcardseverywhere.service.grayscale

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.flashcardseverywhere.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GrayscaleManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
) {

    /**
     * Enables system-wide grayscale. Called when enforcement activates
     * (budget locked, doom-scroll detected, etc.).
     */
    suspend fun enableGrayscale() {
        if (!settings.grayscaleEnabled.first()) return
        if (!hasWriteSecureSettings()) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted — grayscale unavailable. " +
                "Grant via: adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS")
            return
        }

        try {
            val cr = ctx.contentResolver
            Settings.Secure.putInt(cr, DALTONIZER_ENABLED, 1)
            Settings.Secure.putInt(cr, DALTONIZER, DALTONIZER_GRAYSCALE)
            settings.setGrayscaleActive(true)
            Log.d(TAG, "Grayscale enabled")
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to enable grayscale — permission denied", e)
        }
    }

    /**
     * Disables system-wide grayscale. Called when:
     *   - User starts reviewing a card (reward: color returns)
     *   - Budget is replenished
     *   - Enforcement is turned off
     */
    suspend fun disableGrayscale() {
        if (!settings.grayscaleActive.first()) return
        if (!hasWriteSecureSettings()) return

        try {
            val cr = ctx.contentResolver
            Settings.Secure.putInt(cr, DALTONIZER_ENABLED, 0)
            Settings.Secure.putInt(cr, DALTONIZER, DALTONIZER_OFF)
            settings.setGrayscaleActive(false)
            Log.d(TAG, "Grayscale disabled")
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to disable grayscale — permission denied", e)
        }
    }

    /**
     * Checks whether WRITE_SECURE_SETTINGS was granted via ADB.
     */
    fun hasWriteSecureSettings(): Boolean {
        return try {
            // Attempt a harmless read to verify we can write.
            // The actual check: try to read a secure setting. If the app
            // has WRITE_SECURE_SETTINGS, it can also read them.
            ctx.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "GrayscaleManager"

        // Settings.Secure key names (not exposed as public constants pre-API 33)
        private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        private const val DALTONIZER = "accessibility_display_daltonizer"

        // Daltonizer modes
        private const val DALTONIZER_GRAYSCALE = 0  // Monochromacy
        private const val DALTONIZER_OFF = -1
    }
}
