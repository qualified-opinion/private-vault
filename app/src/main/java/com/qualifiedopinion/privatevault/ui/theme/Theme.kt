package com.qualifiedopinion.privatevault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Teal80,
    background = Slate10,
    surface = Slate10,
    surfaceVariant = Slate30,
)

private val LightColors = lightColorScheme(
    primary = Teal40,
    surfaceVariant = Slate90,
)

@Composable
fun PrivateVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PrivateVaultTypography,
        content = content,
    )
}
