package com.kiosk.launcher.data

import android.graphics.drawable.Drawable

/**
 * Model representing an installed application on the tablet.
 */
data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val isWhitelisted: Boolean,
    val isSystemApp: Boolean,
    val versionName: String = ""
)
