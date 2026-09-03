package dev.ide.ui.editor.preview

import androidx.compose.runtime.Composable

internal interface AudioPreviewPlayer {
    val durationMillis: Long
    val positionMillis: Long
    val isPlaying: Boolean

    suspend fun load(bytes: ByteArray, extension: String): String?
    fun play()
    fun pause()
    fun seekTo(positionMillis: Long)
    fun release()
}

@Composable
internal expect fun rememberAudioPreviewPlayer(): AudioPreviewPlayer
