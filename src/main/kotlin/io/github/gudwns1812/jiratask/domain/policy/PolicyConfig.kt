package io.github.gudwns1812.jiratask.domain.policy

import io.github.gudwns1812.jiratask.domain.jira.JiraNamedValue
import java.net.URI
import java.net.URISyntaxException

class PolicyConfigurationError(message: String) : Exception(message)

data class JiraProjectRef(val id: String, val name: String)

data class JiraSettings(
    val baseUrl: String? = null,
    val email: String? = null,
    val project: JiraProjectRef? = null,
    val issueTypes: IssueTypes? = null,
) {
    data class IssueTypes(
        val story: JiraNamedValue? = null,
        val task: JiraNamedValue? = null,
        val epic: JiraNamedValue? = null,
    )
}

data class GitLabSettings(val baseUrl: String? = null, val defaultReviewers: List<String> = emptyList())

enum class AiProvider(val id: String) {
    CLAUDE("claude"),
    CODEX("codex"),
    ;

    companion object {

        fun of(id: String?): AiProvider? = entries.firstOrNull { it.id == id }

        fun ids(): List<String> = entries.map { it.id }
    }
}

data class AiCliSettings(val model: String? = null, val executablePath: String? = null) {

    fun isEmpty(): Boolean = model == null && executablePath == null
}

data class AiSettings(
    val enabled: Boolean = false,
    val provider: AiProvider = AiProvider.CLAUDE,
    val sendDiff: Boolean = true,
    val clis: Map<AiProvider, AiCliSettings> = emptyMap(),
) {

    fun cliSettings(which: AiProvider = provider): AiCliSettings = clis[which] ?: AiCliSettings()

    val model: String? get() = cliSettings().model

    val executablePath: String? get() = cliSettings().executablePath
}

data class JiraTaskConfig(
    val projectKey: String? = null,
    val jira: JiraSettings? = null,
    val gitlab: GitLabSettings? = null,
    val ai: AiSettings? = null,
) {
    companion object
}

private val PROJECT_KEY_PATTERN = Regex("""^[A-Z][A-Z0-9_]*$""")

private val LEGACY_POLICY_KEYS = setOf("areas", "workCategories", "storyPoints", "labels")

fun JiraTaskConfig.Companion.default(): JiraTaskConfig = JiraTaskConfig()

fun JiraTaskConfig.Companion.resolve(rawConfig: Any?): JiraTaskConfig {
    val obj = asConfigObject(rawConfig, "설정")
    val errors = mutableListOf<String>()

    rejectUnknownKeys(obj, LEGACY_POLICY_KEYS + setOf("projectKey", "jira", "gitlab", "ai"), "", errors)
    val projectKey = readProjectKey(obj, errors)
    val jira = readJiraSettings(obj, errors)
    val gitlab = readGitLabSettings(obj, errors)
    val ai = readAiSettings(obj, errors)

    if (errors.isNotEmpty()) {
        throw PolicyConfigurationError("설정 형식이 올바르지 않습니다. ${errors.joinToString("; ")}")
    }

    return JiraTaskConfig(projectKey = projectKey, jira = jira, gitlab = gitlab, ai = ai)
}

private fun readAiSettings(obj: Map<*, *>, errors: MutableList<String>): AiSettings? {
    if (!obj.containsKey("ai") || obj["ai"] == null) {
        return null
    }
    val raw = obj["ai"] as? Map<*, *> ?: run {
        errors += "ai: 값 형식이 올바르지 않습니다."
        return null
    }
    rejectUnknownKeys(raw, AI_KEYS, "ai", errors)

    return AiSettings(
        enabled = readOptionalBoolean(raw, "enabled", "ai.enabled", errors) ?: false,
        provider = readProvider(raw, errors) ?: AiProvider.CLAUDE,
        sendDiff = readOptionalBoolean(raw, "sendDiff", "ai.sendDiff", errors) ?: true,
        clis = AiProvider.entries.mapNotNull { provider ->
            readCliSettings(raw, provider, errors)?.let { provider to it }
        }.toMap(),
    )
}

private fun readProvider(raw: Map<*, *>, errors: MutableList<String>): AiProvider? {
    val text = readOptionalText(raw, "provider", "ai.provider", errors) ?: return null

    return AiProvider.of(text) ?: run {
        errors += "ai.provider: ${AiProvider.ids().joinToString(", ")} 중 하나여야 합니다."
        null
    }
}

