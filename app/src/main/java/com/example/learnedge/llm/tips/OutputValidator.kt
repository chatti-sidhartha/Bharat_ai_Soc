package com.example.learnedge.llm.tips

import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface Validation {
    data class Valid(val summary: String, val tips: Map<String, String>) : Validation
    data class Invalid(val reason: String) : Validation
}

/** Accepts model output only if it is the expected JSON about today's planned topics. */
object OutputValidator {
    const val MAX_TIP_CHARS = 300
    const val MAX_SUMMARY_CHARS = 400
    const val MIN_TEXT_CHARS = 10

    fun validate(raw: String, todaysTopicIds: Set<String>): Validation {
        // Tolerate text or ``` fences around the object; everything outside the outer braces is ignored.
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return Validation.Invalid("no JSON object found")

        val root = try {
            Json.parseToJsonElement(raw.substring(start, end + 1))
        } catch (e: SerializationException) {
            return Validation.Invalid("malformed JSON (${e.message?.lineSequence()?.firstOrNull()?.take(80)})")
        } catch (e: IllegalArgumentException) {
            return Validation.Invalid("malformed JSON")
        }
        val obj = root as? JsonObject ?: return Validation.Invalid("top level is not a JSON object")
        val days = obj["days"] as? JsonArray ?: return Validation.Invalid("missing \"days\" array")
        val summary = obj.string("summary")?.trim() ?: return Validation.Invalid("missing \"summary\" string")
        lengthError("summary", summary, MAX_SUMMARY_CHARS)?.let { return Validation.Invalid(it) }

        val tips = LinkedHashMap<String, String>()
        for ((i, element) in days.withIndex()) {
            val day = element as? JsonObject ?: return Validation.Invalid("days[$i] is not an object")
            val topicId = day.string("topicId") ?: return Validation.Invalid("days[$i] has no \"topicId\" string")
            val tip = day.string("tip")?.trim() ?: return Validation.Invalid("days[$i] has no \"tip\" string")
            val date = day.string("date") ?: return Validation.Invalid("days[$i] has no \"date\" string")
            try {
                LocalDate.parse(date.trim())
            } catch (e: DateTimeParseException) {
                return Validation.Invalid("days[$i] date \"$date\" is not YYYY-MM-DD")
            }
            if (topicId !in todaysTopicIds) return Validation.Invalid("unknown topicId \"$topicId\"")
            lengthError("tip for $topicId", tip, MAX_TIP_CHARS)?.let { return Validation.Invalid(it) }
            tips.putIfAbsent(topicId, tip)
        }
        if (tips.isEmpty()) return Validation.Invalid("\"days\" is empty")
        return Validation.Valid(summary, tips)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun lengthError(what: String, text: String, max: Int): String? = when {
        text.length < MIN_TEXT_CHARS -> "$what is too short"
        text.length > max -> "$what is longer than $max characters"
        else -> null
    }
}
