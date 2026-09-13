package github.rikacelery.cutter.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads and writes LosslessCut `.llc` project files.
 *
 * ## The format is JSON5, not JSON
 *
 * LosslessCut writes `JSON5.stringify(projectData, null, 2)`, so real files carry
 * unquoted identifier keys, single-quoted strings and trailing commas — all of which
 * a strict JSON parser rejects. The 993 project files already sitting next to the
 * recordings look exactly like that.
 *
 * Version history (LosslessCut 3.69.0, `src/renderer/src/types.ts`):
 *  - **v1**: `{version: 1, mediaFileName?, cutSegments: [{start?, end?, name, tags?}]}`
 *  - **v2**: `start` became required and `selected` was added. Nothing else changed.
 *
 * A v1 file is upgraded on load by defaulting `start` to 0. Writing always emits v2.
 * No other fields are produced: `discardedSegments`, `customSegmentTags` and
 * `cutTimeOffset` do not exist in 3.69.0 and would only confuse a round trip.
 *
 * Note the segment cap LosslessCut applies on load is 2000, so [MAX_SEGMENTS] warns
 * rather than silently writing a file it would truncate.
 */
object Llc {

    private const val MAX_SEGMENTS = 2000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses JSON5 into a [CutProject].
     *
     * Throws [IllegalArgumentException] if the text is not an object or has no
     * `cutSegments` array.
     */
    fun parse(text: String): CutProject {
        val normalized = Json5.toStrictJson(text)
        val root = json.parseToJsonElement(normalized).jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull() ?: 2
        val media = root["mediaFileName"]?.jsonPrimitive?.contentOrNull ?: ""
        val segments = (root["cutSegments"] as? JsonArray)?.map { element ->
            val obj = element.jsonObject
            val rawStart = obj["start"]?.jsonPrimitive?.doubleOrNull
            val rawEnd = obj["end"]?.jsonPrimitive?.doubleOrNull
            CutSegment(
                // v1 allowed a missing start; LosslessCut upgrades it to 0.
                start = rawStart ?: 0.0,
                // A missing end means marker — never "until EOF".
                end = rawEnd,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                tags = (obj["tags"] as? JsonObject)?.entries
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                    ?.toMap() ?: emptyMap(),
                selected = obj["selected"]?.jsonPrimitive?.booleanOrNull ?: true
            )
        } ?: throw IllegalArgumentException("not a .llc file: no cutSegments array")

        return CutProject(version = if (version >= 2) 2 else version, mediaFileName = media, segments = segments)
    }

    /**
     * Serialises in JSON5 style, matching what LosslessCut itself writes so the files
     * stay diff-friendly with the existing corpus.
     */
    fun write(project: CutProject): String {
        val sb = StringBuilder(256 + project.segments.size * 96)
        sb.append("{\n")
        sb.append("  version: 2,\n")
        sb.append("  mediaFileName: ").append(quote(project.mediaFileName)).append(",\n")
        sb.append("  cutSegments: [\n")
        for (segment in project.segments) {
            sb.append("    {\n")
            sb.append("      start: ").append(num(segment.start)).append(",\n")
            // Omitting `end` is how a marker is expressed.
            segment.end?.let { sb.append("      end: ").append(num(it)).append(",\n") }
            sb.append("      name: ").append(quote(segment.name)).append(",\n")
            if (segment.tags.isNotEmpty()) {
                sb.append("      tags: { ")
                sb.append(segment.tags.entries.joinToString(", ") { (k, v) -> "${quote(k)}: ${quote(v)}" })
                sb.append(" },\n")
            }
            sb.append("      selected: ").append(segment.selected).append(",\n")
            sb.append("    },\n")
        }
        sb.append("  ],\n")
        sb.append("}\n")
        return sb.toString()
    }

    fun segmentCountWarning(project: CutProject): String? =
        if (project.segments.size > MAX_SEGMENTS) {
            "项目包含 ${project.segments.size} 个片段，超过 LosslessCut 的 $MAX_SEGMENTS 上限，超出部分会被其忽略"
        } else {
            null
        }

    private fun num(value: Double): String = String.format(java.util.Locale.ROOT, "%.6f", value)

