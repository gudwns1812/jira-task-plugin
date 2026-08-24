package io.github.gudwns1812.jiratask.infrastructure

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Gson [JsonElement] 트리를 도메인 계층이 다루는 Map/List/기본 타입 구조로 변환한다. */
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

/**
 * Map/List/기본 타입 구조를 Gson [JsonElement] 트리로 되돌린다(설정 저장용).
 *
 * `JsonObject`/`JsonArray`는 그 자체가 `Iterable`/멤버를 가지고 있어 `apply { entries... }`,
 * `apply { forEach... }`처럼 쓰면 새로 만든 빈 컨테이너 자신을 순회하는 실수를 하기 쉽다.
 * 원본 컬렉션을 별도 지역 변수로 받아 순회함으로써 이를 피한다.
 */
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
