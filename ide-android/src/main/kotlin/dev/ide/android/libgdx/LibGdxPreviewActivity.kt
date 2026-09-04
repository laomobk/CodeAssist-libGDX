package dev.ide.android.libgdx

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarFile
import com.badlogic.gdx.Files as GdxFiles

/** Isolated Activity that runs compiled project bytecode against the IDE's real libGDX Android backend. */
class LibGdxPreviewActivity : AndroidApplication() {
    private var runtime: LibGdxVmRuntime? = null
    private var projectAssetsRoot: File? = null
    private var previewMenuButton: ImageButton? = null
    private var previewRoot: FrameLayout? = null
    private var menuPanel: LinearLayout? = null
    private var consolePanel: View? = null
    private var consoleOutput: TextView? = null
    private var consoleScroll: ScrollView? = null
    private var consoleInput: EditText? = null
    private var consoleTranscript = StringBuilder()
    private val consoleInputStream = PreviewInputStream()
    private var originalIn: InputStream? = null
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var consoleFraction = 0.38f
    private val consoleUiPending = AtomicBoolean(false)
    private var consoleRevision = 0L
    private var consoleTrimGeneration = 0L
    private var renderedConsoleLength = 0
    private var renderedTrimGeneration = 0L
    private var menuButtonMoved = false
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
            installPreviewStreams()
            setContentView(createPreviewLayout(gameName, gameView))
        }.onFailure(::showStartupError)
    }

    private fun previewOrientation(): LibGdxPreviewOrientation = runCatching {
        LibGdxPreviewOrientation.valueOf(
            intent.getStringExtra(EXTRA_ORIENTATION) ?: LibGdxPreviewOrientation.LANDSCAPE.name
        )
    }.getOrDefault(LibGdxPreviewOrientation.LANDSCAPE)

    private fun createPreviewLayout(gameName: String, gameView: View): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewRoot = root
        root.addView(gameView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val menuButton = ImageButton(this).apply {
            previewMenuButton = this
            setImageResource(android.R.drawable.ic_menu_more)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.rgb(28, 30, 34))
            contentDescription = "Preview menu"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            elevation = dp(8).toFloat()
            setOnClickListener {
                menuPanel?.visibility = if (menuPanel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (menuPanel?.visibility == View.VISIBLE) positionMenuPanel()
            }
        }
        root.addView(menuButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
        installMenuButtonDrag(menuButton, root)

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.rgb(28, 30, 34))
            elevation = dp(10).toFloat()
        }
        menuPanel = menu
        menu.addView(TextView(this).apply {
            text = gameName
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(14), dp(12), dp(14), dp(12))
        })
        menu.addView(menuItem("Console output") { openConsole() })
        menu.addView(menuItem("Enter fullscreen") { enterFullscreen(); menu.visibility = View.GONE })
        menu.addView(menuItem("Set default position") { setDefaultMenuButtonPosition(); menu.visibility = View.GONE })
        menu.addView(menuItem("Exit") { showExitConfirmation() })
        root.addView(menu, FrameLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(64)
            leftMargin = dp(12)
        })
        // A floating menu is transient UI: always enter the preview with it collapsed. Mapping the old
        // title-bar preference to VISIBLE made Android restore/show the panel as soon as the Activity opened.
        menu.visibility = View.GONE
        root.post { restoreMenuButtonPosition(root, menuButton) }
        return root
    }

    private fun installMenuButtonDrag(button: ImageButton, root: FrameLayout) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        button.setOnTouchListener { view, event ->
            val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    menuButtonMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!menuButtonMoved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) menuButtonMoved = true
                    if (menuButtonMoved) {
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.leftMargin = (startLeft + dx.toInt()).coerceIn(0, (root.width - view.width).coerceAtLeast(0))
                        lp.topMargin = (startTop + dy.toInt()).coerceIn(0, (root.height - view.height).coerceAtLeast(0))
                        view.layoutParams = lp
                        if (menuPanel?.visibility == View.VISIBLE) positionMenuPanel()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (menuButtonMoved) {
                        saveMenuButtonPosition(lp.leftMargin, lp.topMargin)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun restoreMenuButtonPosition(root: FrameLayout, button: ImageButton) {
        val prefs = getSharedPreferences(PREVIEW_PREFS, MODE_PRIVATE)
        val savedX = prefs.getInt(KEY_MENU_X, Int.MIN_VALUE)
        val savedY = prefs.getInt(KEY_MENU_Y, Int.MIN_VALUE)
        val lp = button.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = if (savedX == Int.MIN_VALUE) (root.width - button.width - dp(12)).coerceAtLeast(0) else savedX
        lp.topMargin = if (savedY == Int.MIN_VALUE) dp(12) else savedY
        lp.leftMargin = lp.leftMargin.coerceIn(0, (root.width - button.width).coerceAtLeast(0))
        lp.topMargin = lp.topMargin.coerceIn(0, (root.height - button.height).coerceAtLeast(0))
        button.layoutParams = lp
        if (menuPanel?.visibility == View.VISIBLE) positionMenuPanel()
    }

    private fun saveMenuButtonPosition(x: Int, y: Int) {
        getSharedPreferences(PREVIEW_PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_MENU_X, x)
            .putInt(KEY_MENU_Y, y)
            .apply()
    }

    private fun setDefaultMenuButtonPosition() {
        getSharedPreferences(PREVIEW_PREFS, MODE_PRIVATE).edit()
            .remove(KEY_MENU_X)
            .remove(KEY_MENU_Y)
            .apply()
        val root = previewRoot ?: return
        val button = previewMenuButton ?: return
        restoreMenuButtonPosition(root, button)
    }

    private fun positionMenuPanel() {
        val root = previewRoot ?: return
        val button = previewMenuButton ?: return
        val menu = menuPanel ?: return
        val buttonLp = button.layoutParams as? FrameLayout.LayoutParams ?: return
        val menuLp = menu.layoutParams as? FrameLayout.LayoutParams ?: return
        menuLp.gravity = Gravity.TOP or Gravity.START
        menuLp.leftMargin = buttonLp.leftMargin.coerceIn(0, (root.width - dp(240)).coerceAtLeast(0))
        menuLp.topMargin = (buttonLp.topMargin + button.height + dp(8)).coerceAtMost((root.height - dp(220)).coerceAtLeast(0))
        menu.layoutParams = menuLp
    }

    private fun menuItem(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
    }

    private fun openConsole() {
        menuPanel?.visibility = View.GONE
        if (consolePanel != null) {
            consolePanel?.visibility = View.VISIBLE
            scheduleConsoleUiUpdate()
            focusConsoleInput()
            return
        }
        val panel = createConsolePanel()
        consolePanel = panel
        previewRoot?.addView(panel)
        focusConsoleInput()
    }

    private fun focusConsoleInput() {
        val input = consoleInput ?: return
        input.requestFocus()
        input.post {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun createConsolePanel(): View {
        val landscape = previewOrientation() == LibGdxPreviewOrientation.LANDSCAPE
        val panel = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(24, 25, 28))
            elevation = dp(12).toFloat()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Console"
            setTextColor(Color.WHITE)
            textSize = 16f
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        header.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Close console"
            setOnClickListener { closeConsole() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        content.addView(header)

        val scroll = ScrollView(this)
        consoleScroll = scroll
        val initialOutput = synchronized(consoleTranscript) {
            renderedConsoleLength = consoleTranscript.length
            renderedTrimGeneration = consoleTrimGeneration
            consoleTranscript.toString()
        }
        val output = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            text = initialOutput
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        consoleOutput = output
        scroll.addView(output, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val input = EditText(this).apply {
            consoleInput = this
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Input"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // Keep landscape keyboards in the console itself instead of Android's full-screen extract editor.
            imeOptions = EditorInfo.IME_ACTION_SEND or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
            setSingleLine(true)
            maxLines = 1
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)) {
                    submitConsoleInput()
                    true
                } else false
            }
        }
        content.addView(input, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(content, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val handle = View(this).apply {
            setBackgroundColor(Color.rgb(110, 112, 118))
            setOnTouchListener(ConsoleResizeTouchListener(landscape))
        }
        val handleParams = if (landscape) {
            FrameLayout.LayoutParams(dp(12), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        } else {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12), Gravity.BOTTOM)
        }
        panel.addView(handle, handleParams)
        val params = if (landscape) {
            FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * consoleFraction).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
        } else {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * consoleFraction).toInt(), Gravity.TOP)
        }
        panel.layoutParams = params
        return panel
    }

    private fun closeConsole() {
        consolePanel?.visibility = View.GONE
        consoleInput?.clearFocus()
    }

    private fun submitConsoleInput() {
        val line = consoleInput?.text?.toString().orEmpty()
        if (line.isEmpty()) return
        appendConsole("> $line\\n")
        consoleInputStream.offer(line)
        consoleInput?.text?.clear()
    }

    private fun appendConsole(text: String) {
        if (text.isEmpty()) return
        synchronized(consoleTranscript) {
            consoleTranscript.append(text)
            consoleRevision++
            if (consoleTranscript.length > MAX_CONSOLE_CHARS) {
                consoleTranscript.delete(0, consoleTranscript.length - CONSOLE_TRIM_TO_CHARS)
                consoleTrimGeneration++
            }
        }
        scheduleConsoleUiUpdate()
    }

    /** Coalesce high-frequency output and append only the new suffix. Never use focus-based scrolling here:
     * ScrollView.fullScroll(FOCUS_DOWN) steals focus from the console EditText while the game is printing. */
    private fun scheduleConsoleUiUpdate() {
        if (consolePanel?.visibility != View.VISIBLE || !consoleUiPending.compareAndSet(false, true)) return
        runOnUiThread {
            consoleOutput?.postDelayed({
                val revision: Long
                val update: String
                val replace: Boolean
                val renderedLength: Int
                val trimGeneration: Long
                synchronized(consoleTranscript) {
                    revision = consoleRevision
                    trimGeneration = consoleTrimGeneration
                    replace = renderedTrimGeneration != trimGeneration || renderedConsoleLength > consoleTranscript.length
                    update = if (replace) consoleTranscript.toString()
                        else consoleTranscript.substring(renderedConsoleLength)
                    renderedLength = consoleTranscript.length
                }
                val output = consoleOutput
                if (replace) output?.text = update else if (update.isNotEmpty()) output?.append(update)
                renderedConsoleLength = renderedLength
                renderedTrimGeneration = trimGeneration
                val scroll = consoleScroll
                scroll?.post {
                    val bottom = ((output?.height ?: 0) - scroll.height).coerceAtLeast(0)
                    scroll.scrollTo(0, bottom)
                }
                consoleUiPending.set(false)
                synchronized(consoleTranscript) {
                    if (consoleRevision != revision) scheduleConsoleUiUpdate()
                }
            }, CONSOLE_UPDATE_INTERVAL_MS)
        }
    }

    private fun installPreviewStreams() {
        originalIn = System.`in`
        originalOut = System.out
        originalErr = System.err
        val out = ConsoleOutputStream(::appendConsole)
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(out, true))
        System.setIn(consoleInputStream)
    }

    private fun restorePreviewStreams() {
        originalIn?.let(System::setIn)
        originalOut?.let(System::setOut)
        originalErr?.let(System::setErr)
        originalIn = null
        originalOut = null
        originalErr = null
    }

    private inner class ConsoleResizeTouchListener(private val landscape: Boolean) : View.OnTouchListener {
        private var start = 0f
        private var initialFraction = 0.38f
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    start = if (landscape) event.rawX else event.rawY
                    initialFraction = consoleFraction
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (if (landscape) start - event.rawX else event.rawY - start)
                    val size = if (landscape) resources.displayMetrics.widthPixels else resources.displayMetrics.heightPixels
                    consoleFraction = (initialFraction + delta / size).coerceIn(0.2f, 0.8f)
                    val lp = consolePanel?.layoutParams as? FrameLayout.LayoutParams ?: return true
                    if (landscape) lp.width = (size * consoleFraction).toInt() else lp.height = (size * consoleFraction).toInt()
                    consolePanel?.layoutParams = lp
                    return true
                }
            }
            return true
        }
    }

    private fun enterFullscreen() {
        immersive = true
        previewMenuButton?.visibility = View.GONE
        menuPanel?.visibility = View.GONE
        applyImmersiveMode()
    }

    private fun exitFullscreen() {
        immersive = false
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        previewMenuButton?.visibility = View.VISIBLE
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
        if (immersive) exitFullscreen() else showExitConfirmation()
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
        restorePreviewStreams()
        consoleInputStream.closeInput()
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
        const val MAX_CONSOLE_CHARS = 200_000
        const val CONSOLE_TRIM_TO_CHARS = 150_000
        const val CONSOLE_UPDATE_INTERVAL_MS = 50L
        const val PREVIEW_PREFS = "libgdx_preview_preferences"
        const val KEY_MENU_X = "menu_button_x"
        const val KEY_MENU_Y = "menu_button_y"
    }
}

/** A line-oriented output stream used to mirror the user's game stdout/stderr into the preview console. */
private class ConsoleOutputStream(private val onText: (String) -> Unit) : ByteArrayOutputStream() {
    override fun flush() {
        synchronized(this) {
            if (count == 0) return
            onText(String(buf, 0, count, StandardCharsets.UTF_8))
            reset()
        }
    }
}

/** Blocking stdin for preview games. Lines submitted by the console are made available to System.in. */
private class PreviewInputStream : InputStream() {
    private val bytes = LinkedBlockingQueue<Int>()

    override fun read(): Int = bytes.take()

    fun offer(line: String) {
        line.toByteArray(StandardCharsets.UTF_8).forEach { bytes.offer(it.toInt() and 0xff) }
        bytes.offer('\n'.code)
    }

    fun closeInput() {
        bytes.offer(-1)
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
