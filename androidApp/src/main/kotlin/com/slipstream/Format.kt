package com.slipstream

import java.util.Locale

fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1L shl 40 -> String.format(Locale.US, "%.1f TB", bytes / (1L shl 40).toDouble())
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

fun formatSpeed(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

fun formatEta(seconds: Long): String =
    when {
        seconds < 0 -> "--:--"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m${seconds % 60}s"
        else -> "${seconds / 3600}h${seconds % 3600 / 60}m"
    }
