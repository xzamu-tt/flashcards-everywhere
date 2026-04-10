/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Centralised "is X granted / how do I deeplink to settings to grant it"
 * helpers for all the special-access permissions we need.
 *
 * Reference: docs/ANDROID_SURFACES_REFERENCE.md §6 (deeplink cheat sheet).
 */
package com.flashcardseverywhere.util

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.getSystemService

object PermissionChecks {

    // ── Notifications (POST_NOTIFICATIONS, API 33+) ────────────────────────
    fun hasNotificationPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true

    fun openAppNotificationSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }

    // ── Full-screen intent (USE_FULL_SCREEN_INTENT, restricted on API 34+) ─
    fun canUseFullScreenIntent(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return ctx.getSystemService<NotificationManager>()?.canUseFullScreenIntent() == true
    }

    fun openFullScreenIntentSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val i = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }

    // ── Usage access (PACKAGE_USAGE_STATS) ─────────────────────────────────
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService<AppOpsManager>() ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }

    // ── System overlay (SYSTEM_ALERT_WINDOW) ───────────────────────────────
    fun canDrawOverlays(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun openOverlaySettings(ctx: Context) {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ctx.startActivity(i)
    }

    // ── Accessibility service (full flavor only) ───────────────────────────
    fun isAccessibilityServiceEnabled(ctx: Context, serviceClass: Class<*>): Boolean {
        val expected = "${ctx.packageName}/${serviceClass.canonicalName}"
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    fun openAccessibilitySettings(ctx: Context) {
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }

    // ── Battery optimisation (so the foreground service isn't killed) ──────
    fun openBatteryOptimisationSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}
