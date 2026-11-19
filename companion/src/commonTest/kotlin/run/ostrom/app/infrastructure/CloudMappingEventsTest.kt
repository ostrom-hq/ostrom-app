// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import run.ostrom.app.domain.models.AgentQuestion
import run.ostrom.app.domain.models.AgentQuestionOption
import run.ostrom.app.domain.models.BackgroundTask
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TurnPhase
import run.ostrom.app.infrastructure.repositories.domain
import run.ostrom.core.api.models.CloudEvent
import run.ostrom.core.api.models.CloudEventTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every event type of a transcript as the app reads it: the fields present, the fields missing, and the odd shapes. */
class CloudMappingEventsTest {
    private fun read(type: String, data: JsonObject = JsonObject(emptyMap()), feed: String? = null): TranscriptEvent =
        CloudEvent(5, type, "2026-11-14T09:00:00Z", 1, data, feed).domain()

    private val at = 1_794_646_800_000L

    @Test fun aUserMessageKeepsItsTextInputAndAuthor() {
        val full = read(CloudEventTypes.USER_MESSAGE, buildJsonObject { put("text", "hi"); put("inputId", "i1"); put("authorId", "m1") })
        assertEquals(TranscriptEvent.UserMessage(5, at, "hi", "i1", "m1"), full)
        assertEquals(TranscriptEvent.UserMessage(5, at, "", null, null), read(CloudEventTypes.USER_MESSAGE))
    }

    @Test fun aFieldOfTheWrongTypeReadsAsMissingNeverAsAFailure() {
        val odd = read(CloudEventTypes.USER_MESSAGE, buildJsonObject { put("text", 5); put("inputId", true); put("authorId", JsonNull) })
        assertEquals(TranscriptEvent.UserMessage(5, at, "", null, null), odd)
    }

    @Test fun anAgentMessageWithoutTextIsEmpty() {
        assertEquals(TranscriptEvent.AgentMessage(5, at, "", null), read(CloudEventTypes.AGENT_MESSAGE))
    }

    @Test fun aToolCallKeepsItsInputPrettyPrintedAndASummaryLine() {
        val call = read(CloudEventTypes.TOOL_CALL, buildJsonObject {
            put("id", "t1"); put("name", "Bash")
            putJsonObject("input") { put("command", "ls -la") }
        })
        val tool = assertIs<TranscriptEvent.ToolCall>(call)
        assertEquals("t1", tool.id)
        assertEquals("Bash", tool.name)
        assertEquals("ls -la", tool.summary)
        assertEquals("{\n    \"command\": \"ls -la\"\n}", tool.input)
    }

    @Test fun aToolCallWithoutNameIdOrInputIsAGenericTool() {
        val tool = assertIs<TranscriptEvent.ToolCall>(read(CloudEventTypes.TOOL_CALL))
        assertEquals("tool", tool.name)
        assertEquals("", tool.id)
        assertEquals("", tool.input)
        assertNull(tool.summary)
    }

    @Test fun theSummaryIsTheFirstFieldOfTheListThatHasTextAndIsCutWhenLongOrMultiline() {
        fun summary(input: JsonElement) = assertIs<TranscriptEvent.ToolCall>(read(CloudEventTypes.TOOL_CALL, buildJsonObject { put("input", input) })).summary
        assertEquals("a.kt", summary(buildJsonObject { put("file_path", "a.kt"); put("prompt", "later") }))
        assertEquals("a.kt", summary(buildJsonObject { put("command", "  "); put("path", "a.kt") }), "a blank value is skipped")
        assertEquals("first…", summary(buildJsonObject { put("command", "first\nsecond") }))
        assertEquals("x".repeat(160) + "…", summary(buildJsonObject { put("command", "x".repeat(200)) }))
        assertEquals("x".repeat(160), summary(buildJsonObject { put("command", "x".repeat(160)) }), "exactly 160 stays whole")
        assertNull(summary(buildJsonObject { put("other", "text"); put("command", 5) }), "no known field has text")
        assertNull(summary(JsonArray(listOf(JsonPrimitive("command")))), "an input that is not an object has no summary")
    }

