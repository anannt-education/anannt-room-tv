package io.anannt.roomtv.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object PhoneColors {
    val Navy = Color(0xFF070B1E)
    val Panel = Color(0xFF111A3A)
    val Blue = Color(0xFF3B82F6)
    val Gold = Color(0xFFD4A62A)
    val Ink = Color(0xFFF4F6FB)
}

@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PhoneColors.Blue,
            onPrimary = Color.White,
            secondary = PhoneColors.Gold,
            background = PhoneColors.Navy,
            onBackground = PhoneColors.Ink,
            surface = PhoneColors.Panel,
            onSurface = PhoneColors.Ink,
            surfaceVariant = PhoneColors.Panel,
            onSurfaceVariant = PhoneColors.Ink,
        ),
        content = content,
    )
}
