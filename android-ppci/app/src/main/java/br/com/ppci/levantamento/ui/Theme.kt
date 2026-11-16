package br.com.ppci.levantamento.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(primary = Color(0xFFD9500B), onPrimary = Color.White, background = Color(0xFFEEF0F3), surface = Color.White)
private val Dark = darkColorScheme(primary = Color(0xFFFF7A33), onPrimary = Color(0xFF1A0B00), background = Color(0xFF0E1319), surface = Color(0xFF161D26))

@Composable
fun PpciTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
