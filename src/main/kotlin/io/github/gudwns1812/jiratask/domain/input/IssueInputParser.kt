package io.github.gudwns1812.jiratask.domain.input

import io.github.gudwns1812.jiratask.domain.policy.WorkPolicy
import io.github.gudwns1812.jiratask.domain.validation.ValidationMessage
import java.time.DateTimeException
import java.time.LocalDate

sealed class IssueInputParseResult {
    data class Success(val input: IssueInput) : IssueInputParseResult()
    data class Failure(val errors: List<ValidationMessage>) : IssueInputParseResult()
}

/**
 * JSON 텍스트를 읽어 [Map]/[List]/기본 타입으로 역직렬화하는 것은 인프라 계층의 책임이다.
 * 이 파서는 이미 역직렬화된 구조(예: Gson이 만든 Map)를 입력으로 받아 도메인 타입으로 검증·변환한다.
 */
object IssueInputParser {
    private val EPIC_KEYS = setOf("type", "title")
    private val STORY_KEYS = setOf("type", "epicKey", "title", "branch", "sprint")
    private val TASK_KEYS = setOf(
        "type", "epicKey", "storyKey", "title", "assignee", "priority", "storyPoints", "sprint", "dueDate", "description",
    )
    private val TASK_DESCRIPTION_KEYS = setOf("summary", "deliverables")
    private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    fun parse(raw: Any?, policy: WorkPolicy = WorkPolicy.DEFAULT): IssueInputParseResult {
        val obj = raw as? Map<*, *>
            ?: return IssueInputParseResult.Failure(listOf(ValidationMessage("input", "입력은 JSON 객체여야 합니다.")))

        return when (obj["type"]) {
            "epic" -> parseEpic(obj)
            "story" -> parseStory(obj)
            "task" -> parseTask(obj, policy)
            else -> IssueInputParseResult.Failure(listOf(ValidationMessage("type", "type은 epic, story, task 중 하나여야 합니다.")))
        }
    }

    private fun parseEpic(obj: Map<*, *>): IssueInputParseResult {
        val errors = mutableListOf<ValidationMessage>()
        rejectUnknownKeys(obj, EPIC_KEYS, "", errors)
        val title = requiredText(obj, "title", errors)

        return if (errors.isEmpty()) IssueInputParseResult.Success(IssueInput.Epic(title!!)) else IssueInputParseResult.Failure(errors)
    }

    private fun parseStory(obj: Map<*, *>): IssueInputParseResult {
        val errors = mutableListOf<ValidationMessage>()
        rejectUnknownKeys(obj, STORY_KEYS, "", errors)
        val epicKey = requiredText(obj, "epicKey", errors)
        val title = requiredText(obj, "title", errors)
        val branch = requiredText(obj, "branch", errors)
        val sprint = optionalText(obj, "sprint", errors)

        return if (errors.isEmpty()) {
            IssueInputParseResult.Success(IssueInput.Story(epicKey!!, title!!, branch!!, sprint))
        } else {
            IssueInputParseResult.Failure(errors)
        }
    }

    private fun parseTask(obj: Map<*, *>, policy: WorkPolicy): IssueInputParseResult {
        val errors = mutableListOf<ValidationMessage>()
        rejectUnknownKeys(obj, TASK_KEYS, "", errors)
        val epicKey = requiredText(obj, "epicKey", errors)
        val storyKey = optionalText(obj, "storyKey", errors)
        val title = requiredText(obj, "title", errors)
        val assignee = requiredText(obj, "assignee", errors)
        val priority = requiredText(obj, "priority", errors)
        val storyPoints = requiredStoryPoints(obj, policy, errors)
        val sprint = requiredText(obj, "sprint", errors)
        val dueDate = requiredIsoDate(obj, errors)
        val description = requiredDescription(obj, errors)

        return if (errors.isEmpty()) {
            IssueInputParseResult.Success(
                IssueInput.Task(
                    epicKey = epicKey!!,
                    storyKey = storyKey,
                    title = title!!,
                    assignee = assignee!!,
                    priority = priority!!,
                    storyPoints = storyPoints!!,
                    sprint = sprint!!,
                    dueDate = dueDate!!,
                    description = description!!,
                ),
            )
        } else {
            IssueInputParseResult.Failure(errors)
        }
    }

