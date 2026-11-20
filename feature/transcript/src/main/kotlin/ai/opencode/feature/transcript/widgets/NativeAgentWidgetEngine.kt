package ai.opencode.feature.transcript.widgets

import ai.opencode.core.log.AppLog
import ai.opencode.widget.ShaderTarget
import ai.opencode.widget.WidgetException
import ai.opencode.widget.WidgetProgram
import ai.opencode.widget.translateShader
import ai.opencode.widget.vertexShader

/** The Rust PsychoScript runner through `opencode-widget-ffi`, when this build bundles it. */
internal object NativeAgentWidgetEngine : AgentWidgetEngine {
    override val isAvailable: Boolean = true

    /** The engine, or null when the native library isn't in this build or doesn't load. */
    fun load(): AgentWidgetEngine? = try {
        val started = System.nanoTime()
        vertexShader(ShaderTarget.GLSL_ES300)
        AppLog.perf.info { "Widget runtime loaded in ${(System.nanoTime() - started) / 1_000} µs" }
        this
    } catch (error: Throwable) {
        AppLog.app.notice { "Widget runtime unavailable: ${error.javaClass.simpleName}" }
        null
    }

    override fun compile(program: String): AgentWidgetProgram {
        val compiled = mapped { WidgetProgram(program) }
        return object : AgentWidgetProgram {
            override fun step(input: String): String = mapped { compiled.step(input) }
            override fun close() = compiled.close()
        }
    }

    override fun translateShader(wgsl: String): String = mapped { translateShader(wgsl, ShaderTarget.GLSL_ES300) }

    override fun vertexShader(): String = mapped { vertexShader(ShaderTarget.GLSL_ES300) }

    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (error: WidgetException) {
        throw AgentWidgetEngineException(
            error.message ?: error.javaClass.simpleName,
            isTimeout = error is WidgetException.Timeout,
            isBudget = error is WidgetException.Budget,
        )
    }
}
