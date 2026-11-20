package ai.opencode.feature.transcript.widgets

import ai.opencode.core.log.AppLog
import com.anomalyco.psychoscript.Program
import com.anomalyco.psychoscript.PsychoScriptException
import com.anomalyco.psychoscript.RunOptions

/**
 * The step ABI on the Rust PsychoScript (`psychoscript-mobile`), when this build bundles it: a
 * template's program compiles once with the step entry appended, and steps run under the
 * contract's 250 ms (draws 8 ms; a timeout there is an overrun).
 */
internal object NativeAgentWidgetEngine : AgentWidgetEngine {
    override val isAvailable: Boolean = true

    /** The engine, or null when the native library isn't in this build or doesn't load. */
    fun load(): AgentWidgetEngine? = try {
        val started = System.nanoTime()
        Program("1").use { it.run("null") }
        AppLog.perf.info { "Widget runtime loaded in ${(System.nanoTime() - started) / 1_000} µs" }
        this
    } catch (error: Throwable) {
        AppLog.app.notice { "Widget runtime unavailable: ${error.javaClass.simpleName}" }
        null
    }

    override fun compile(program: String): AgentWidgetProgram {
        val compiled = run { Program(program + ENTRY) }
        return object : AgentWidgetProgram {
            override fun step(input: String): String = run { compiled.run(input, STEP) }
            override fun draw(input: String): String = run(draw = true) { compiled.run(input, DRAW) }
            override fun close() = compiled.close()
        }
    }

    private inline fun <T> run(draw: Boolean = false, block: () -> T): T = try {
        block()
    } catch (error: PsychoScriptException) {
        val reason = when (error) {
            is PsychoScriptException.Syntax -> error.reason
            is PsychoScriptException.Runtime -> error.reason
            is PsychoScriptException.Timeout -> error.reason
            is PsychoScriptException.OutputTooLarge -> error.reason
            is PsychoScriptException.InvalidInput -> error.reason
        }
        val timeout = error is PsychoScriptException.Timeout
        throw AgentWidgetEngineException(reason, isTimeout = timeout && !draw, isBudget = timeout && draw)
    }

    private const val ENTRY = "\nreturn __widget_step(__input())"
    private val STEP = RunOptions(timeoutMs = 250uL, maxOutputBytes = 4uL * 1024uL * 1024uL)
    private val DRAW = RunOptions(timeoutMs = 8uL, maxOutputBytes = 1024uL * 1024uL)
}
