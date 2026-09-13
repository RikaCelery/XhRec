package github.rikacelery.cutter.events

import github.rikacelery.cutter.media.Power
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Parses XhRec `.event` files.
 *
 * ## Envelope
 *
 * Two generations of writer exist and both appear in the corpus:
 *
 *   - `{"push":{"channel":"newChatMessage@123","pub":{"data":{...}}}}`
 *   - `{"type":"goalChanged","data":{...}}`
 *
 * plus the bare `data` object with no envelope at all.
 *
 * ## Timestamps — the part that is easy to get wrong
 *
 * Only message-bearing events carry `message.createdAt`. `goalChanged`,
 * `groupShow` and `interactiveToyStatusChanged` — three of the most interesting
 * kinds — carry none, so their time has to be interpolated (see [EventTimeMapper]).
 *
 * Worse, a naive "take the largest ISO-8601 string in the payload" scan picks up
 * *deadline* fields: `userBanned.ban.expiredAt` is a full day ahead. Measured on
 * real files that mistake puts events up to **85920 s** away from where they
 * belong. [OCCURRENCE_KEYS] is therefore a whitelist of occurrence fields and
 * [DEADLINE_KEYS] is explicitly rejected; with that rule the worst observed error
 * drops to **1.0 s** (the last event lands within a second of the recording's end).
 */
object EventParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Where an event's *occurrence* time lives, in priority order.
     *
     * This is a path whitelist rather than a key-name whitelist on purpose. The
     * obvious "walk the payload for any field called `createdAt`" rule fails on real
     * data: `userBanned.ban.createdAt` is when the ban was originally issued, not
     * when this event fired. One recording in the corpus re-broadcasts a ban whose
     * `createdAt` is **124 days** before the stream, which drags every derived time
     * with it. Nested `ban`/`user`/`model` objects are historical records and are
     * deliberately not consulted.
     *
     * Anything not listed here simply has no own timestamp and gets interpolated
     * from its neighbours, which is both correct and cheaper than guessing.
     */
    private val TIMESTAMP_PATHS: List<List<String>> = listOf(
        listOf("message", "createdAt"),
        listOf("show", "createdAt"),
        listOf("statusChangedAt"),
        listOf("startedAt"),
        listOf("createdAt"),
        listOf("deletedAt")
    )

    fun parseFile(file: File): List<ParsedEvent> =
        file.bufferedReader().useLines { lines -> parse(lines) }

    fun parse(lines: Sequence<String>): List<ParsedEvent> {
        val out = ArrayList<ParsedEvent>(256)
        var index = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            runCatching { parseLine(trimmed, index) }.getOrNull()?.let { out.add(it) }
            index++
        }
        return out
    }

    fun parseLine(line: String, lineIndex: Int): ParsedEvent? {
        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val (channel, data) = unwrap(root)
        val message = (data["message"] as? JsonObject)
        val messageType = message?.get("type")?.jsonPrimitive?.contentOrNull

        // A payload that only carries a toy `settings` block is a menu broadcast,
        // not an action, whichever channel name it arrived under.
        val kind = if (message == null && data["settings"] is JsonObject) {
            EventKind.TOY_SETTINGS
        } else {
            classify(channel, messageType)
        }

        val details = message?.get("details") as? JsonObject
        val userData = message?.get("userData") as? JsonObject
        val lovense = details?.get("lovenseDetails") as? JsonObject
        val toyDetail = lovense?.get("detail") as? JsonObject
        val tipData = details?.get("tipData") as? JsonObject
        val goalObject = data["goal"] as? JsonObject

        return ParsedEvent(
            lineIndex = lineIndex,
            kind = kind,
            channel = channel,
            ownEpochMs = findOccurrence(data),
            user = firstNonNull(
                stringOf(toyDetail, "name"),
                stringOf(details, "name"),
                stringOf(userData, "username"),
                stringOf(data["user"] as? JsonObject, "username")
            ),
            text = firstNonNull(
                stringOf(details, "body"),
                stringOf(lovense, "text")
            ),
            amount = firstNonNull(
                longOf(toyDetail, "amount"),
                longOf(details, "amount"),
                longOf(data, "kingAmount")
            ),
            tipSource = stringOf(details, "source"),
            triggerType = stringOf(tipData, "triggerType"),
            toyAction = stringOf(toyDetail, "specialActualValue"),
            toySeconds = doubleOf(toyDetail, "time"),
            toyPower = Power.from(stringOf(toyDetail, "power")),
            goal = parseGoal(goalObject, details),
            showState = stringOf(data, "state"),
            streamStatus = statusOf(data["status"])
        )
    }

    /** Strips the optional envelope, returning the payload and its channel name. */
    private fun unwrap(root: JsonObject): Pair<String, JsonObject> {
        val push = root["push"] as? JsonObject
        if (push != null) {
            val channel = push["channel"]?.jsonPrimitive?.contentOrNull?.substringBefore('@') ?: "unknown"
            val data = ((push["pub"] as? JsonObject)?.get("data") as? JsonObject) ?: JsonObject(emptyMap())
            return channel to data
        }
        val type = root["type"]?.jsonPrimitive?.contentOrNull
        val data = root["data"] as? JsonObject
        if (type != null && data != null) return type to data
        // Bare payload: the inner `type` names the event, and the payload is the root.
        return (type ?: "unknown") to root
    }

    private fun classify(channel: String, messageType: String?): EventKind =
        when (channel.lowercase()) {
            "interactivetoystatuschanged" -> EventKind.TOY_SETTINGS
            "newchatmessage" -> when (messageType?.lowercase()) {
                "text" -> EventKind.CHAT
                "tip", "privatetip", "usertipped" -> EventKind.TIP
                "lovense" -> EventKind.TOY_CMD
                "thresholdgoal", "goal", "repeatgoal" -> EventKind.GOAL
                "newking" -> EventKind.KING
                else -> EventKind.OTHER
            }
            "goalchanged" -> EventKind.GOAL
            "lovense" -> EventKind.TOY_CMD
            "groupshow", "privatestartedv3", "privateendedv3" -> EventKind.SHOW
            "newking" -> EventKind.KING
            "userbanned" -> EventKind.BAN
            "streamchanged", "broadcastchanged", "broadcastsettingschanged" -> EventKind.STREAM
            else -> EventKind.OTHER
        }

    private fun parseGoal(goal: JsonObject?, details: JsonObject?): GoalInfo? {
        val source = goal ?: details ?: return null
        val info = GoalInfo(
            goal = longOf(source, "goal"),
            spent = longOf(source, "spent"),
            left = longOf(source, "left"),
            description = stringOf(source, "description"),
            isEnabled = stringOf(source, "isEnabled")?.toBooleanStrictOrNull()
        )
        return info.takeIf {
            it.goal != null || it.spent != null || it.left != null || it.description != null
        }
    }

    /**
     * The event's own occurrence timestamp, if it has one.
     *
     * Only [TIMESTAMP_PATHS] are consulted — see the note there on why a generic
     * deep scan is wrong. [EventTimeMapper] additionally drops any surviving
     * timestamp that breaks the file's append ordering.
     */
    fun findOccurrence(data: JsonObject): Long? {
        for (path in TIMESTAMP_PATHS) {
            val value = resolve(data, path) as? JsonPrimitive ?: continue
            val text = value.contentOrNull ?: continue
            parseInstant(text)?.let { return it }
        }
        return null
    }

    private fun resolve(data: JsonObject, path: List<String>): kotlinx.serialization.json.JsonElement? {
        var current: kotlinx.serialization.json.JsonElement = data
        for (segment in path) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }

    /** Parses `2026-03-18T11:59:36Z` and friends, including sub-second precision. */
    fun parseInstant(text: String): Long? {
        if (text.length < 19 || text.getOrNull(4) != '-') return null
        return runCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrNull()
    }

    private fun statusOf(element: kotlinx.serialization.json.JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element["isEnabled"]?.jsonPrimitive?.contentOrNull
        else -> null
    }

    private fun stringOf(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }

    private fun longOf(obj: JsonObject?, key: String): Long? {
        val p = obj?.get(key) as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    private fun doubleOf(obj: JsonObject?, key: String): Double? {
        val p = obj?.get(key) as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
    }

    private fun <T> firstNonNull(vararg values: T?): T? = values.firstOrNull { it != null }
}
