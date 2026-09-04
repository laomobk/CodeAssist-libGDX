package dev.ide.core.completion

import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet

/** Completion for the small, documented configuration surface of a libGDX preview project. */
object LibGdxPropertiesCompletion : CompletionContributor {
    override val id = "libgdx.properties"

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val fileName = params.document.file.path.replace('\\', '/').substringAfterLast('/')
        if (fileName != "libgdx.properties") return

        val text = params.document.text.toString()
        val lineStart = text.lastIndexOf('\n', (params.offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val head = text.substring(lineStart, params.offset)
        if (head.trimStart().startsWith('#') || head.trimStart().startsWith('!')) return

        val separator = head.indexOfFirst { it == '=' || it == ':' }
        if (separator < 0) {
            KEYS.forEach { (key, detail) ->
                if (params.prefixMatches(key)) {
                    result.addElement(
                        CompletionItem(key, "$key=", CompletionItemKind.FIELD, detail = detail),
                    )
                }
            }
            return
        }

        val key = head.substring(0, separator).trim()
        when (key) {
            "previewOrientation" -> VALUES_ORIENTATION.forEach { (value, detail) ->
                addValue(params, result, value, detail)
            }
            "previewShowTitleBar" -> VALUES_BOOLEAN.forEach { (value, detail) ->
                addValue(params, result, value, detail)
            }
            else -> Unit
        }
    }

    private fun addValue(params: CompletionParams, result: CompletionResultSet, value: String, detail: String) {
        if (params.prefixMatches(value)) {
            result.addElement(CompletionItem(value, value, CompletionItemKind.ENUM_CONSTANT, detail = detail))
        }
    }

    private val KEYS = linkedMapOf(
        "mainClass" to "fully-qualified ApplicationListener entry class",
        "gameName" to "title shown by the preview Activity",
        "previewOrientation" to "landscape or portrait preview",
        "previewShowTitleBar" to "show the preview title bar by default",
    )

    private val VALUES_ORIENTATION = listOf(
        "landscape" to "two-way landscape sensor orientation",
        "portrait" to "portrait orientation",
    )

    private val VALUES_BOOLEAN = listOf(
        "true" to "enabled",
        "false" to "disabled",
    )
}