    @Test fun aToolResultPrintsAStringOutputAsIsAndOtherValuesPretty() {
        fun output(value: JsonElement?) = assertIs<TranscriptEvent.ToolResult>(read(CloudEventTypes.TOOL_RESULT, buildJsonObject { value?.let { put("output", it) } })).output
        assertEquals("done", output(JsonPrimitive("done")))
        assertEquals("42", output(JsonPrimitive(42)))
        assertEquals("{\n    \"k\": 1\n}", output(buildJsonObject { put("k", 1) }))
        assertEquals("", output(null))
    }

    @Test fun aToolResultKeepsItsErrorFlagExitCodeAndThread() {
        val result = assertIs<TranscriptEvent.ToolResult>(
            read(CloudEventTypes.TOOL_RESULT, buildJsonObject { put("id", "t1"); put("output", "boom"); put("isError", true); put("exitCode", 2); put("parentToolUseId", "p") }),
        )
        assertEquals(TranscriptEvent.ToolResult(5, at, "t1", "boom", true, 2, "p"), result)
        assertEquals(TranscriptEvent.ToolResult(5, at, "", "", false, null, null), read(CloudEventTypes.TOOL_RESULT))
        val text = assertIs<TranscriptEvent.ToolResult>(read(CloudEventTypes.TOOL_RESULT, buildJsonObject { put("isError", "true"); put("exitCode", "2") }))
        assertEquals(false to null, text.isError to text.exitCode, "a string is not a boolean or a number")
    }

    @Test fun aTurnReadsItsPhaseDurationAndSteps() {
        assertEquals(
            TranscriptEvent.Turn(5, at, TurnPhase.COMPLETED, 1_500L, 3),
            read(CloudEventTypes.TURN, buildJsonObject { put("phase", "completed"); put("durationMs", 1_500); put("steps", 3) }),
        )
        assertEquals(TranscriptEvent.Turn(5, at, TurnPhase.UNKNOWN, null, null), read(CloudEventTypes.TURN, buildJsonObject { put("phase", "later") }))
        assertEquals(TurnPhase.UNKNOWN, TurnPhase.of("unknown"), "the unknown phase is never matched by name")
    }

    @Test fun statusErrorAndInputEventsKeepTheirFieldsAndToleratesMissingOnes() {
        assertEquals(
            TranscriptEvent.Status(5, at, "claude", "opus", 0),
            read(CloudEventTypes.STATUS, buildJsonObject { put("agent", "claude"); put("model", "opus"); put("exitCode", 0) }),
        )
        assertEquals(TranscriptEvent.Status(5, at, null, null, null), read(CloudEventTypes.STATUS))
        assertEquals(TranscriptEvent.Error(5, at, "boom", "it broke"), read(CloudEventTypes.ERROR, buildJsonObject { put("code", "boom"); put("message", "it broke") }))
        assertEquals(TranscriptEvent.Error(5, at, null, null), read(CloudEventTypes.ERROR))
        assertEquals(
            TranscriptEvent.InputChanged(5, at, "i1", "denied", "late", "r1"),
            read(CloudEventTypes.INPUT, buildJsonObject { put("inputId", "i1"); put("status", "denied"); put("reason", "late"); put("requestId", "r1") }),
        )
        assertEquals(TranscriptEvent.InputChanged(5, at, null, null, null, null), read(CloudEventTypes.INPUT))
    }

    @Test fun anExpiredPermissionAndATransferNameWhatTheyConcern() {
        assertEquals(TranscriptEvent.PermissionExpired(5, at, "r1"), read(CloudEventTypes.PERMISSION_EXPIRED, buildJsonObject { put("requestId", "r1") }))
        assertEquals(TranscriptEvent.PermissionExpired(5, at, null), read(CloudEventTypes.PERMISSION_EXPIRED))
        assertEquals(TranscriptEvent.Transferred(5, at, "m1", "m2"), read("transfer", buildJsonObject { put("fromMemberId", "m1"); put("toMemberId", "m2") }))
        assertEquals(TranscriptEvent.Transferred(5, at, null, null), read("transfer"))
    }

    @Test fun anUnknownTypeIsKeptAsUnknownWithItsType() {
        assertEquals(TranscriptEvent.Unknown(5, at, "future.thing"), read("future.thing", buildJsonObject { put("x", 1) }))
    }

