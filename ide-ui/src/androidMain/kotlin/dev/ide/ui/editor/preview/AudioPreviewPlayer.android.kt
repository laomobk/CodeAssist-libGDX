package dev.ide.ui.editor.preview

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberAudioPreviewPlayer(): AudioPreviewPlayer {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidAudioPreviewPlayer(context) }
}

private class AndroidAudioPreviewPlayer(private val context: Context) : AudioPreviewPlayer {
    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    override val durationMillis: Long get() = runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L)
    override val positionMillis: Long get() = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    override val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    override suspend fun load(bytes: ByteArray, extension: String): String? = withContext(Dispatchers.IO) {
        release()
        runCatching {
            val file = File.createTempFile("asset-preview-", ".${extension.ifBlank { "audio" }}", context.cacheDir)
            file.writeBytes(bytes)
            tempFile = file
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
        }.exceptionOrNull()?.let { it.message ?: "Unsupported or damaged audio file" }
    }

    override fun play() { runCatching { player?.start() } }
    override fun pause() { runCatching { player?.pause() } }
    override fun seekTo(positionMillis: Long) {
        runCatching { player?.seekTo(positionMillis.coerceIn(0L, durationMillis).toInt()) }
    }

    override fun release() {
        runCatching { player?.release() }
        player = null
        tempFile?.delete()
        tempFile = null
    }
}