private fun readCliSettings(
    raw: Map<*, *>,
    provider: AiProvider,
    errors: MutableList<String>,
): AiCliSettings? {
    val path = "ai.${provider.id}"
    val section = raw[provider.id]
    if (section != null) {
        val obj = section as? Map<*, *> ?: run {
            errors += "$path: 값 형식이 올바르지 않습니다."
            return null
        }
        rejectUnknownKeys(obj, setOf("model", "executablePath"), path, errors)

        return AiCliSettings(
            model = readOptionalText(obj, "model", "$path.model", errors),
            executablePath = readOptionalText(obj, "executablePath", "$path.executablePath", errors),
        ).takeIf { !it.isEmpty() }
    }

    if (provider != AiProvider.CLAUDE) {
        return null
    }

    return AiCliSettings(
        model = readOptionalText(raw, "model", "ai.model", errors),
        executablePath = readOptionalText(raw, "executablePath", "ai.executablePath", errors),
    ).takeIf { !it.isEmpty() }
}

private val AI_KEYS: Set<String> =
    setOf("enabled", "provider", "sendDiff", "model", "executablePath") + AiProvider.ids()

private fun readGitLabSettings(obj: Map<*, *>, errors: MutableList<String>): GitLabSettings? {
    val raw = obj["gitlab"] as? Map<*, *> ?: return null
    rejectUnknownKeys(raw, setOf("baseUrl", "defaultReviewers"), "gitlab", errors)

    val reviewers = readUniqueStrings(raw, "defaultReviewers", errors).orEmpty()
    val baseUrl = readOptionalText(raw, "baseUrl", "gitlab.baseUrl", errors)
    if (baseUrl != null && !isHttpUrl(baseUrl)) {
        errors += "gitlab.baseUrl: http 또는 https 주소여야 합니다."
        return null
    }

    if (baseUrl == null && reviewers.isEmpty()) {
        return null
    }

    return GitLabSettings(baseUrl, reviewers)
}

fun JiraTaskConfig.toRawMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    projectKey?.let { result["projectKey"] = it }
    jira?.let { result["jira"] = it.toRawMap() }
    gitlab?.let { settings ->
        val section = buildMap<String, Any> {
            settings.baseUrl?.let { put("baseUrl", it) }
            settings.defaultReviewers.takeIf { it.isNotEmpty() }?.let { put("defaultReviewers", it) }
        }
        if (section.isNotEmpty()) {
            result["gitlab"] = section
        }
    }
    ai?.let { result["ai"] = it.toRawMap() }
    return result
}

private fun AiSettings.toRawMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>(
        "enabled" to enabled,
        "provider" to provider.id,
        "sendDiff" to sendDiff,
    )

    for (which in AiProvider.entries) {
        val settings = clis[which] ?: continue
        val section = buildMap<String, Any> {
            settings.model?.let { put("model", it) }
            settings.executablePath?.let { put("executablePath", it) }
        }
        if (section.isNotEmpty()) {
            result[which.id] = section
        }
    }

    return result
}

fun JiraSettings.toRawMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    baseUrl?.let { result["baseUrl"] = it }
    email?.let { result["email"] = it }
    project?.let { result["project"] = mapOf("id" to it.id, "name" to it.name) }
    issueTypes?.toRawMap()?.let { if (it.isNotEmpty()) result["issueTypes"] = it }
    return result
}

private fun JiraSettings.IssueTypes.toRawMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    story?.let { result["story"] = mapOf("id" to it.id, "name" to it.name) }
    task?.let { result["task"] = mapOf("id" to it.id, "name" to it.name) }
    epic?.let { result["epic"] = mapOf("id" to it.id, "name" to it.name) }
    return result
}

fun JiraTaskConfig.mergeWith(update: Map<String, Any?>): JiraTaskConfig {
    val merged = toRawMap().toMutableMap()

    for (section in DEEP_MERGED_SECTIONS) {
        val incoming = update[section] as? Map<*, *> ?: continue
        val current = merged[section] as? Map<*, *> ?: emptyMap<String, Any?>()
        merged[section] = deepMerge(current, incoming)
    }
    merged.putAll(update.filterKeys { it !in DEEP_MERGED_SECTIONS })

    return JiraTaskConfig.resolve(merged)
}