    @Test fun aTruncatedEventKeepsItsTypeAndOriginalSizeOnlyWhenTheServerGaveBoth() {
        assertEquals(
            TranscriptEvent.Truncated(5, at, CloudEventTypes.TOOL_RESULT, 90_000L),
            read(CloudEventTypes.TOOL_RESULT, buildJsonObject { put("truncated", true); put("originalBytes", 90_000) }),
        )
        assertEquals(
            TranscriptEvent.Truncated(5, at, CloudEventTypes.TOOL_RESULT, null),
            read(CloudEventTypes.TOOL_RESULT, buildJsonObject { put("truncated", true); put("originalBytes", "many") }),
            "an unreadable size is kept as unknown",
        )
        assertIs<TranscriptEvent.AgentMessage>(read(CloudEventTypes.AGENT_MESSAGE, buildJsonObject { put("truncated", true); put("text", "kept") }), "no size: not the marker")
        assertIs<TranscriptEvent.AgentMessage>(read(CloudEventTypes.AGENT_MESSAGE, buildJsonObject { put("truncated", false); put("originalBytes", 1) }))
        assertIs<TranscriptEvent.AgentMessage>(read(CloudEventTypes.AGENT_MESSAGE, buildJsonObject { put("truncated", "true"); put("originalBytes", 1) }), "a string is not a boolean")
    }

    @Test fun tasksKeepOnlyTheReadableOnesAndBoundTheScheduledCountAtZero() {
        val tasks = assertIs<TranscriptEvent.Tasks>(
            read(CloudEventTypes.TASKS, buildJsonObject {
                putJsonArray("tasks") {
                    add(JsonPrimitive("not an object"))
                    addJsonObject { put("id", "b1") }
                    addJsonObject { put("type", "local_bash") }
                }
                put("scheduled", -4)
            }),
        )
        assertEquals(listOf(BackgroundTask("b1", "task", "", null, null, null)), tasks.tasks)
        assertEquals(0, tasks.scheduled)
        assertEquals(false, tasks.stoppable)
        assertEquals(3, assertIs<TranscriptEvent.Tasks>(read(CloudEventTypes.TASKS, buildJsonObject { put("scheduled", 3) })).scheduled)
        assertTrue(assertIs<TranscriptEvent.Tasks>(read(CloudEventTypes.TASKS)).tasks.isEmpty())
        assertEquals(0, assertIs<TranscriptEvent.Tasks>(read(CloudEventTypes.TASKS, buildJsonObject { put("scheduled", "2") })).scheduled)
    }

    @Test fun aPermissionRequestForAToolKeepsItsInputAndPreview() {
        val request = assertIs<TranscriptEvent.PermissionRequest>(
            read(CloudEventTypes.PERMISSION_REQUEST, buildJsonObject {
                put("requestId", "r1"); put("toolName", "Bash"); put("inputPreview", "rm -rf x"); put("description", "Delete")
                putJsonObject("input") { put("command", "rm -rf x") }
            }),
        )
        assertEquals("r1", request.requestId)
        assertEquals("Bash", request.toolName)
        assertEquals(false, request.question)
        assertEquals("{\n    \"command\": \"rm -rf x\"\n}", request.input)
        assertEquals(false, request.inputLeftOut)
        assertEquals("rm -rf x", request.inputPreview)
        assertEquals("Delete", request.description)
        assertTrue(request.questions.isEmpty(), "a tool request has no questions")
    }

    @Test fun aPermissionRequestWithoutInputOrWithATruncatedOneIsLeftOutAndAnInputThatIsNotAnObjectCountsAsLeftOut() {
        val none = assertIs<TranscriptEvent.PermissionRequest>(read(CloudEventTypes.PERMISSION_REQUEST, buildJsonObject { put("description", "  ") }))
        assertNull(none.input)
        assertTrue(none.inputLeftOut)
        assertNull(none.description, "a blank description is none")
        assertNull(none.requestId)
        val flagged = assertIs<TranscriptEvent.PermissionRequest>(
            read(CloudEventTypes.PERMISSION_REQUEST, buildJsonObject { put("inputTruncated", true); putJsonObject("input") { put("a", 1) } }),
        )
        assertTrue(flagged.inputLeftOut)
        assertEquals("{\n    \"a\": 1\n}", flagged.input)
        val notObject = assertIs<TranscriptEvent.PermissionRequest>(read(CloudEventTypes.PERMISSION_REQUEST, buildJsonObject { put("input", "text") }))
        assertNull(notObject.input)
        assertTrue(notObject.inputLeftOut)
    }

