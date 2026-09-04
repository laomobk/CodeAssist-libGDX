package dev.ide.core.templates

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateDependency
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/** JVM module compiled by CodeAssist and hosted by the built-in libGDX Android runtime. */
object LibGdxModuleType : ModuleType {
    override val id = "java-libgdx"
    override val displayName = "libGDX Game"
    override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
}

object LibGdxProjectTemplate : ProjectTemplate {
    const val GDX_VERSION = "1.14.2"
    const val MODULE = "core"
    const val GAME_NAME = "gameName"
    const val PREVIEW_ORIENTATION = "previewOrientation"
    const val PREVIEW_SHOW_TITLE_BAR = "previewShowTitleBar"

    override val id = TemplateId("libgdx")
    override val displayName = "libGDX Game"
    override val description = "A libGDX game with instant on-device preview and a shared assets directory."
    override val category = TemplateCategory.OTHER
    override val iconId = "java"

    override fun parameters(): List<TemplateParameter> = listOf(
        TemplateParameter.Text(
            key = GAME_NAME,
            label = "Game name",
            placeholder = "Defaults to project name",
            help = "Displayed in the libGDX preview title bar.",
        ),
        TemplateParameter.Choice(
            key = PREVIEW_ORIENTATION,
            label = "Preview orientation",
            options = listOf(
                TemplateParameter.Choice.Option("landscape", "Landscape"),
                TemplateParameter.Choice.Option("portrait", "Portrait"),
            ),
            defaultIndex = 0,
            help = "Landscape follows both landscape rotations of the device.",
        ),
        TemplateParameter.Toggle(
            key = PREVIEW_SHOW_TITLE_BAR,
            label = "Show preview title bar",
            default = true,
            help = "The title bar provides Back and Enter fullscreen actions.",
        ),
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule(MODULE, scaffold.moduleType(LibGdxModuleType.id)).apply {
                languageLevel = scaffold.languageLevel
                addSourceSet(
                    SourceSetTemplate(
                        "main",
                        DependencyScope.IMPLEMENTATION,
                        mapOf(
                            "src/main/java" to setOf(ContentRole.SOURCE),
                            "../assets" to setOf(ContentRole.ASSETS),
                        ),
                    )
                )
            }
            commit()
        }

        val pkg = args.packageName
        val mainClass = "$pkg.Main"
        val gameName = args.string(GAME_NAME, args.name)
        val previewOrientation = args.string(PREVIEW_ORIENTATION, "landscape")
        val previewShowTitleBar = args.bool(PREVIEW_SHOW_TITLE_BAR, true)
        scaffold.writeText(
            "$MODULE/src/main/java/${JavaTemplateSupport.pkgPath(pkg)}/Main.java",
            """
            package $pkg;

            import com.badlogic.gdx.ApplicationAdapter;
            import com.badlogic.gdx.graphics.GL20;
            import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

            public class Main extends ApplicationAdapter {
                private ShapeRenderer shapes;

                @Override
                public void create() {
                    shapes = new ShapeRenderer();
                }

                @Override
                public void render() {
                    com.badlogic.gdx.Gdx.gl.glClearColor(0.08f, 0.10f, 0.13f, 1f);
                    com.badlogic.gdx.Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                    shapes.begin(ShapeRenderer.ShapeType.Filled);
                    shapes.setColor(0.92f, 0.25f, 0.32f, 1f);
                    shapes.circle(
                        com.badlogic.gdx.Gdx.graphics.getWidth() * 0.5f,
                        com.badlogic.gdx.Gdx.graphics.getHeight() * 0.5f,
                        72f
                    );
                    shapes.end();
                }

                @Override
                public void dispose() {
                    if (shapes != null) shapes.dispose();
                }
            }
            """,
        )
        scaffold.writeText(
            "$MODULE/libgdx.properties",
            """
            mainClass=$mainClass
            gameName=$gameName
            previewOrientation=$previewOrientation
            previewShowTitleBar=$previewShowTitleBar
            """,
        )
        scaffold.writeText(
            "assets/README.txt",
            "Files in this directory are available through Gdx.files.internal(...).\n",
        )
        scaffold.writeText(
            "assets/shaders/default.vert",
            """
            attribute vec4 a_position;
            uniform mat4 u_projTrans;

            void main() {
                gl_Position = u_projTrans * a_position;
            }
            """,
        )
        scaffold.writeText(
            "assets/shaders/default.frag",
            """
            #ifdef GL_ES
            precision mediump float;
            #endif

            uniform vec4 u_color;

            void main() {
                gl_FragColor = u_color;
            }
            """,
        )
    }

    override fun dependencies(args: TemplateArgs): List<TemplateDependency> =
        listOf(TemplateDependency(MODULE, "com.badlogicgames.gdx:gdx:$GDX_VERSION"))
}
