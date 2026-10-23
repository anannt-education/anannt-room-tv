package io.freemytv.room

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Navy field, blue for what's focused, gold only as a small accent. */
object RoomColors {
    val Navy = Color(0xFF070B1E)
    val Panel = Color(0xFF111A3A)
    val Blue = Color(0xFF3B82F6)
    val Gold = Color(0xFFD4A62A)
    val Ink = Color(0xFFF4F6FB)
    val Muted = Color(0xB3F4F6FB)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RoomColors.Blue,
            onPrimary = Color.White,
            secondary = RoomColors.Gold,
            background = RoomColors.Navy,
            onBackground = RoomColors.Ink,
            surface = RoomColors.Panel,
            onSurface = RoomColors.Ink,
        ),
        content = content,
    )
}