    @Test fun aQuestionRequestKeepsEachQuestionWithItsIndexOptionsAndOnlyTheFirstOfARepeatedText() {
        val request = assertIs<TranscriptEvent.PermissionRequest>(
            read(CloudEventTypes.PERMISSION_REQUEST, buildJsonObject {
                put("question", true)
                putJsonObject("input") {
                    putJsonArray("questions") {
                        addJsonObject {
                            put("question", "Which?"); put("header", "Pick"); put("multiSelect", true)
                            putJsonArray("options") {
                                addJsonObject { put("label", "A"); put("description", "first") }
                                addJsonObject { put("label", "B"); put("description", " ") }
                                add(JsonPrimitive("junk"))
                            }
                        }
                        add(JsonPrimitive("junk"))
                        addJsonObject { put("header", "no text: left out") }
                        addJsonObject { put("question", "Which?"); put("header", "again") }
                        addJsonObject { put("question", "Why?"); put("header", " ") }
                    }
                }
            }),
        )
        assertTrue(request.question)
        assertEquals(
            listOf(
                AgentQuestion(0, "Which?", "Pick", listOf(AgentQuestionOption("A", "first"), AgentQuestionOption("B", null), AgentQuestionOption("", null)), true),
                AgentQuestion(4, "Why?", null, emptyList(), false),
            ),
            request.questions,
            "an unreadable question keeps the others' indexes",
        )
    }

    @Test fun questionsAreIgnoredWhenTheFlagIsAbsentOrTheInputIsNotAnObjectOrHasNoList() {
        val withoutFlag = assertIs<TranscriptEvent.PermissionRequest>(
            read(CloudEventTypes.PERMISSION_REQUEST, buildJsonObject {
                putJsonObject("input") { putJsonArray("questions") { addJsonObject { put("question", "Which?") } } }
            }),
        )
        assertTrue(withoutFlag.questions.isEmpty())
        val noObject = assertIs<TranscriptEvent.PermissionRequest>(read(CloudEventTypes.PERMISSION_REQUEST, buildJsonObject { put("question", true); put("input", "x") }))
        assertTrue(noObject.questions.isEmpty())
        val noList = assertIs<TranscriptEvent.PermissionRequest>(
            read(CloudEventTypes.PERMISSION_REQUEST, buildJsonObject { put("question", true); putJsonObject("input") { put("questions", "none") } }),
        )
        assertTrue(noList.questions.isEmpty())
    }

    @Test fun theNumbersOfAnEventAreReadFromNumbersOnly() {
        val turn = assertIs<TranscriptEvent.Turn>(read(CloudEventTypes.TURN, buildJsonObject { put("durationMs", "5"); put("steps", 2.5) }))
        assertNull(turn.durationMs)
        assertNull(turn.steps, "a fraction is not a step count")
    }

    @Test fun anOptionThatIsNotAnObjectIsBlankAndAFlagIsOnlyTrueWhenTrue() {
        val request = assertIs<TranscriptEvent.PermissionRequest>(
            read(
                CloudEventTypes.PERMISSION_REQUEST,
                buildJsonObject {
                    put("question", true)
                    putJsonObject("input") {
                        putJsonArray("questions") {
                            addJsonObject {
                                put("question", "Which?"); put("multiSelect", false)
                                putJsonArray("options") {
                                    add(JsonPrimitive("loose")); addJsonObject { put("label", "Yes"); put("description", "") }
                                    addJsonObject { put("description", "Unnamed") }; addJsonObject { put("label", "No"); put("description", "Because") }
                                }
                            }
                        }
                    }
                },
            ),
        )
        val question = request.questions.single()
        assertEquals(listOf(AgentQuestionOption("", null), AgentQuestionOption("Yes", null), AgentQuestionOption("", "Unnamed"), AgentQuestionOption("No", "Because")), question.options)
        assertFalse(question.multiSelect)
        assertNull(question.header)
    }
}