private fun mergeLabels(
    defaults: Map<String, String>,
    allowedKeys: List<String>,
    overrides: Map<String, String>?,
    kind: String,
): Map<String, String> {
    for (key in overrides?.keys.orEmpty()) {
        if (key !in allowedKeys) {
            throw PolicyConfigurationError("$kind 레이블 '$key'는 설정된 목록에 없습니다.")
        }
    }

    val applicableDefaults = defaults.filterKeys { it in allowedKeys }
    return applicableDefaults + overrides.orEmpty()
}

private fun asConfigObject(raw: Any?, subject: String): Map<*, *> {
    if (raw == null) {
        return emptyMap<String, Any?>()
    }
    return raw as? Map<*, *> ?: throw PolicyConfigurationError("$subject 형식이 올바르지 않습니다. config: 값은 객체여야 합니다.")
}

private fun rejectUnknownKeys(obj: Map<*, *>, allowed: Set<String>, pathPrefix: String, errors: MutableList<String>) {
    for (key in obj.keys) {
        val name = key as? String
        if (name == null || name !in allowed) {
            val path = if (pathPrefix.isEmpty()) (name ?: "config") else "$pathPrefix.${name ?: "?"}"
            errors += "$path: 허용되지 않는 필드입니다."
        }
    }
}

private fun readUniqueStrings(obj: Map<*, *>, key: String, errors: MutableList<String>): List<String>? {
    if (!obj.containsKey(key) || obj[key] == null) {
        return null
    }
    val raw = obj[key]
    if (raw !is List<*> || raw.isEmpty()) {
        errors += "$key: 값을 하나 이상 입력해야 합니다."
        return null
    }

    val values = mutableListOf<String>()
    for (item in raw) {
        val text = (item as? String)?.trim()
        if (text.isNullOrEmpty()) {
            errors += "$key: 값을 입력해야 합니다."
            return null
        }
        values.add(text)
    }

    if (values.toSet().size != values.size) {
        errors += "$key: 중복된 값은 사용할 수 없습니다."
        return null
    }

    return values
}

private fun readLabelsObject(obj: Map<*, *>, errors: MutableList<String>): Map<*, *>? {
    if (!obj.containsKey("labels") || obj["labels"] == null) {
        return null
    }
    val raw = obj["labels"] as? Map<*, *>
    if (raw == null) {
        errors += "labels: 값 형식이 올바르지 않습니다."
        return null
    }
    rejectUnknownKeys(raw, setOf("areas", "categories"), "labels", errors)
    return raw
}

private fun readStringMap(obj: Map<*, *>?, key: String): Map<String, String>? {
    val raw = obj?.get(key) as? Map<*, *> ?: return null
    return raw.entries.associate { (k, v) -> k.toString() to v.toString() }
}

private fun readProjectKey(obj: Map<*, *>, errors: MutableList<String>): String? {
    if (!obj.containsKey("projectKey") || obj["projectKey"] == null) {
        return null
    }
    val text = (obj["projectKey"] as? String)?.trim()
    if (text.isNullOrEmpty() || !PROJECT_KEY_PATTERN.matches(text)) {
        errors += "projectKey: 프로젝트 키는 대문자 영문으로 시작하고 대문자 영문·숫자·밑줄만 사용할 수 있습니다."
        return null
    }
    return text
}

private fun readJiraSettings(obj: Map<*, *>, errors: MutableList<String>): JiraSettings? {
    if (!obj.containsKey("jira") || obj["jira"] == null) {
        return null
    }
    val raw = obj["jira"] as? Map<*, *>
    if (raw == null) {
        errors += "jira: 값 형식이 올바르지 않습니다."
        return null
    }

    rejectUnknownKeys(raw, setOf("baseUrl", "email", "project", "issueTypes", "statuses"), "jira", errors)

    val baseUrl = readBaseUrl(raw, errors)
    val email = readOptionalText(raw, "email", "jira.email", errors)
    val project = readProjectRef(raw, errors)
    val issueTypes = readIssueTypes(raw, errors)
    readStatuses(raw, errors)

    if (listOf(baseUrl, email, project, issueTypes).all { it == null }) {
        errors += "jira: 하나 이상의 값이 필요합니다."
        return null
    }

    return JiraSettings(baseUrl, email, project, issueTypes)
}

