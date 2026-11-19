package com.slipstream

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 0 = system, 1 = light, 2 = dark. Dynamic color on API 31+. */
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
        when {
            Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(LocalContext.current)
            Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(LocalContext.current)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
    MaterialTheme(colorScheme = scheme, content = content)
}
