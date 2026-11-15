// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../PermissionCardTest.kt; see NOTICE.
package run.ostrom.app

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import run.ostrom.app.domain.cloudSession
import run.ostrom.app.domain.models.AgentQuestion
import run.ostrom.app.domain.models.AgentQuestionOption
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.QuestionAnswer
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.domain.repositories.TranscriptRepository
import run.ostrom.app.presentation.screens.PermissionCardView
import run.ostrom.app.presentation.theme.OstromTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The question card on the desktop target: one question at a time, Back keeping the choice, one Send for all. */
@OptIn(ExperimentalTestApi::class)
class PermissionCardTest {
    @Test
    fun aQuestionIsAnsweredOneStepAtATimeAndSentOnce() = runComposeUiTest {
        val questions = listOf(
            AgentQuestion(0, "Red or blue?", "Colour", listOf(AgentQuestionOption("Red", "A warm one"), AgentQuestionOption("Blue", null)), multiSelect = false),
            AgentQuestion(1, "Which sizes?", null, listOf(AgentQuestionOption("S", null), AgentQuestionOption("**M**", null)), multiSelect = true),
        )
        val live = Channel<LiveUpdate<TranscriptEvent>>(Channel.UNLIMITED)
        val transcripts = object : TranscriptRepository {
            override suspend fun page(accountId: String, sessionId: String, before: Long?) =
                TranscriptPage(listOf(TranscriptEvent.PermissionRequest(1, 1, "r1", "AskUserQuestion", true, questions, input = "{}")), null, 1)
            override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = live.receiveAsFlow()
        }
        val sessions = FakeCloudSessionRepository(cloudSession("s1", ownerId = "m1").copy(mode = CloudSessionMode.CONVERSATION))
        val features = FakeFeatures(setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_CONVERSATION, Features.RUNNERS_CLOUD_PERMISSIONS))
        val model = conversationViewModel(sessions, transcripts, features)
        setContent {
            OstromTheme {
                val state by model.uiState.collectAsStateWithLifecycle()
                state.permission?.let { PermissionCardView(it, model) }
            }
        }
        model.visible()
        waitUntil { model.uiState.value.permission != null }
        onNodeWithText("Red or blue?").assertExists()
        onNodeWithText("A warm one").assertExists()
        onNodeWithText("Colour").assertExists()
        onNodeWithText("1/2").assertExists()
        onNodeWithTag("permission-next").assertIsNotEnabled()
        onNodeWithTag("option-0-1").performClick()
        onNodeWithTag("option-0-1").assertIsSelected()
        onNodeWithTag("permission-next").assertIsEnabled().performClick()

        onNodeWithText("Which sizes?").assertExists()
        onNodeWithText("2/2").assertExists()
        // An option label is the agent's text, shown as is.
        onNodeWithText("**M**").assertExists()
        onNodeWithTag("option-1-0").performClick()
        onNodeWithTag("option-1-0").assertIsOn()
        onNodeWithTag("option-1-1").assertIsOff()
        onNodeWithTag("other-1").performClick()
        onNodeWithTag("other-text-1").performTextInput("XL")
        // Back keeps the first choice.
        onNodeWithTag("permission-back").performClick()
        onNodeWithTag("option-0-1").assertIsSelected()
        onNodeWithTag("permission-next").performClick()
        onNodeWithTag("permission-send").assertIsEnabled().performClick()
        waitUntil { sessions.answers.isNotEmpty() }
        assertEquals(PermissionAnswer("r1", allow = true, answers = listOf(QuestionAnswer(0, listOf(1)), QuestionAnswer(1, text = "S, XL"))), sessions.answers.single().first)
        waitUntil { onAllNodes(hasTestTag("permission-status")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Answer sent, waiting for the agent").assertExists()
        model.hidden()
    }
}
