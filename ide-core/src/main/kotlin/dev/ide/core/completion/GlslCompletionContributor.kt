package dev.ide.core.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet

/** Lightweight GLSL vocabulary completion; project identifiers remain covered by buffer-word completion. */
object GlslCompletionContributor : CompletionContributor {
    override val id = "glsl.builtins"

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        KEYWORDS.filter(params::prefixMatches).forEach { word ->
            result.addElement(CompletionItem(word, word, CompletionItemKind.KEYWORD, detail = "GLSL keyword"))
        }
        BUILT_INS.filter(params::prefixMatches).forEach { name ->
            result.addElement(
                CompletionItem(
                    label = name,
                    insertText = "$name()",
                    kind = CompletionItemKind.METHOD,
                    detail = "GLSL built-in",
                    caret = CaretAction.At(name.length + 1),
                )
            )
        }
        VARIABLES.filter(params::prefixMatches).forEach { name ->
            result.addElement(CompletionItem(name, name, CompletionItemKind.VARIABLE, detail = "GLSL built-in"))
        }
    }

    val KEYWORDS = setOf(
        "attribute", "const", "uniform", "varying", "buffer", "shared", "coherent", "volatile",
        "restrict", "readonly", "writeonly", "layout", "centroid", "flat", "smooth", "noperspective",
        "patch", "sample", "break", "continue", "do", "for", "while", "switch", "case", "default",
        "if", "else", "subroutine", "in", "out", "inout", "float", "double", "int", "void", "bool",
        "true", "false", "invariant", "discard", "return", "mat2", "mat3", "mat4", "dmat2", "dmat3",
        "dmat4", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "bvec2", "bvec3", "bvec4",
        "dvec2", "dvec3", "dvec4", "uint", "uvec2", "uvec3", "uvec4", "lowp", "mediump", "highp",
        "precision", "sampler2D", "sampler3D", "samplerCube", "sampler2DShadow", "samplerCubeShadow",
        "struct",
    )

    private val VARIABLES = setOf(
        "gl_Position", "gl_PointSize", "gl_FragCoord", "gl_FrontFacing", "gl_PointCoord", "gl_FragDepth",
        "gl_VertexID", "gl_InstanceID", "gl_GlobalInvocationID", "gl_LocalInvocationID", "gl_WorkGroupID",
    )

    private val BUILT_INS = setOf(
        "radians", "degrees", "sin", "cos", "tan", "asin", "acos", "atan", "pow", "exp", "log", "sqrt",
        "inversesqrt", "abs", "sign", "floor", "trunc", "round", "ceil", "fract", "mod", "min", "max",
        "clamp", "mix", "step", "smoothstep", "isnan", "isinf", "length", "distance", "dot", "cross",
        "normalize", "faceforward", "reflect", "refract", "matrixCompMult", "transpose", "determinant",
        "inverse", "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual", "equal", "notEqual",
        "any", "all", "not", "texture", "textureProj", "textureLod", "texelFetch", "textureSize",
        "dFdx", "dFdy", "fwidth",
    )
}
