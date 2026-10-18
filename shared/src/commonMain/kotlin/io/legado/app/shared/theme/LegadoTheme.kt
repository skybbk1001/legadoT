package io.legado.app.shared.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun LegadoTheme(
    isDark: Boolean,
    accentColor: Color? = null,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = colorScheme(isDark, accentColor),
        content = content
    )
}

private fun colorScheme(isDark: Boolean, accentColor: Color?): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return if (accentColor == null) base else base.copy(primary = accentColor)
}
