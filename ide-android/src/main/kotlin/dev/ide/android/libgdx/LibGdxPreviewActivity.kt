package dev.ide.android.libgdx

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.badlogic.gdx.backends.android.AndroidFiles
import com.badlogic.gdx.backends.android.DefaultAndroidFiles
import com.badlogic.gdx.files.FileHandle
import dev.ide.android.DexPeerFactory
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

    override fun onCreate(savedInstanceState: Bundle?) {
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
            initialize(listener, AndroidApplicationConfiguration())
        }.onFailure(::showStartupError)
    }

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
