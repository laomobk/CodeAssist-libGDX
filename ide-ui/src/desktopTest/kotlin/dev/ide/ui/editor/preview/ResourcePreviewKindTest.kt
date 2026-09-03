package dev.ide.ui.editor.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResourcePreviewKindTest {

    @Test
    fun libGdxAssetsSupportImageAndAudioPreview() {
        assertEquals(PreviewKind.BITMAP, previewKindOf("C:\\game\\assets\\textures\\hero.PNG"))
        assertEquals(PreviewKind.BITMAP, previewKindOf("/game/assets/splash.webp"))
        assertEquals(PreviewKind.AUDIO, previewKindOf("/game/assets/audio/theme.mp3"))
        assertEquals(PreviewKind.AUDIO, previewKindOf("assets/sfx/hit.OGG"))
        assertEquals(PreviewKind.AUDIO, previewKindOf("assets/sfx/hit.wav"))
    }

    @Test
    fun audioOutsideAssetsDoesNotStealTheCodeEditor() {
        assertNull(previewKindOf("/game/core/src/main/resources/theme.mp3"))
        assertNull(previewKindOf("/downloads/theme.ogg"))
    }
}
