/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 */
package com.flashcardseverywhere.data.anki

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat

object AnkiPermissionHelper {
    const val REQUEST_CODE = 0x4E4B  // "NK"

    fun request(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(FlashCardsContract.READ_WRITE_PERMISSION),
            REQUEST_CODE,
        )
    }

    /**
     * Compose-friendly registration. Use from a ComponentActivity / Fragment with
     * `registerForActivityResult(...)` and pass the launcher down to UI code.
     */
    fun registerLauncher(
        host: androidx.activity.result.ActivityResultCaller,
        onResult: (Boolean) -> Unit,
    ): ActivityResultLauncher<String> = host.registerForActivityResult(
        ActivityResultContracts.RequestPermission(), onResult
    )
}
