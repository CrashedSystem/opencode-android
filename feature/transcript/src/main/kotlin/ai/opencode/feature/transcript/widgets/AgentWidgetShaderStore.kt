package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Translated shaders by the hash of their WGSL (and target): the few most recent in memory, all of
 * them on disk, so a widget template's banner translates once per device. A failure is kept for the
 * process only, so a newer runner gets another try next launch.
 */
class AgentWidgetShaderStore(
    private val directory: File?,
    private val engine: () -> AgentWidgetEngine,
) {
    private val memory = object : LinkedHashMap<String, Result<AgentWidgetShaderStages>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<AgentWidgetShaderStages>>) = size > 16
    }

    /** The node's stages, or throws why it can't run. */
    suspend fun stages(wgsl: String): AgentWidgetShaderStages {
        val bytes = wgsl.toByteArray()
        if (bytes.size > AgentWidgetNode.MAX_SHADER_BYTES) throw AgentWidgetEngineException("The shader is ${bytes.size} bytes, over the 16 KB cap.")
        val key = hash(bytes)
        synchronized(memory) { memory[key] }?.let { return it.getOrThrow() }
        val result = withContext(Dispatchers.IO) {
            read(key)?.let { Result.success(it) } ?: run {
                val started = System.nanoTime()
                val translated = runCatching { engine().translateShader(wgsl) }
                AppLog.perf.info { "Widget shader translated in ${(System.nanoTime() - started) / 1_000} µs (${bytes.size} bytes)" }
                translated.onSuccess { write(key, it) }
            }
        }
        synchronized(memory) { memory[key] = result }
        return result.getOrThrow()
    }

    private fun hash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(TARGET.toByteArray())
        digest.update(0)
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun read(key: String): AgentWidgetShaderStages? {
        val file = directory?.let { File(it, "$key.json") }?.takeIf { it.isFile } ?: return null
        return runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            AgentWidgetShaderStages(code(o["vertex"]!!.jsonObject), code(o["fragment"]!!.jsonObject))
        }.getOrNull()
    }

    private fun write(key: String, stages: AgentWidgetShaderStages) {
        val directory = directory ?: return
        runCatching {
            directory.mkdirs()
            val text = buildJsonObject {
                put("vertex", json(stages.vertex))
                put("fragment", json(stages.fragment))
            }.toString()
            val temporary = File(directory, "$key.tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(File(directory, "$key.json"))) temporary.delete()
        }.onFailure { AppLog.app.notice { "Widget shader cache write failed: ${it.javaClass.simpleName}" } }
    }

    private fun json(code: AgentWidgetShaderCode): JsonObject = buildJsonObject {
        put("code", code.code)
        put("blocks", buildJsonArray { for ((name, slot) in code.uniformBlocks) add(JsonArray(listOf(JsonPrimitive(name), JsonPrimitive(slot)))) })
    }

    private fun code(o: JsonObject) = AgentWidgetShaderCode(
        o["code"]!!.jsonPrimitive.content,
        o["blocks"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.int },
    )

    companion object Key : DependencyKey<AgentWidgetShaderStore> {
        private const val TARGET = "glsl-es-300"

        override val liveValue: AgentWidgetShaderStore by lazy {
            AgentWidgetShaderStore(File(File(dependency(AppContext).filesDir, "support"), "widget-shaders")) { dependency(AgentWidgetEngine) }
        }
        override val testValue: AgentWidgetShaderStore
            get() = AgentWidgetShaderStore(null) { dependency(AgentWidgetEngine) }
    }
}
