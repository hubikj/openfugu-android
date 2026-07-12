package org.hubik.openfugu.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Accessors over kotlinx.serialization's JsonObject mirroring the org.json
 * semantics this codebase was written against: plain getters throw on a
 * missing key (callers catch and treat the payload as unreadable), "OrNull"
 * getters treat a missing key and an explicit JSON null the same, and
 * getters with a default fall back for keys written by older app versions.
 */
internal fun JsonObject.string(key: String): String = prim(key).content
internal fun JsonObject.stringOrNull(key: String): String? = primOrNull(key)?.content
internal fun JsonObject.long(key: String): Long = prim(key).long
internal fun JsonObject.longOrNull(key: String): Long? = primOrNull(key)?.long
internal fun JsonObject.long(key: String, default: Long): Long = primOrNull(key)?.long ?: default
internal fun JsonObject.int(key: String): Int = prim(key).int
internal fun JsonObject.double(key: String): Double = prim(key).double
internal fun JsonObject.doubleOrNull(key: String): Double? = primOrNull(key)?.double
internal fun JsonObject.double(key: String, default: Double): Double = primOrNull(key)?.double ?: default
internal fun JsonObject.boolean(key: String): Boolean = prim(key).boolean
internal fun JsonObject.boolean(key: String, default: Boolean): Boolean = primOrNull(key)?.boolean ?: default
internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

private fun JsonObject.prim(key: String): JsonPrimitive = getValue(key).jsonPrimitive
private fun JsonObject.primOrNull(key: String): JsonPrimitive? =
    get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive
