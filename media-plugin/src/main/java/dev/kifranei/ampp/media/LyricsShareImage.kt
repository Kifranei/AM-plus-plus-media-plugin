package dev.kifranei.ampp.media

import android.app.Activity
import android.net.Uri
import java.io.File

/** The two image layers produced by Apple Music's native story share renderer. */
data class LyricsShareImage(
    val activity: Activity,
    val title: String,
    val cardUri: Uri,
    val backgroundUri: Uri,
    val shareUri: (File) -> Uri,
)
