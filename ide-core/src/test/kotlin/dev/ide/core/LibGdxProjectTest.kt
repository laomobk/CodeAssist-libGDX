package dev.ide.core

import com.badlogic.gdx.ApplicationAdapter
import dev.ide.core.templates.LibGdxProjectTemplate
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.complete
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.java.JavaLanguageBackend
import dev.ide.lang.java.JavaSourceAnalyzer
import dev.ide.lang.java.completion.JavaCompletion
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.model.ContentRole
import dev.ide.model.LibraryDependency
import dev.ide.model.template.TemplateArgs
import dev.ide.testkit.TestDocument
import dev.ide.testkit.withTempDir
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibGdxProjectTest {

    @Test
    fun templateCreatesCoreAssetsShadersAndDeclaredGdxDependency() = withTempDir("ide-libgdx-template") { root ->
        val manager = ProjectManager.desktop(root.resolve("projects"))
        try {
            manager.create(
                "libgdx",
                mapOf(
                    TemplateArgs.NAME to "Orbit",
                    TemplateArgs.PACKAGE to "com.example.orbit",
                    LibGdxProjectTemplate.GAME_NAME to "轨道跑者",
                    LibGdxProjectTemplate.PREVIEW_ORIENTATION to "portrait",
                    LibGdxProjectTemplate.PREVIEW_SHOW_TITLE_BAR to "false",
                ),
            ).use { ide ->
                val module = ide.modules().single()
                assertEquals("core", module.name)
                assertEquals("java-libgdx", module.type.id)

                val projectRoot = Path.of(manager.list().single().rootPath)
                val main = projectRoot.resolve("core/src/main/java/com/example/orbit/Main.java")
                val assets = projectRoot.resolve("assets")
                assertTrue(Files.isRegularFile(main), "Main.java should use the conventional libGDX core layout")
                val propertiesFile = projectRoot.resolve("core/libgdx.properties")
                assertTrue(Files.isRegularFile(propertiesFile))
                val properties = Properties().apply { Files.newBufferedReader(propertiesFile).use(::load) }
                assertEquals("com.example.orbit.Main", properties.getProperty("mainClass"))
                assertEquals("轨道跑者", properties.getProperty("gameName"))
                assertEquals("portrait", properties.getProperty("previewOrientation"))
                assertEquals("false", properties.getProperty("previewShowTitleBar"))
                assertTrue(Files.isRegularFile(assets.resolve("shaders/default.vert")))
                assertTrue(Files.isRegularFile(assets.resolve("shaders/default.frag")))
                assertTrue(Files.readString(main).contains("extends ApplicationAdapter"))

                val assetRoot = module.sourceSets.flatMap { it.contentRoots }
                    .singleOrNull { ContentRole.ASSETS in it.roles }
                assertNotNull(assetRoot, "the project-level assets directory must belong to core")
                assertEquals(
                    module.id,
                    ide.moduleForEditableFile(assets.resolve("shaders/default.frag"))?.id,
                    "shader completion needs assets files to resolve to their owning module",
                )

                val declared = module.dependencies.filterIsInstance<LibraryDependency>()
                    .map { it.library.name }
                assertTrue(
                    "com.badlogicgames.gdx:gdx:${LibGdxProjectTemplate.GDX_VERSION}" in declared,
                    "the gdx API must be declared before background resolution starts: $declared",
                )
            }
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun kotlinTemplateCreatesKotlinCoreWithPreviewConfigurationAndGdxDependency() =
        withTempDir("ide-kotlin-libgdx-template") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            try {
                manager.create(
                    "kotlin-libgdx",
                    mapOf(
                        TemplateArgs.NAME to "Kotlin Orbit",
                        TemplateArgs.PACKAGE to "com.example.kotlinorbit",
                        LibGdxProjectTemplate.GAME_NAME to "Kotlin Orbit",
                        LibGdxProjectTemplate.PREVIEW_ORIENTATION to "portrait",
                    ),
                ).use { ide ->
                    val module = ide.modules().single()
                    assertEquals("core", module.name)
                    assertEquals("java-libgdx", module.type.id)

                    val projectRoot = Path.of(manager.list().single().rootPath)
                    val main = projectRoot.resolve("core/src/main/kotlin/com/example/kotlinorbit/Main.kt")
                    assertTrue(Files.isRegularFile(main), "Main.kt should use the conventional Kotlin source root")
                    assertTrue(
                        !Files.exists(projectRoot.resolve("core/src/main/java/com/example/kotlinorbit/Main.java")),
                        "the Kotlin template must not generate a Java entry point",
                    )
                    assertTrue(Files.readString(main).contains("class Main : ApplicationAdapter()"))

                    val sourceRoot = module.sourceSets.flatMap { it.contentRoots }
                        .singleOrNull { ContentRole.SOURCE in it.roles }
                    assertNotNull(sourceRoot)
                    assertTrue(sourceRoot.dir.path.replace('\\', '/').endsWith("core/src/main/kotlin"))
                    assertEquals(
                        module.id,
                        ide.moduleForEditableFile(main)?.id,
                        "Kotlin analysis and completion need Main.kt to resolve to the core module",
                    )

                    val propertiesFile = projectRoot.resolve("core/libgdx.properties")
                    val properties = Properties().apply { Files.newBufferedReader(propertiesFile).use(::load) }
                    assertEquals("com.example.kotlinorbit.Main", properties.getProperty("mainClass"))
                    assertEquals("portrait", properties.getProperty("previewOrientation"))
                    assertTrue(Files.isRegularFile(projectRoot.resolve("assets/shaders/default.vert")))

                    val declared = module.dependencies.filterIsInstance<LibraryDependency>()
                        .map { it.library.name }
                    assertTrue("com.badlogicgames.gdx:gdx:${LibGdxProjectTemplate.GDX_VERSION}" in declared)
                }
            } finally {
                manager.dispose()
            }
        }

    @Test
    fun blankGameNameFallsBackToProjectNameAndPreviewDefaultsAreStable() = withTempDir("ide-libgdx-defaults") { root ->
        val manager = ProjectManager.desktop(root.resolve("projects"))
        try {
            manager.create(
                "libgdx",
                mapOf(TemplateArgs.NAME to "Default Game", TemplateArgs.PACKAGE to "com.example.defaults"),
            ).use {
                val propertiesFile = Path.of(manager.list().single().rootPath).resolve("core/libgdx.properties")
                val properties = Properties().apply { Files.newBufferedReader(propertiesFile).use(::load) }
                assertEquals("Default Game", properties.getProperty("gameName"))
                assertEquals("landscape", properties.getProperty("previewOrientation"))
                assertEquals("true", properties.getProperty("previewShowTitleBar"))
            }
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun glslCompletionWorksForShaderInsideAssets() = withTempDir("ide-libgdx-glsl") { root ->
        val manager = ProjectManager.desktop(root.resolve("projects"))
        try {
            manager.create(
                "libgdx",
                mapOf(TemplateArgs.NAME to "ShaderGame", TemplateArgs.PACKAGE to "com.example.shader"),
            ).use { ide ->
                val shader = Path.of(manager.list().single().rootPath).resolve("assets/shaders/default.frag")
                val source = "void main() { vec4 c = text|; }"
                val textureOffset = source.indexOf('|')
                val texture = runBlocking { ide.complete(shader, source.replace("|", ""), textureOffset) }
                assertTrue("texture" in texture.items.map { it.label }, "GLSL built-in function completion expected")

                val variableSource = "void main() { vec2 p = gl_FragC|; }"
                val variableOffset = variableSource.indexOf('|')
                val variable = runBlocking {
                    ide.complete(shader, variableSource.replace("|", ""), variableOffset)
                }
                assertTrue("gl_FragCoord" in variable.items.map { it.label }, "GLSL built-in variable completion expected")
            }
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun libGdxPropertiesCompletionSuggestsKeysAndKnownValues() = withTempDir("ide-libgdx-properties") { root ->
        val manager = ProjectManager.desktop(root.resolve("projects"))
        try {
            manager.create(
                "libgdx",
                mapOf(TemplateArgs.NAME to "PropertiesGame", TemplateArgs.PACKAGE to "com.example.properties"),
            ).use { ide ->
                val properties = Path.of(manager.list().single().rootPath).resolve("core/libgdx.properties")

                val keySource = "previewOr|"
                val key = runBlocking { ide.complete(properties, keySource.removeSuffix("|"), keySource.indexOf('|')) }
                assertTrue(
                    key.items.any { it.label == "previewOrientation" && it.insertText == "previewOrientation=" },
                    "libGDX property keys should insert a complete key assignment: ${key.items}",
                )

                val valueSource = "previewOrientation=por|"
                val value = runBlocking { ide.complete(properties, valueSource.removeSuffix("|"), valueSource.indexOf('|')) }
                assertTrue("portrait" in value.items.map { it.label }, "orientation values should complete: ${value.items}")

                val booleanSource = "previewShowTitleBar=fal|"
                val boolean = runBlocking {
                    ide.complete(properties, booleanSource.removeSuffix("|"), booleanSource.indexOf('|'))
                }
                assertTrue("false" in boolean.items.map { it.label }, "boolean values should complete: ${boolean.items}")
            }
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun javaCompletionResolvesTypesAndMembersFromTheRealGdxJar() = withTempDir("ide-libgdx-completion") { root ->
        val sourceRoot = root.resolve("src").also(Files::createDirectories).toFile()
        val gdxJar = Path.of(ApplicationAdapter::class.java.protectionDomain.codeSource.location.toURI()).toFile()
        JavaEnvironment.create(
            classpath = listOf(gdxJar),
            sourceRoots = listOf(sourceRoot),
            jdkHome = File(System.getProperty("java.home")),
            moduleName = "libgdx-completion-test",
        ).use { env ->
            val fs = LocalFileSystem(sourceRoot.toPath())
            val file = fs.fileFor(sourceRoot.toPath().resolve("Probe.java"))
            val analyzer = JavaSourceAnalyzer(env)

            assertDirectCompletion(analyzer, file, memberProbe("batch.be|"), "begin")
            assertDirectCompletion(analyzer, file, memberProbe("Gdx.graphics.getW|"), "getWidth")

            val indexedCompletion = JavaCompletion(env, typeSearch = { prefix ->
                if ("SpriteBatch".startsWith(prefix, ignoreCase = true)) {
                    listOf(JavaCompletion.IndexedType("com.badlogic.gdx.graphics.g2d.SpriteBatch", "class"))
                } else emptyList()
            })
            val type = directItems(
                indexedCompletion,
                file,
                "package com.example.complete; class TypeProbe { SpriteB| field; }",
            ).firstOrNull { it.label == "SpriteBatch" }
            assertNotNull(type, "the real gdx SpriteBatch type should be available to indexed auto-import completion")
            assertTrue(
                type.additionalEdits.any { it.newText.contains("import com.badlogic.gdx.graphics.g2d.SpriteBatch;") },
                "accepting SpriteBatch should add its libGDX import: ${type.additionalEdits}",
            )
        }
    }

    @Test
    fun previewRunTaskAppearsOnlyWhenTheAndroidHostProvidesALauncher() = withTempDir("ide-libgdx-run") { root ->
        val env = ApplicationEnvironment()
        env.container.registerServiceIfAbsent(LIBGDX_PREVIEW_LAUNCHER) {
            LibGdxPreviewLauncher { }
        }
        try {
            IdeServices.createProjectAt(
                root,
                "libgdx",
                mapOf(TemplateArgs.NAME to "RunGame", TemplateArgs.PACKAGE to "com.example.run"),
                IdeServices.defaultDesktopSdk(),
                dev.ide.model.LanguageLevel.JAVA_17,
                env = env,
            ).use { ide ->
                assertTrue(
                    ide.build.runTasks().any { it.id == "libgdxRun:core" },
                    "an Android host with the embedded runtime must expose the instant preview task",
                )
            }
        } finally {
            env.close()
        }
    }

    private fun memberProbe(expression: String) = """
        package com.example.complete;
        import com.badlogic.gdx.Gdx;
        import com.badlogic.gdx.graphics.g2d.SpriteBatch;
        class Probe {
            void draw(SpriteBatch batch) { $expression; }
        }
    """.trimIndent()

    private fun directItems(contributor: CompletionContributor, file: VirtualFile, marked: String): List<CompletionItem> {
        val offset = marked.indexOf('|')
        require(offset >= 0)
        val text = marked.removeRange(offset, offset + 1)
        val request = CompletionRequest(Snapshot(file, text), offset, CompletionTrigger.Explicit)
        return runBlocking { contributor.complete(request, JavaLanguageBackend.LANGUAGE_ID) }.items
    }

    private fun assertDirectCompletion(
        analyzer: SourceAnalyzer,
        file: VirtualFile,
        marked: String,
        expected: String,
    ) {
        val offset = marked.indexOf('|')
        require(offset >= 0)
        val text = marked.removeRange(offset, offset + 1)
        val request = CompletionRequest(Snapshot(file, text), offset, CompletionTrigger.Explicit)
        val labels = runBlocking { analyzer.complete(request, JavaLanguageBackend.LANGUAGE_ID) }.items.map { it.label }
        assertTrue(expected in labels, "expected $expected from the real gdx jar; got $labels")
    }

    private class Snapshot(file: VirtualFile, text: CharSequence) :
        DocumentSnapshot by TestDocument(text, file)
}
