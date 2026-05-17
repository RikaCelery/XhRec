package github.rikacelery.v3.utils

import kotlinx.serialization.json.*

fun JsonElement.string(name: String): String =
    jsonObject[name]?.jsonPrimitive?.content ?: throw NoSuchElementException("Missing: $name")

fun JsonElement.int(name: String): Int =
    jsonObject[name]?.jsonPrimitive?.content?.toInt() ?: throw NoSuchElementException("Missing: $name")

fun JsonElement.long(name: String): Long =
    jsonObject[name]?.jsonPrimitive?.content?.toLong() ?: throw NoSuchElementException("Missing: $name")

fun JsonElement.booleanOrElse(name: String, default: Boolean = false): Boolean =
    jsonObject[name]?.jsonPrimitive?.booleanOrNull ?: default

fun JsonElement.jsonArr(name: String): JsonArray =
    jsonObject[name]?.jsonArray ?: throw NoSuchElementException("Missing: $name")
