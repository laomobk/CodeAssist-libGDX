package dev.ide.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {
    @Test
    fun lineNumbersDefaultOnAndPersistWhenDisabled() {
        val prefs = mutableMapOf<String, String>()
        val store = SettingsStore(
            get = prefs::get,
            set = { key, value -> prefs[key] = value },
        )

        val defaults = store.load()
        assertFalse(defaults.showEditorTabs)
        assertFalse(defaults.showBreadcrumbBar)
        assertTrue(defaults.showLineNumbers)
        assertEquals(IdeSettings.DEFAULT_GUTTER_WIDTH_DP, defaults.gutterWidthDp)

        store.save(IdeSettings(
            showEditorTabs = true,
            showBreadcrumbBar = true,
            showLineNumbers = false,
            gutterWidthDp = 52,
        ))

        assertEquals("true", prefs["settings.editor.showEditorTabs"])
        assertEquals("true", prefs["settings.editor.showBreadcrumbBar"])
        assertEquals("false", prefs["settings.editor.showLineNumbers"])
        assertEquals("52", prefs["settings.editor.gutterWidthDp"])
        val loaded = store.load()
        assertTrue(loaded.showEditorTabs)
        assertTrue(loaded.showBreadcrumbBar)
        assertFalse(loaded.showLineNumbers)
        assertEquals(52, loaded.gutterWidthDp)
    }
}
