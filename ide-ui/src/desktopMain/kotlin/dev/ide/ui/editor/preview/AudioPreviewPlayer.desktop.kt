package dev.ide.ui.editor.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberAudioPreviewPlayer(): AudioPreviewPlayer = remember { DesktopAudioPreviewPlayer() }

private class DesktopAudioPreviewPlayer : AudioPreviewPlayer {
    private var clip: Clip? = null

    override val durationMillis: Long get() = (clip?.microsecondLength ?: 0L) / 1_000L
    override val positionMillis: Long get() = (clip?.microsecondPosition ?: 0L) / 1_000L
    override val isPlaying: Boolean get() = clip?.isRunning == true

    override suspend fun load(bytes: ByteArray, extension: String): String? = withContext(Dispatchers.IO) {
        release()
        runCatching {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { stream ->
                clip = AudioSystem.getClip().apply { open(stream) }
            }
        }.exceptionOrNull()?.let {
            "This desktop runtime cannot decode .$extension audio"
        }
    }

    override fun play() {
        clip?.let { if (it.microsecondPosition >= it.microsecondLength) it.microsecondPosition = 0L; it.start() }
    }
    override fun pause() { clip?.stop() }
    override fun seekTo(positionMillis: Long) {
        clip?.microsecondPosition = positionMillis.coerceIn(0L, durationMillis) * 1_000L
    }
    override fun release() {
        clip?.stop()
        clip?.close()
        clip = null
    }
}
