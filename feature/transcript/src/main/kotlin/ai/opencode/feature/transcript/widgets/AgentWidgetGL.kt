package ai.opencode.feature.transcript.widgets

import ai.opencode.core.log.AppLog
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Every shader node's GPU side: one GL thread and one GLES 3 context for the whole process, the
 * full-screen-triangle vertex stage, one uniform buffer, and linked programs cached by their
 * translated GLSL. Each node draws into its own [Surface] (a `TextureView`'s window surface).
 */
internal class AgentWidgetGL private constructor() {
    private val thread = HandlerThread("AgentWidgetGL").apply { start() }
    val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE
    private var vertexShader = 0
    private var uniforms = 0
    private var vertexArray = 0
    private var setupFailure: String? = null
    private val programs = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean {
            if (size <= PROGRAM_LIMIT) return false
            GLES30.glDeleteProgram(eldest.value)
            return true
        }
    }

    /** GL thread: the context, once; null when it's ready, else why it isn't. */
    private fun setUp(vertex: String): String? {
        if (context != EGL14.EGL_NO_CONTEXT) return null
        setupFailure?.let { return it }
        val failure = runCatching {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no GLES 3 config" }
            config = configs[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "no GLES 3 context" }
            idle = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, idle, idle, context)) { "eglMakeCurrent failed" }
            vertexShader = compile(GLES30.GL_VERTEX_SHADER, vertex)
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            uniforms = ids[0]
            GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, uniforms)
            GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, UNIFORM_BYTES, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glGenVertexArrays(1, ids, 0)
            vertexArray = ids[0]
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        if (failure != null) {
            AppLog.app.notice { "Widget shader GL setup failed" }
            setupFailure = failure
        }
        return failure
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error(log.trim().ifEmpty { "the shader didn't compile" })
        }
        return shader
    }

    /** GL thread: [fragment] linked with the vertex stage, its uniform block bound to 0. */
    private fun program(fragment: String): Int {
        programs[fragment]?.let { return it }
        val shader = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, shader)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(shader)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error(log.trim().ifEmpty { "the shader didn't link" })
        }
        var block = GLES30.glGetUniformBlockIndex(program, UNIFORM_BLOCK)
        if (block == GLES30.GL_INVALID_INDEX) {
            val blocks = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_ACTIVE_UNIFORM_BLOCKS, blocks, 0)
            block = if (blocks[0] > 0) 0 else GLES30.GL_INVALID_INDEX
        }
        if (block != GLES30.GL_INVALID_INDEX) GLES30.glUniformBlockBinding(program, block, 0)
        programs[fragment] = program
        return program
    }

    /** What a [Surface] tells its node, on the main thread. */
    interface SurfaceEvents {
        /** Ready to draw a frame (attached with a program), or not any more. */
        fun ready(value: Boolean)
        fun failed(message: String)
        /** Three frames over budget in a row: the last one stays. */
        fun stopped()
    }

    /**
     * One shader node's drawable. Calls come from the main thread and run on the GL thread in
     * order; [render] drops a frame while the previous one is still drawing.
     */
    inner class Surface(private val vertex: String, private val events: SurfaceEvents) {
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var texture: SurfaceTexture? = null
        private var width = 0
        private var height = 0
        private var program = 0
        @Volatile private var closed = false
        private val drawing = AtomicBoolean(false)
        private val values = FloatArray(UNIFORM_FLOATS)
        private val buffer: FloatBuffer = ByteBuffer.allocateDirect(UNIFORM_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private var overruns = 0
        private var frames = 0
        private var frameTotal = 0L
        private var frameMax = 0L

        fun attach(texture: SurfaceTexture, width: Int, height: Int) = handler.post {
            if (closed) return@post
            release()
            setUp(vertex)?.let { failure -> return@post report { events.failed(failure) } }
            this.texture = texture
            this.width = width
            this.height = height
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return@post report { events.failed("couldn't create the shader's surface") }
            announce()
        }

        fun resize(width: Int, height: Int) = handler.post {
            this.width = width
            this.height = height
        }

        /** The texture's gone: drops the window surface, then releases the texture. */
        fun detach(texture: SurfaceTexture) = handler.post {
            if (this.texture === texture) release()
            texture.release()
            report { events.ready(false) }
        }

        fun use(fragment: String) = handler.post {
            if (closed) return@post
            setUp(vertex)?.let { failure -> return@post report { events.failed(failure) } }
            program = try {
                program(fragment)
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                return@post report { events.failed(message) }
            }
            announce()
        }

        /** Main thread: draws one frame unless the last one is still drawing. */
        fun render(time: Float, scale: Float, pointerX: Float, pointerY: Float, down: Boolean, nodeValues: FloatArray): Boolean {
            if (!drawing.compareAndSet(false, true)) return false
            values[2] = time
            values[3] = scale
            values[4] = pointerX
            values[5] = pointerY
            values[6] = if (down) 1f else 0f
            values[7] = 0f
            nodeValues.copyInto(values, 8, 0, minOf(16, nodeValues.size))
            handler.post {
                try {
                    draw()
                } finally {
                    drawing.set(false)
                }
            }
            return true
        }

        fun close() = handler.post {
            closed = true
            release()
        }

        private fun announce() {
            if (surface != EGL14.EGL_NO_SURFACE && program != 0) report { events.ready(true) }
        }

        private fun release() {
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, idle, idle, context)
                EGL14.eglDestroySurface(display, surface)
                surface = EGL14.EGL_NO_SURFACE
            }
            texture = null
        }

        private fun draw() {
            if (closed || surface == EGL14.EGL_NO_SURFACE || program == 0 || width <= 0 || height <= 0) return
            val started = System.nanoTime()
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return
            values[0] = width.toFloat()
            values[1] = height.toFloat()
            buffer.position(0)
            buffer.put(values)
            buffer.position(0)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(program)
            GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, 0, uniforms)
            GLES30.glBufferSubData(GLES30.GL_UNIFORM_BUFFER, 0, UNIFORM_BYTES, buffer)
            GLES30.glBindVertexArray(vertexArray)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            EGL14.eglSwapBuffers(display, surface)
            val micros = (System.nanoTime() - started) / 1_000
            overruns = if (micros > FRAME_BUDGET_MICROS) overruns + 1 else 0
            if (overruns >= 3) {
                overruns = 0
                AppLog.perf.notice { "Widget shader stopped: over 50 ms three frames in a row" }
                report { events.stopped() }
            }
            frames++
            frameTotal += micros
            if (micros > frameMax) frameMax = micros
            if (frames == 120) {
                AppLog.perf.info { "Widget shader: 120 frames ${width}x$height, frame avg ${frameTotal / frames} µs (max $frameMax)" }
                frames = 0
                frameTotal = 0
                frameMax = 0
            }
        }

        private inline fun report(crossinline block: () -> Unit) {
            main.post { if (!closed) block() }
        }
    }

    companion object {
        private const val UNIFORM_FLOATS = 24
        private const val UNIFORM_BYTES = UNIFORM_FLOATS * 4
        private const val UNIFORM_BLOCK = "Widget_block_0Fragment"
        private const val PROGRAM_LIMIT = 16
        private const val FRAME_BUDGET_MICROS = 50_000L

        val shared: AgentWidgetGL by lazy { AgentWidgetGL() }
    }
}
