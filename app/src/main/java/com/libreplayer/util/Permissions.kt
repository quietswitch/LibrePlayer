package com.libreplayer.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

fun audioReadPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun hasAudioReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        audioReadPermission(),
    ) == PackageManager.PERMISSION_GRANTED

enum class AudioPermissionAction {
    REQUEST,
    OPEN_SETTINGS,
}

fun resolveAudioPermissionAction(
    hasPermission: Boolean,
    deniedRequestCount: Int,
    shouldShowRationale: Boolean,
): AudioPermissionAction? =
    when {
        hasPermission -> null
        deniedRequestCount <= 0 -> AudioPermissionAction.REQUEST
        shouldShowRationale -> AudioPermissionAction.REQUEST
        deniedRequestCount == 1 -> AudioPermissionAction.REQUEST
        else -> AudioPermissionAction.OPEN_SETTINGS
    }

fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
