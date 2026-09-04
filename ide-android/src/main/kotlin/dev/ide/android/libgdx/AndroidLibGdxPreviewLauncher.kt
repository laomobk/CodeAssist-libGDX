package dev.ide.android.libgdx

import android.content.Context
import android.content.Intent
import dev.ide.core.LibGdxPreviewLauncher
import dev.ide.core.LibGdxPreviewRequest

class AndroidLibGdxPreviewLauncher(private val context: Context) : LibGdxPreviewLauncher {
    override suspend fun launch(request: LibGdxPreviewRequest) {
        val intent = Intent(context, LibGdxPreviewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putStringArrayListExtra(EXTRA_CLASSPATH, ArrayList(request.classpath.map { it.toString() }))
            putExtra(EXTRA_MAIN_CLASS, request.mainClass)
            putExtra(EXTRA_ASSETS_DIR, request.assetsDir.toString())
            putExtra(EXTRA_GAME_NAME, request.gameName)
            putExtra(EXTRA_ORIENTATION, request.orientation.name)
            putExtra(EXTRA_SHOW_TITLE_BAR, request.showTitleBar)
        }
        context.startActivity(intent)
    }
}

internal const val EXTRA_CLASSPATH = "dev.ide.libgdx.classpath"
internal const val EXTRA_MAIN_CLASS = "dev.ide.libgdx.mainClass"
internal const val EXTRA_ASSETS_DIR = "dev.ide.libgdx.assetsDir"
internal const val EXTRA_GAME_NAME = "dev.ide.libgdx.gameName"
internal const val EXTRA_ORIENTATION = "dev.ide.libgdx.orientation"
internal const val EXTRA_SHOW_TITLE_BAR = "dev.ide.libgdx.showTitleBar"
