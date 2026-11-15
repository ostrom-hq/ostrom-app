// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/CloudMapping.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import run.ostrom.app.domain.models.AgentQuestion
import run.ostrom.app.domain.models.AgentQuestionOption
import run.ostrom.app.domain.models.BackgroundTask
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.KnownAgentState
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.domain.models.TurnPhase
import run.ostrom.core.api.models.CloudEvent
import run.ostrom.core.api.models.CloudEventPage
import run.ostrom.core.api.models.CloudEventTypes
import run.ostrom.core.api.models.CloudSubagent

// Times are ISO-8601 strings in Ostrom's contract (epoch milliseconds in TeamCode's): an unreadable one reads as 0.

internal fun CloudSubagent.domain() = KnownAgent(
    id, type?.takeIf { it.isNotBlank() }, description,
    when (state) { "running" -> KnownAgentState.RUNNING; "completed" -> KnownAgentState.COMPLETED; "failed" -> KnownAgentState.FAILED; else -> KnownAgentState.UNKNOWN },
    millisOf(startedAt) ?: 0, millisOf(endedAt), steps, parentId, taskId,
)

internal fun CloudEventPage.domain() = TranscriptPage(events.map { it.domain() }, nextBefore, lastSeq ?: events.lastOrNull()?.seq ?: 0)

/** Every type and field is optional: an unknown type or a missing field never fails the transcript. */
internal fun CloudEvent.domain(): TranscriptEvent {
    val at = millisOf(this.at) ?: 0
    // A subagent's event names its thread in its data, and in its feed (`runners.cloud.transcript.feeds.v1`).
    val parent = data.text("parentToolUseId") ?: feed
    // The server replaces an event of more than 60 000 bytes with this marker.
    if (data["truncated"].boolean() == true && data["originalBytes"] != null) return TranscriptEvent.Truncated(seq, at, type, data["originalBytes"].long())
    return when (type) {
        CloudEventTypes.USER_MESSAGE -> TranscriptEvent.UserMessage(seq, at, data.text("text").orEmpty(), data.text("inputId"), data.text("authorId"))
        CloudEventTypes.AGENT_MESSAGE -> TranscriptEvent.AgentMessage(seq, at, data.text("text").orEmpty(), parent)
        CloudEventTypes.TOOL_CALL -> TranscriptEvent.ToolCall(
            seq, at, data.text("id").orEmpty(), data.text("name") ?: "tool", data["input"].pretty(), toolSummary(data["input"]), parent,
        )
        CloudEventTypes.TOOL_RESULT -> TranscriptEvent.ToolResult(
            seq, at, data.text("id").orEmpty(), data["output"].let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: it.pretty() },
            data["isError"].boolean() == true, data["exitCode"].int(), parent,
        )
        CloudEventTypes.TURN -> TranscriptEvent.Turn(seq, at, TurnPhase.of(data.text("phase")), data["durationMs"].long(), data["steps"].int())
        CloudEventTypes.STATUS -> TranscriptEvent.Status(seq, at, data.text("agent"), data.text("model"), data["exitCode"].int())
        CloudEventTypes.ERROR -> TranscriptEvent.Error(seq, at, data.text("code"), data.text("message"))
        CloudEventTypes.INPUT -> TranscriptEvent.InputChanged(seq, at, data.text("inputId"), data.text("status"), data.text("reason"), data.text("requestId"))
        CloudEventTypes.PERMISSION_REQUEST -> permissionRequest(seq, at, data)
        CloudEventTypes.PERMISSION_EXPIRED -> TranscriptEvent.PermissionExpired(seq, at, data.text("requestId"))
        CloudEventTypes.TASKS -> TranscriptEvent.Tasks(seq, at, (data["tasks"] as? JsonArray).orEmpty().mapNotNull { element ->
            val task = element as? JsonObject ?: return@mapNotNull null
            BackgroundTask(task.text("id") ?: return@mapNotNull null, task.text("type") ?: "task", task.text("description").orEmpty(), task.text("subagentType"), task.text("toolUseId"), task["startedAt"].long())
        }, data["stoppable"].boolean() == true, data["scheduled"].int()?.coerceAtLeast(0) ?: 0)
        "transfer" -> TranscriptEvent.Transferred(seq, at, data.text("fromMemberId"), data.text("toMemberId"))
        else -> TranscriptEvent.Unknown(seq, at, type)
    }
}

/**
 * A permission request. An input that is not an object counts as left out, as the server reads it: such a request can
 * only be denied. Questions keep their index in `input.questions`, the one the server takes back, even when one of them
 * cannot be read; a repeated question text is kept once (Claude Code keys answers by text).
 */
private fun permissionRequest(seq: Long, at: Long, data: JsonObject): TranscriptEvent.PermissionRequest {
    val input = data["input"] as? JsonObject
    val question = data["question"].boolean() == true
    val questions = if (!question || input == null) emptyList() else (input["questions"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
        val fields = element as? JsonObject ?: return@mapIndexedNotNull null
        val text = fields.text("question") ?: return@mapIndexedNotNull null
        val options = (fields["options"] as? JsonArray).orEmpty().map { option ->
            val o = option as? JsonObject
            AgentQuestionOption(o?.text("label").orEmpty(), o?.text("description")?.takeIf(String::isNotBlank))
        }
        AgentQuestion(index, text, fields.text("header")?.takeIf(String::isNotBlank), options, fields["multiSelect"].boolean() == true)
    }.distinctBy { it.text }
    return TranscriptEvent.PermissionRequest(
        seq, at, data.text("requestId"), data.text("toolName"), question,
        questions = questions,
        input = input?.let { (it as JsonElement).pretty() },
        inputLeftOut = data["inputTruncated"].boolean() == true || input == null,
        inputPreview = data.text("inputPreview"),
        description = data.text("description")?.takeIf(String::isNotBlank),
    )
}

private val prettyJson = Json { prettyPrint = true }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
private fun JsonElement?.pretty(): String = if (this == null) "" else prettyJson.encodeToString(JsonElement.serializer(), this)

/** The field that tells most about a tool use, on one line: a shell command, a path, a pattern, a URL. */
private fun toolSummary(input: JsonElement?): String? {
    val fields = input as? JsonObject ?: return null
    val value = SUMMARY_FIELDS.firstNotNullOfOrNull { fields.text(it)?.takeIf(String::isNotBlank) } ?: return null
    val trimmed = value.trim()
    val line = trimmed.lineSequence().first().trim()
    return if (line.length > 160 || trimmed.contains('\n')) line.take(160) + "…" else line
}

private val SUMMARY_FIELDS = listOf("command", "file_path", "path", "pattern", "url", "query", "description", "prompt")