    private fun rejectUnknownKeys(obj: Map<*, *>, allowed: Set<String>, pathPrefix: String, errors: MutableList<ValidationMessage>) {
        for (key in obj.keys) {
            val name = key as? String
            if (name == null || name !in allowed) {
                val path = if (pathPrefix.isEmpty()) (name ?: "input") else "$pathPrefix.${name ?: "?"}"
                errors.add(ValidationMessage(path, "허용되지 않는 필드입니다."))
            }
        }
    }

    private fun requiredText(obj: Map<*, *>, key: String, errors: MutableList<ValidationMessage>, path: String = key): String? {
        val text = (obj[key] as? String)?.trim()
        if (text.isNullOrEmpty()) {
            errors.add(ValidationMessage(path, "값을 입력해야 합니다."))
            return null
        }
        return text
    }

    private fun optionalText(obj: Map<*, *>, key: String, errors: MutableList<ValidationMessage>): String? {
        if (!obj.containsKey(key) || obj[key] == null) {
            return null
        }
        return requiredText(obj, key, errors)
    }

    private fun requiredStoryPoints(obj: Map<*, *>, policy: WorkPolicy, errors: MutableList<ValidationMessage>): Int? {
        val number = when (val value = obj["storyPoints"]) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt().takeIf { it.toDouble() == value }
            else -> null
        }
        if (number == null || number !in policy.storyPoints) {
            errors.add(ValidationMessage("storyPoints", "스토리 포인트는 ${policy.storyPoints.joinToString(", ")} 중 하나여야 합니다."))
            return null
        }
        return number
    }

    private fun requiredIsoDate(obj: Map<*, *>, errors: MutableList<ValidationMessage>): String? {
        val text = requiredText(obj, "dueDate", errors) ?: return null
        if (!isValidIsoDate(text)) {
            errors.add(ValidationMessage("dueDate", "기한은 유효한 YYYY-MM-DD 형식이어야 합니다."))
            return null
        }
        return text
    }

    private fun isValidIsoDate(value: String): Boolean {
        val match = ISO_DATE.matchEntire(value) ?: return false
        val (year, month, day) = match.destructured
        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            true
        } catch (error: DateTimeException) {
            false
        }
    }

    private fun requiredDescription(obj: Map<*, *>, errors: MutableList<ValidationMessage>): TaskDescription? {
        val descriptionObj = obj["description"] as? Map<*, *>
        if (descriptionObj == null) {
            errors.add(ValidationMessage("description", "값을 입력해야 합니다."))
            return null
        }

        rejectUnknownKeys(descriptionObj, TASK_DESCRIPTION_KEYS, "description", errors)
        val summary = requiredText(descriptionObj, "summary", errors, path = "description.summary")
        val deliverables = requiredDeliverables(descriptionObj, errors)

        return if (summary != null && deliverables != null) TaskDescription(summary, deliverables) else null
    }

    private fun requiredDeliverables(obj: Map<*, *>, errors: MutableList<ValidationMessage>): List<String>? {
        val raw = obj["deliverables"]
        if (raw !is List<*> || raw.isEmpty()) {
            errors.add(ValidationMessage("description.deliverables", "결과물을 하나 이상 입력해야 합니다."))
            return null
        }

        val values = mutableListOf<String>()
        var valid = true
        raw.forEachIndexed { index, item ->
            val text = (item as? String)?.trim()
            if (text.isNullOrEmpty()) {
                errors.add(ValidationMessage("description.deliverables.$index", "값을 입력해야 합니다."))
                valid = false
            } else {
                values.add(text)
            }
        }

        return if (valid) values else null
    }
}
