package com.slipstream

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 0 = system, 1 = light, 2 = dark. Dynamic color always (minSdk 34 ≥ API 31). */
@Composable
fun SlipstreamTheme(
    themeMode: Int = 0,
    content: @Composable () -> Unit,
) {
    val dark =
        when (themeMode) {
            1 -> false
            2 -> true
            else -> isSystemInDarkTheme()
        }
    val scheme =
        if (dark) {
            dynamicDarkColorScheme(LocalContext.current)
        } else {
            dynamicLightColorScheme(LocalContext.current)
        }
    MaterialTheme(colorScheme = scheme, content = content)
}
