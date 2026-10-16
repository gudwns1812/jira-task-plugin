package io.github.gudwns1812.jiratask.infrastructure

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

fun JsonElement.toKotlinValue(): Any? = when {
    isJsonNull -> null
    isJsonPrimitive -> asJsonPrimitive.let { primitive ->
        when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asNumber.toDouble().let { value ->
                if (value == Math.floor(value) && !value.isInfinite()) value.toLong() else value
            }
            else -> primitive.asString
        }
    }
    isJsonArray -> asJsonArray.map { it.toKotlinValue() }
    isJsonObject -> asJsonObject.entrySet().associate { (key, value) -> key to value.toKotlinValue() }
    else -> null
}

fun Any?.toJsonElement(): JsonElement = when (val value = this) {
    null -> JsonNull.INSTANCE
    is Map<*, *> -> {
        val result = JsonObject()
        value.forEach { (key, entryValue) -> result.add(key.toString(), entryValue.toJsonElement()) }
        result
    }
    is List<*> -> {
        val result = JsonArray()
        value.forEach { item -> result.add(item.toJsonElement()) }
        result
    }
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> throw IllegalArgumentException("JSON으로 직렬화할 수 없는 값입니다: $value")
}
