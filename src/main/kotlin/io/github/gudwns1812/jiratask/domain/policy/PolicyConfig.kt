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

/** MR을 만들 GitLab. 토큰은 여기 두지 않고 자격 증명 저장소에 보관한다. */
data class GitLabSettings(val baseUrl: String? = null)

/**
 * MR 제목·본문을 AI에게 맡길지, 무엇으로 맡길지.
 *
 * API 키는 여기에도 자격 증명 저장소에도 두지 않는다. 사용자가 이미 로그인해 둔 Claude Code를
 * 그대로 쓰기 때문이다 — 구독 요금제로 쓰려면 그 길뿐이다.
 */
data class AiSettings(
    val enabled: Boolean = false,
    /** 비어 있으면 Claude Code에 설정된 사용자 자신의 모델을 따른다. */
    val model: String? = null,
    /** PATH에서 찾지 못할 때만 쓴다. */
    val executablePath: String? = null,
    /**
     * 커밋 메시지 초안에 변경 내용(diff)까지 보낼지.
     *
     * MR 초안은 커밋 제목이라는 요약이 이미 있어 경로만으로도 쓸 수 있지만, 커밋은 아직 만들지
     * 않은 것이라 경로만 주면 모델이 지어낸다. 그래서 이 기능은 기본으로 켜 두되,
     * **소스 코드가 밖으로 나가는 유일한 자리**이므로 끌 수 있게 남긴다.
     */
    val sendDiff: Boolean = true,
)

data class JiraTaskConfig(
    val projectKey: String? = null,
    val jira: JiraSettings? = null,
    val gitlab: GitLabSettings? = null,
    val ai: AiSettings? = null,
) {
    companion object
}

private val PROJECT_KEY_PATTERN = Regex("""^[A-Z][A-Z0-9_]*$""")

/**
 * 업무 정책(영역·업무 분류·스토리 포인트·레이블)을 저장하던 키들이다. 이슈를 만들 때만 쓰였으므로
 * 더는 읽지 않지만, 예전 버전이 저장해 둔 설정 파일이 이 키를 갖고 있다. 허용 목록에는 남겨
 * 그런 파일이 "허용되지 않는 필드" 오류로 막히지 않게 하고, 값은 읽지도 다시 쓰지도 않는다.
 */
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

    // projectKey 오류와 jira 오류를 한 번에 모아 던진다.
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
    rejectUnknownKeys(raw, setOf("enabled", "model", "executablePath", "sendDiff"), "ai", errors)

    return AiSettings(
        enabled = readOptionalBoolean(raw, "enabled", "ai.enabled", errors) ?: false,
        model = readOptionalText(raw, "model", "ai.model", errors),
        executablePath = readOptionalText(raw, "executablePath", "ai.executablePath", errors),
        // 예전 설정 파일에는 이 키가 없다. 없으면 기본값(켜짐)을 쓴다.
        sendDiff = readOptionalBoolean(raw, "sendDiff", "ai.sendDiff", errors) ?: true,
    )
}

private fun readGitLabSettings(obj: Map<*, *>, errors: MutableList<String>): GitLabSettings? {
    val raw = obj["gitlab"] as? Map<*, *> ?: return null
    rejectUnknownKeys(raw, setOf("baseUrl"), "gitlab", errors)

    val baseUrl = readOptionalText(raw, "baseUrl", "gitlab.baseUrl", errors) ?: return null
    if (!isHttpUrl(baseUrl)) {
        errors += "gitlab.baseUrl: http 또는 https 주소여야 합니다."
        return null
    }
    return GitLabSettings(baseUrl)
}

/** [JiraTaskConfig.resolve]가 다시 읽을 수 있는 원시 구조로 되돌린다(설정 파일 저장용). */
fun JiraTaskConfig.toRawMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    projectKey?.let { result["projectKey"] = it }
    jira?.let { result["jira"] = it.toRawMap() }
    gitlab?.baseUrl?.let { result["gitlab"] = mapOf("baseUrl" to it) }
    ai?.let { result["ai"] = it.toRawMap() }
    return result
}

private fun AiSettings.toRawMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>("enabled" to enabled, "sendDiff" to sendDiff)
    model?.let { result["model"] = it }
    executablePath?.let { result["executablePath"] = it }
    return result
}

/** 설정 저장소가 다시 읽을 수 있는 원시 구조로 되돌린다(초기 설정 마법사가 저장할 때 사용). */
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

/**
 * 이 설정에 [update]를 얕게 병합한 뒤 다시 검증한다. `jira`는 한 단계만 병합해
 * (예: baseUrl만 갱신해도 issueTypes는 유지) `config set`과 같은 동작이다.
 */
fun JiraTaskConfig.mergeWith(update: Map<String, Any?>): JiraTaskConfig {
    val merged = toRawMap().toMutableMap()

    // 키가 여럿인 절은 한 겹 깊이로 합친다. 통째로 덮으면 이번에 건드리지 않은 값이 사라진다 —
    // `ai.enabled`만 저장했다고 `ai.model`이 없어지면 안 된다.
    for (section in DEEP_MERGED_SECTIONS) {
        val incoming = update[section] as? Map<*, *> ?: continue
        val current = merged[section] as? Map<*, *> ?: emptyMap<String, Any?>()
        merged[section] = current + incoming
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
    // `statuses`는 어느 상태가 "진행 중"인지 저장하던 자리다. 이제 Jira의 상태 카테고리로
    // 그때그때 고르므로 읽지 않는다. 예전 설정 파일에 남아 있어도 오류로 막지 않는다.
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

/**
 * 없거나 null이면 null, 참·거짓이 아니면 오류.
 *
 * `toString().toBoolean()`을 쓰지 않는다 — 그건 `"maybe"`를 조용히 `false`로 삼켜서,
 * 잘못 적은 설정이 "꺼 둔 것"처럼 보이게 만든다.
 */
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

/** 예전 설정의 `jira.statuses` 형식만 확인하고 값은 버린다. 오류로 막지 않기 위해 남겨 둔 자리다. */
private fun readStatuses(obj: Map<*, *>, errors: MutableList<String>) {
    val raw = obj["statuses"] as? Map<*, *> ?: return
    rejectUnknownKeys(raw, setOf("story", "task"), "jira.statuses", errors)
}

/** 한 겹 깊이로 합칠 절들. 키가 하나뿐인 절은 통째로 덮여도 잃을 것이 없다. */
private val DEEP_MERGED_SECTIONS = setOf("jira", "ai")
