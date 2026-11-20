package ai.opencode.feature.transcript.widgets

import com.anomalyco.psychoscript.ShaderException
import com.anomalyco.psychoscript.ShaderStage
import com.anomalyco.psychoscript.ShaderTarget
import com.anomalyco.psychoscript.translateWgsl

/**
 * Translates a shader node on the device, until the server's `opencode.widget` translation is
 * there: the node's WGSL in the spec's header and a GL entry point that flips `frag.y` to a
 * top-left origin, as GLSL ES 3.00 through the bundled runner's naga.
 */
internal object LocalAgentWidgetShaderTranslator {
    fun stages(wgsl: String): AgentWidgetShaderStages = try {
        val fragment = translateWgsl(HEADER + wgsl + ENTRY_GL, ShaderTarget.GLSL_ES300, ShaderStage.FRAGMENT, "widget_main")
        val vertex = translateWgsl(VERTEX, ShaderTarget.GLSL_ES300, ShaderStage.VERTEX, "widget_vertex")
        AgentWidgetShaderStages(
            vertex = AgentWidgetShaderCode(vertex.code),
            fragment = AgentWidgetShaderCode(fragment.code, fragment.uniformBlocks.map { it.name to it.slot.toInt() }),
        )
    } catch (error: ShaderException) {
        throw AgentWidgetTransportException(message(error, wgsl.lines().size))
    } catch (error: UnsatisfiedLinkError) {
        throw AgentWidgetTransportException("The widget runtime isn't available in this build.")
    }

    /** The reason, with where it happened as a line of the template when it happened there. */
    private fun message(error: ShaderException, templateLines: Int): String {
        val (reason, line, column) = when (error) {
            is ShaderException.Parse -> Triple(error.reason, error.line, error.column)
            is ShaderException.Validation -> Triple(error.reason, error.line, error.column)
            is ShaderException.Unsupported -> Triple(error.reason, null, null)
            is ShaderException.Translation -> Triple(error.reason, null, null)
        }
        val at = line?.toInt()?.minus(HEADER_LINES)
        return if (at != null && at in 1..templateLines) "$reason (line $at, column ${column ?: 0u})" else reason
    }

    private const val HEADER = "struct Widget { resolution: vec2f, time: f32, scale: f32, pointer: vec4f, values: array<vec4f, 4> }\n" +
        "@group(0) @binding(0) var<uniform> widget: Widget;\n"
    private const val HEADER_LINES = 2
    private const val ENTRY_GL = "\n@fragment fn widget_main(@builtin(position) p: vec4f) -> @location(0) vec4f " +
        "{ return shade(vec2f(p.x, widget.resolution.y - p.y)); }\n"
    private const val VERTEX = "@vertex fn widget_vertex(@builtin(vertex_index) i: u32) -> @builtin(position) vec4f {\n" +
        "  let uv = vec2f(f32((i << 1u) & 2u), f32(i & 2u));\n" +
        "  return vec4f(uv * 2.0 - 1.0, 0.0, 1.0);\n" +
        "}\n"
}
