package ai.opencode.mobilecode.system

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `golden.json`, written by `scripts/justice-golden.ts` from the TypeScript engine. */
object JusticeGolden {
    val root: JsonObject by lazy {
        val text = JusticeGolden::class.java.getResourceAsStream("/justice/golden.json")!!.bufferedReader().readText()
        Json.parseToJsonElement(text).jsonObject
    }

    val prepared: Map<String, Justice.Prepared> by lazy { root.getValue("prepared").jsonObject.mapValues { prepared(it.value.jsonObject) } }

    val cases: List<JsonObject> by lazy { root.getValue("cases").jsonArray.map { it.jsonObject } }

    /** The test measures, by the names the generator uses. */
    val measures: Map<String, (String) -> Double> = mapOf(
        "eight" to { text -> text.codePoints().toArray().sumOf { if (it == ' '.code) 4.0 else 8.0 } },
        "shaped" to { text -> text.codePoints().toArray().sumOf { if (it == ' '.code) 4.0 else 8.0 } - Regex("a-").findAll(text).count() * 2 },
    )

    val opticals: Map<String, (String, Int) -> Justice.Edges> = mapOf(
        "oracle" to { _, index -> Justice.Edges((index % 3) * 1.7, (index % 2) * 2.1) },
        "thus" to { _, _ -> Justice.Edges(2.0, 3.0) },
        "hyphenation-oracle" to { text, index ->
            Justice.Edges(0.3 * (text[0].code % 5) + index * 0.1, if (text.endsWith("-")) 2.5 else 0.4 * index)
        },
    )

    fun widths(element: JsonElement): List<Double> =
        if (element is JsonArray) element.map { it.number() } else listOf(element.number())

    fun options(policy: JsonObject): Justice.Options {
        var o = Justice.defaults
        for ((key, value) in policy) {
            o = when (key) {
                "stretch" -> o.copy(stretch = value.number())
                "shrink" -> o.copy(shrink = value.number())
                "mode" -> o.copy(mode = Justice.Mode.valueOf(value.jsonPrimitive.content))
                "tracking" -> o.copy(tracking = value.number())
                "compressionPenalty" -> o.copy(compressionPenalty = value.number())
                "emergencyStretch" -> o.copy(emergencyStretch = value.number())
                "hanging" -> o.copy(hanging = value.number())
                "opening" -> o.copy(opening = value.number())
                "protrusion" -> o.copy(protrusion = value.number())
                "adjacentPenalty" -> o.copy(adjacentPenalty = value.number())
                "lastLine" -> o.copy(lastLine = value.number())
                "ending" -> o.copy(ending = Justice.Ending.valueOf(value.jsonPrimitive.content))
                "widowPenalty" -> o.copy(widowPenalty = value.number())
                "hyphenPenalty" -> o.copy(hyphenPenalty = value.number())
                "consecutiveHyphenPenalty" -> o.copy(consecutiveHyphenPenalty = value.number())
                "finalHyphenPenalty" -> o.copy(finalHyphenPenalty = value.number())
                "explicitHyphenPenalty" -> o.copy(explicitHyphenPenalty = value.number())
                else -> error("Unknown policy key $key")
            }
        }
        return o
    }

    fun JsonElement.number(): Double = when (val content = jsonPrimitive.content) {
        "Infinity" -> Double.POSITIVE_INFINITY
        "-Infinity" -> Double.NEGATIVE_INFINITY
        else -> content.toDouble()
    }

    private fun JsonElement.doubles(): DoubleArray? = if (this is JsonNull) null else jsonArray.map { it.number() }.toDoubleArray()

    private fun prepared(json: JsonObject) = Justice.Prepared(
        words = json.getValue("words").jsonArray.map { it.jsonPrimitive.content },
        widths = json.getValue("widths").doubles()!!,
        characters = json.getValue("characters").doubles()!!,
        endHangs = json.getValue("endHangs").doubles()!!,
        startHangs = json.getValue("startHangs").doubles()!!,
        space = json.getValue("space").number(),
        startProtrusions = json.getValue("startProtrusions").doubles(),
        endProtrusions = json.getValue("endProtrusions").doubles(),
        hyphenation = (json.getValue("hyphenation") as? JsonArray)?.map { fragments ->
            if (fragments is JsonNull) return@map null
            val f = fragments.jsonObject
            Justice.WordFragments(
                offsets = f.getValue("offsets").jsonArray.map { it.jsonPrimitive.int }.toIntArray(),
                widths = f.getValue("widths").doubles()!!,
                hyphenWidths = f.getValue("hyphenWidths").doubles()!!,
                characters = f.getValue("characters").doubles()!!,
                startProtrusions = f.getValue("startProtrusions").doubles(),
                hyphenProtrusion = (f.getValue("hyphenProtrusion") as? JsonPrimitive)?.takeUnless { it is JsonNull }?.double,
                explicit = (f.getValue("explicit") as? JsonArray)?.map { it.jsonPrimitive.boolean }?.toBooleanArray(),
            )
        },
    )
}
