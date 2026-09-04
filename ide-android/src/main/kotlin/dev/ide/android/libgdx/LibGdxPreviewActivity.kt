package dev.ide.android.libgdx

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toolbar
import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.badlogic.gdx.backends.android.AndroidFiles
import com.badlogic.gdx.backends.android.DefaultAndroidFiles
import com.badlogic.gdx.files.FileHandle
import dev.ide.android.DexPeerFactory
import dev.ide.core.LibGdxPreviewOrientation
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.ReflectiveBridge
import dev.ide.jvm.Vm
import dev.ide.jvm.hasInterpretedClass
import dev.ide.jvm.interpretedConstructors
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import com.badlogic.gdx.Files as GdxFiles

/** Isolated Activity that runs compiled project bytecode against the IDE's real libGDX Android backend. */
class LibGdxPreviewActivity : AndroidApplication() {
    private var runtime: LibGdxVmRuntime? = null
    private var projectAssetsRoot: File? = null
    private var previewToolbar: Toolbar? = null
    private var exitDialog: AlertDialog? = null
    private var immersive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = when (previewOrientation()) {
            LibGdxPreviewOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            LibGdxPreviewOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        super.onCreate(savedInstanceState)
        runCatching {
            val classpath = intent.getStringArrayListExtra(EXTRA_CLASSPATH).orEmpty().map(Paths::get)
            val mainClass = intent.getStringExtra(EXTRA_MAIN_CLASS)
                ?: error("Preview entry class was not provided")
            val assetsDir = intent.getStringExtra(EXTRA_ASSETS_DIR)?.let(::File)
                ?: error("Preview assets directory was not provided")
            require(classpath.isNotEmpty()) { "Preview classpath is empty" }
            projectAssetsRoot = assetsDir

            val vmRuntime = LibGdxVmRuntime(classpath, cacheDir.toPath().resolve("libgdx-peers"))
            runtime = vmRuntime
            val listener = vmRuntime.newListener(mainClass)
            val gameView = initializeForView(listener, AndroidApplicationConfiguration())
            val gameName = intent.getStringExtra(EXTRA_GAME_NAME)?.trim().orEmpty().ifEmpty { "libGDX Preview" }
            title = gameName
            if (intent.getBooleanExtra(EXTRA_SHOW_TITLE_BAR, true)) {
                setContentView(createPreviewLayout(gameName, gameView))
            } else {
                setContentView(gameView)
            }
        }.onFailure(::showStartupError)
    }

    private fun previewOrientation(): LibGdxPreviewOrientation = runCatching {
        LibGdxPreviewOrientation.valueOf(
            intent.getStringExtra(EXTRA_ORIENTATION) ?: LibGdxPreviewOrientation.LANDSCAPE.name
        )
    }.getOrDefault(LibGdxPreviewOrientation.LANDSCAPE)

    private fun createPreviewLayout(gameName: String, gameView: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(createToolbar(gameName), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56),
            ))
            addView(gameView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }

    private fun createToolbar(gameName: String): Toolbar = Toolbar(this).apply {
        previewToolbar = this
        title = gameName
        setTitleTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(28, 30, 34))
        elevation = dp(4).toFloat()
        setNavigationIcon(android.R.drawable.ic_menu_revert)
        navigationContentDescription = "Back"
        setNavigationOnClickListener { showExitConfirmation() }
        menu.add(0, MENU_ENTER_FULLSCREEN, 0, "Enter fullscreen").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        setOnMenuItemClickListener { item ->
            if (item.itemId == MENU_ENTER_FULLSCREEN) {
                enterFullscreen()
                true
            } else false
        }
    }

    private fun enterFullscreen() {
        immersive = true
        previewToolbar?.visibility = View.GONE
        applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersive) applyImmersiveMode()
    }

    @Deprecated("Android routes system Back here for this Activity")
    override fun onBackPressed() {
        showExitConfirmation()
    }

    private fun showExitConfirmation() {
        if (exitDialog?.isShowing == true || isFinishing) return
        exitDialog = AlertDialog.Builder(this)
            .setTitle("Exit preview?")
            .setMessage("The game preview will stop and return to the editor.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Exit") { _, _ -> finish() }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { exitDialog = null }
                dialog.show()
            }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** libGDX assigns Gdx.files again during resume, so install the project-backed implementation at creation. */
    override fun createFiles(): AndroidFiles {
        val root = projectAssetsRoot ?: error("Preview assets directory was not provided")
        return object : DefaultAndroidFiles(assets, this@LibGdxPreviewActivity, true) {
            override fun getFileHandle(path: String, type: com.badlogic.gdx.Files.FileType): FileHandle =
                if (type == com.badlogic.gdx.Files.FileType.Internal) internal(path)
                else super.getFileHandle(path, type)

            override fun internal(path: String): FileHandle = ProjectFileHandle(File(root, path))
        }
    }

    override fun onDestroy() {
        exitDialog?.dismiss()
        exitDialog = null
        runtime?.close()
        runtime = null
        super.onDestroy()
    }

    private fun showStartupError(error: Throwable) {
        val message = buildString {
            append("libGDX preview could not start\n\n")
            append(error.message ?: error.javaClass.simpleName)
        }
        setContentView(TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(28, 30, 34))
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            textSize = 16f
        })
    }

    private companion object {
        const val MENU_ENTER_FULLSCREEN = 1
    }
}

private class ProjectFileHandle(file: File) : FileHandle(file, GdxFiles.FileType.Absolute)

private class LibGdxVmRuntime(classpath: List<Path>, peerCache: Path) : AutoCloseable {
    private val source = ClasspathBytes(classpath)
    private val hostLoadable = ConcurrentHashMap<String, Boolean>()
    private val vm = Vm(
        source = source,
        policy = InterpretPolicy { internalName -> !isHostLoadable(internalName) },
        bridge = ReflectiveBridge(),
        peerFactory = DexPeerFactory(peerCache),
    )

    fun newListener(mainClass: String): ApplicationListener {
        require(vm.hasInterpretedClass(mainClass)) { "$mainClass was not found in the compiled output" }
        val constructor = vm.interpretedConstructors(mainClass)
            .firstOrNull { it.paramDescriptors.isEmpty() }
            ?: error("$mainClass needs a no-argument constructor")
        return constructor.invoke(null, emptyList()) as? ApplicationListener
            ?: error("$mainClass must implement ApplicationListener or extend ApplicationAdapter")
    }

    private fun isHostLoadable(internalName: String): Boolean = hostLoadable.getOrPut(internalName) {
        runCatching {
            Class.forName(internalName.replace('/', '.'), false, javaClass.classLoader)
        }.isSuccess
    }

    override fun close() {
        vm.requestCancel()
        source.close()
    }
}

private class ClasspathBytes(classpath: List<Path>) : ClassBytesSource, AutoCloseable {
    private val directories = classpath.filter(Files::isDirectory)
    private val jars = classpath.filter(Files::isRegularFile)
        .filter { it.fileName.toString().endsWith(".jar") }
        .mapNotNull { runCatching { JarFile(it.toFile()) }.getOrNull() }

    override fun bytesFor(internalName: String): ByteArray? =
        directories.firstNotNullOfOrNull { dir ->
            dir.resolve("$internalName.class").takeIf(Files::isRegularFile)
                ?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }
        } ?: jars.firstNotNullOfOrNull { jar ->
            jar.getJarEntry("$internalName.class")?.let { entry ->
                jar.getInputStream(entry).use { it.readBytes() }
            }
        }

    override fun close() = jars.forEach { runCatching { it.close() } }.let { Unit }
}