    /** Single-quoted, JSON5 style, with `'` and `\` escaped. */
    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        return "'$escaped'"
    }

    private fun JsonPrimitive.intOrNull(): Int? =
        contentOrNull?.toDoubleOrNull()?.toInt()
}

/**
 * Converts the JSON5 dialect LosslessCut emits into strict JSON.
 *
 * This is a small hand-written scanner rather than a general JSON5 implementation: it
 * handles exactly the extensions that appear in real `.llc` files — comments,
 * unquoted keys, single-quoted strings, trailing commas, and a leading `+` on
 * numbers — while correctly staying out of the way of string contents, which is
 * where a naive regex pass corrupts data.
 */
internal object Json5 {

    fun toStrictJson(text: String): String {
        val out = StringBuilder(text.length + 64)
        var i = 0
        val n = text.length
        // True when the previous significant token ended an object key, so an
        // identifier here must be quoted.
        var expectKey = false

        while (i < n) {
            val c = text[i]
            when {
                c == '"' || c == '\'' -> {
                    val (literal, next) = readString(text, i)
                    out.append(literal)
                    i = next
                    expectKey = false
                }

                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }

                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                }

                c == '{' || c == '[' -> {
                    out.append(c)
                    expectKey = c == '{'
                    i++
                }

                c == '}' || c == ']' -> {
                    // A trailing comma before a closer is legal JSON5, illegal JSON.
                    trimTrailingComma(out)
                    out.append(c)
                    expectKey = false
                    i++
                }

                c == ',' -> {
                    out.append(c)
                    expectKey = out.isNotEmpty() && out.last() == ','
                    i++
                }

                c == ':' -> {
                    out.append(c)
                    expectKey = false
                    i++
                }

                c.isWhitespace() -> {
                    out.append(c)
                    i++
                }

                // A bare identifier used as an object key.
                (c.isLetter() || c == '_' || c == '$') && expectKey -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$' || text[i] == '-')) i++
                    out.append('"').append(text, start, i).append('"')
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
            if (expectKey && out.isNotEmpty()) {
                // Still inside an object; keep expecting a key until ':' arrives.
                val last = out.last()
                if (last == ':') expectKey = false
            }
        }
        return out.toString()
    }

    private fun trimTrailingComma(out: StringBuilder) {
        var j = out.length - 1
        while (j >= 0 && out[j].isWhitespace()) j--
        if (j >= 0 && out[j] == ',') out.deleteCharAt(j)
    }

    /**
     * Reads a quoted string, returning an equivalent strictly-valid JSON literal.
     *
     * JSON5 and JSON disagree about escapes, so this cannot be a blind copy: `\'` is
     * legal inside a single-quoted JSON5 string but is **not** a valid JSON escape,
     * and a bare `"` inside a single-quoted string has to become `\"`.
     */
    private fun readString(text: String, start: Int): Pair<String, Int> {
        val quoteChar = text[start]
        val sb = StringBuilder()
        sb.append('"')
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    when (val next = text[i + 1]) {
                        // The source dialect's own quote needs no escape in JSON.
                        quoteChar -> if (quoteChar == '"') sb.append("\\\"") else sb.append('\'')
                        '\\' -> sb.append("\\\\")
                        // A backslash-newline is a line continuation in JSON5.
                        '\n' -> Unit
                        'u' -> {
                            sb.append("\\u")
                            var copied = 0
                            var j = i + 2
                            while (copied < 4 && j < text.length && text[j].isLetterOrDigit()) {
                                sb.append(text[j]); j++; copied++
                            }
                            i = j - 2
                        }
                        in JSON_ESCAPES -> sb.append('\\').append(next)
                        else -> sb.append(next)
                    }
                    i += 2
                }
                c == quoteChar -> {
                    sb.append('"')
                    return sb.toString() to (i + 1)
                }
                c == '"' -> {
                    sb.append("\\\"")
                    i++
                }
                c == '\n' -> {
                    sb.append("\\n")
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        throw IllegalArgumentException("unterminated string in .llc at offset $start")
    }

    /** Escapes JSON accepts verbatim. */
    private const val JSON_ESCAPES = "ntrbf/"
}
