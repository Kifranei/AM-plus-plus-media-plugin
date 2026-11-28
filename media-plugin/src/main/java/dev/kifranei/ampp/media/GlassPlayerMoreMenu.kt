package dev.kifranei.ampp.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/**
 * Floating material below the native-backed iOS menu controls.
 * Uses the exact same popup glass material as the audio-quality card so the
 * player menu, quality dialog, sleep timer and delete confirmation all share
 * one liquid-glass look (and one host renderer path).
 */
@Composable
fun GlassPlayerMoreMenu(backdrop: Backdrop, dark: Boolean) {
    val shape = RoundedCornerShape(32.dp)
    Box(Modifier.fillMaxSize().popupGlass(backdrop, shape))
}

