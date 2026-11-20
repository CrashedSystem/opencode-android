package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.DependencyKey

/**
 * Runs widget programs on the Rust PsychoScript (`opencode-widget-ffi`). A program compiles once
 * per template version; each step takes the step input's JSON and returns the step output's (or
 * a draw step's `{ commands }`). Steps on one program never overlap: the store serializes them.
 * Every call throws [AgentWidgetEngineException] when it fails.
 */
interface AgentWidgetEngine {
    val isAvailable: Boolean

    fun compile(program: String): AgentWidgetProgram

    /** A `shader` node's `wgsl` (its `shade` function) wrapped in the contract's header and entry point, as GLSL ES 3.00. */
    fun translateShader(wgsl: String): String

    /** The full-screen triangle vertex stage paired with every translated shader, as GLSL ES 3.00. */
    fun vertexShader(): String

    companion object Key : DependencyKey<AgentWidgetEngine> {
        override val liveValue: AgentWidgetEngine by lazy { NativeAgentWidgetEngine.load() ?: Unavailable }
        override val testValue: AgentWidgetEngine get() = Unavailable
    }

    /** Without the native runtime every widget shows its error card. */
    object Unavailable : AgentWidgetEngine {
        override val isAvailable: Boolean = false
        override fun compile(program: String): AgentWidgetProgram = throw AgentWidgetEngineException(UNAVAILABLE)
        override fun translateShader(wgsl: String): String = throw AgentWidgetEngineException(UNAVAILABLE)
        override fun vertexShader(): String = throw AgentWidgetEngineException(UNAVAILABLE)
        private const val UNAVAILABLE = "The widget runtime isn't available in this build."
    }
}

fun interface AgentWidgetProgram : AutoCloseable {
    fun step(input: String): String

    /** Frees the interpreter now rather than when the collector gets to it. */
    override fun close() = Unit
}

/** [isBudget]: a draw step over 8 ms or 2,000 commands; the canvas counts these and stops after three. */
class AgentWidgetEngineException(message: String, val isTimeout: Boolean = false, val isBudget: Boolean = false) : Exception(message)