private fun readBaseUrl(obj: Map<*, *>, errors: MutableList<String>): String? {
    val text = readOptionalText(obj, "baseUrl", "jira.baseUrl", errors) ?: return null
    if (!isHttpUrl(text)) {
        errors += "jira.baseUrl: Jira 주소는 http 또는 https URL이어야 합니다."
        return null
    }
    return text
}

private fun isHttpUrl(value: String): Boolean = try {
    val scheme = URI(value).scheme
    scheme == "http" || scheme == "https"
} catch (error: URISyntaxException) {
    false
}

private fun readOptionalText(obj: Map<*, *>, key: String, path: String, errors: MutableList<String>): String? {
    if (!obj.containsKey(key) || obj[key] == null) {
        return null
    }
    return readRequiredText(obj, key, path, errors)
}

private fun readOptionalBoolean(obj: Map<*, *>, key: String, path: String, errors: MutableList<String>): Boolean? {
    if (!obj.containsKey(key) || obj[key] == null) {
        return null
    }

    val value = obj[key] as? Boolean
    if (value == null) {
        errors += "$path: 값 형식이 올바르지 않습니다."
        return null
    }
    return value
}

private fun readRequiredText(obj: Map<*, *>, key: String, path: String, errors: MutableList<String>): String? {
    val text = (obj[key] as? String)?.trim()
    if (text.isNullOrEmpty()) {
        errors += "$path: 값을 입력해야 합니다."
        return null
    }
    return text
}

private fun readProjectRef(obj: Map<*, *>, errors: MutableList<String>): JiraProjectRef? {
    if (!obj.containsKey("project") || obj["project"] == null) {
        return null
    }
    val raw = obj["project"] as? Map<*, *>
    if (raw == null) {
        errors += "jira.project: 값 형식이 올바르지 않습니다."
        return null
    }
    rejectUnknownKeys(raw, setOf("id", "name"), "jira.project", errors)
    val id = readRequiredText(raw, "id", "jira.project.id", errors)
    val name = readRequiredText(raw, "name", "jira.project.name", errors)
    return if (id != null && name != null) JiraProjectRef(id, name) else null
}

private fun readNamedValue(obj: Map<*, *>, path: String, errors: MutableList<String>): JiraNamedValue? {
    rejectUnknownKeys(obj, setOf("id", "name"), path, errors)
    val id = readRequiredText(obj, "id", "$path.id", errors)
    val name = readRequiredText(obj, "name", "$path.name", errors)
    return if (id != null && name != null) JiraNamedValue(id, name) else null
}

private fun readIssueTypes(obj: Map<*, *>, errors: MutableList<String>): JiraSettings.IssueTypes? {
    if (!obj.containsKey("issueTypes") || obj["issueTypes"] == null) {
        return null
    }
    val raw = obj["issueTypes"] as? Map<*, *>
    if (raw == null) {
        errors += "jira.issueTypes: 값 형식이 올바르지 않습니다."
        return null
    }
    rejectUnknownKeys(raw, setOf("story", "task", "epic"), "jira.issueTypes", errors)
    val story = readOptionalNamedValue(raw, "story", "jira.issueTypes.story", errors)
    val task = readOptionalNamedValue(raw, "task", "jira.issueTypes.task", errors)
    val epic = readOptionalNamedValue(raw, "epic", "jira.issueTypes.epic", errors)
    return JiraSettings.IssueTypes(story, task, epic)
}

private fun readOptionalNamedValue(obj: Map<*, *>, key: String, path: String, errors: MutableList<String>): JiraNamedValue? {
    if (!obj.containsKey(key) || obj[key] == null) {
        return null
    }
    val raw = obj[key] as? Map<*, *>
    if (raw == null) {
        errors += "$path: 값 형식이 올바르지 않습니다."
        return null
    }
    return readNamedValue(raw, path, errors)
}

private fun readStatuses(obj: Map<*, *>, errors: MutableList<String>) {
    val raw = obj["statuses"] as? Map<*, *> ?: return
    rejectUnknownKeys(raw, setOf("story", "task"), "jira.statuses", errors)
}

private fun deepMerge(current: Map<*, *>, incoming: Map<*, *>): Map<Any?, Any?> {
    val merged = current.toMutableMap<Any?, Any?>()
    for ((key, value) in incoming) {
        val existing = merged[key]
        merged[key] = if (existing is Map<*, *> && value is Map<*, *>) deepMerge(existing, value) else value
    }
    return merged
}

private val DEEP_MERGED_SECTIONS = setOf("jira", "ai", "gitlab")
