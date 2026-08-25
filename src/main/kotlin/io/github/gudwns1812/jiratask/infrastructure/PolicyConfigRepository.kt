package io.github.gudwns1812.jiratask.infrastructure

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.gudwns1812.jiratask.domain.policy.JiraTaskConfig
import io.github.gudwns1812.jiratask.domain.policy.PolicyConfigurationError
import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.domain.policy.default
import io.github.gudwns1812.jiratask.domain.policy.mergeWith
import io.github.gudwns1812.jiratask.domain.policy.resolve
import io.github.gudwns1812.jiratask.domain.policy.toRawMap

class PolicyConfigRepository(
    private val storage: JiraTaskConfigStorage = JiraTaskConfigStorage.getInstance(),
) {

    fun hasConfig(): Boolean = storage.configJson.isNotBlank()

    fun loadWorkPolicy(): WorkPolicy = loadConfig().policy

    fun loadConfig(): JiraTaskConfig {
        val json = storage.configJson
        if (json.isBlank()) {
            return JiraTaskConfig.default()
        }

        val raw = try {
            JsonParser.parseString(json).toKotlinValue()
        } catch (error: Exception) {
            throw PolicyConfigurationError("정책 설정을 읽을 수 없습니다.")
        }

        return JiraTaskConfig.resolve(raw)
    }

    fun setProjectKey(projectKey: String): Unit = saveConfig(mapOf("projectKey" to projectKey))

    fun saveConfig(update: Map<String, Any?>) {
        val current = if (hasConfig()) loadConfig() else JiraTaskConfig.default()
        val next = current.mergeWith(update)
        storage.configJson = Gson().toJson(next.toRawMap().toJsonElement())
    }
}
