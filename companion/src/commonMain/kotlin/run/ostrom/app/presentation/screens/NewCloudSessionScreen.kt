// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/NewSessionSheet.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Trash
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.AppTextField
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Segmented
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.theme.Radii
import run.ostrom.app.presentation.viewmodels.CloudField
import run.ostrom.app.presentation.viewmodels.NewCloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.RepositoryField
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.cloud_agent_claude
import run.ostrom.app.resources.cloud_agent_codex
import run.ostrom.app.resources.cloud_agent_gemini
import run.ostrom.app.resources.cloud_agent_opencode
import run.ostrom.app.resources.cloud_agent_shell
import run.ostrom.app.resources.cloud_new_agent
import run.ostrom.app.resources.cloud_new_conversation
import run.ostrom.app.resources.cloud_new_conversation_hint
import run.ostrom.app.resources.cloud_new_email_domain
import run.ostrom.app.resources.cloud_new_email_domain_hint
import run.ostrom.app.resources.cloud_new_model
import run.ostrom.app.resources.cloud_new_name
import run.ostrom.app.resources.cloud_new_prompt
import run.ostrom.app.resources.cloud_new_prompt_hint
import run.ostrom.app.resources.cloud_new_ref
import run.ostrom.app.resources.cloud_new_repo
import run.ostrom.app.resources.cloud_new_repo_hint
import run.ostrom.app.resources.cloud_new_repository_add
import run.ostrom.app.resources.cloud_new_repository_branch
import run.ostrom.app.resources.cloud_new_repository_n
import run.ostrom.app.resources.cloud_new_repository_path
import run.ostrom.app.resources.cloud_new_repository_remove
import run.ostrom.app.resources.cloud_new_repository_url
import run.ostrom.app.resources.cloud_new_require_github
import run.ostrom.app.resources.cloud_new_secrets_note
import run.ostrom.app.resources.cloud_new_setup_create
import run.ostrom.app.resources.cloud_new_setup_hint
import run.ostrom.app.resources.cloud_new_setup_start
import run.ostrom.app.resources.cloud_new_start
import run.ostrom.app.resources.cloud_new_title
import run.ostrom.app.resources.cloud_new_variables
import run.ostrom.app.resources.cloud_new_variables_hint
import run.ostrom.app.resources.cloud_new_working_directory
import run.ostrom.app.resources.cloud_recipe
import run.ostrom.app.resources.sessions_title

/**
 * The new cloud session form: the agent, a name, a model and a first message; then, on a server that takes a
 * preparation recipe (`runners.cloud.environment.v1`), its repositories (address, directory, ref, local branch), working
 * directory, setup commands, required variables, GitHub requirement and Git e-mail domain; otherwise the single
 * repository and ref. Start lands on the new session.
 */
@Composable
fun NewCloudSessionScreen(
    viewModel: NewCloudSessionViewModel,
    onBack: (() -> Unit)?,
    onCreated: (CloudSession) -> Unit,
    backLabel: String = stringResource(Res.string.sessions_title),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    LaunchedEffect(state.created) { state.created?.let(onCreated) }
    Column(Modifier.fillMaxSize().background(colors.background).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(Res.string.cloud_new_title))
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
                .testTag("cloud-new-form"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("cloud-new-load-error"), action = {
                    AppButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, fill = false)
                })
            }
            BusyRow(state.loading)
            if (!state.loading && state.error == null) {
                Text(stringResource(Res.string.cloud_new_agent), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Segmented(
                    CloudAgent.entries.map { it to agentWords(it) }, state.agent, viewModel::onAgent, Modifier.fillMaxWidth(),
                    tag = { "cloud-new-agent-${it.wire}" }, scrollable = true,
                )
                if (state.conversationOffered) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.cloud_new_conversation), color = colors.text, fontSize = 14.sp)
                            Text(stringResource(Res.string.cloud_new_conversation_hint), style = MetaStyle, color = colors.muted)
                        }
                        Switch(state.conversation, viewModel::onConversation, modifier = Modifier.testTag("cloud-new-conversation"))
                    }
                }
                AppTextField(state.name, { viewModel.onField(CloudField.NAME, it) }, Modifier.fillMaxWidth(), label = stringResource(Res.string.cloud_new_name), tag = "cloud-new-name")
                AppTextField(state.model, { viewModel.onField(CloudField.MODEL, it) }, Modifier.fillMaxWidth(), label = stringResource(Res.string.cloud_new_model), mono = true,
                    tag = "cloud-new-model")
                AppTextField(state.prompt, { viewModel.onField(CloudField.PROMPT, it) }, Modifier.fillMaxWidth(), label = stringResource(Res.string.cloud_new_prompt),
                    hint = stringResource(Res.string.cloud_new_prompt_hint), singleLine = false, minLines = 3, tag = "cloud-new-prompt")
                if (state.recipeServed) {
                    SectionTitle(stringResource(Res.string.cloud_recipe))
                    state.repositories.forEachIndexed { index, repository -> RepositoryRow(index, repository, state.repositories.size > 1, viewModel) }
                    if (state.canAddRepository) {
                        AppButton(stringResource(Res.string.cloud_new_repository_add), onClick = viewModel::addRepository, kind = ButtonKind.SECONDARY, small = true, fill = false,
                            icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("cloud-new-repository-add"))
                    }
                    AppTextField(state.workingDirectory, { viewModel.onField(CloudField.WORKING_DIRECTORY, it) }, Modifier.fillMaxWidth(),
                        label = stringResource(Res.string.cloud_new_working_directory), mono = true, tag = "cloud-new-working-directory")
                    AppTextField(state.setupCreate, { viewModel.onField(CloudField.SETUP_CREATE, it) }, Modifier.fillMaxWidth(),
                        label = stringResource(Res.string.cloud_new_setup_create), hint = stringResource(Res.string.cloud_new_setup_hint), mono = true, singleLine = false,
                        minLines = 2, tag = "cloud-new-setup-create")
                    AppTextField(state.setupStart, { viewModel.onField(CloudField.SETUP_START, it) }, Modifier.fillMaxWidth(),
                        label = stringResource(Res.string.cloud_new_setup_start), hint = stringResource(Res.string.cloud_new_setup_hint), mono = true, singleLine = false,
                        minLines = 2, tag = "cloud-new-setup-start")
                    AppTextField(state.variables, { viewModel.onField(CloudField.VARIABLES, it) }, Modifier.fillMaxWidth(),
                        label = stringResource(Res.string.cloud_new_variables), hint = stringResource(Res.string.cloud_new_variables_hint), mono = true, tag = "cloud-new-variables")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(Res.string.cloud_new_require_github), color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(state.requireGitHub, viewModel::onRequireGitHub, modifier = Modifier.testTag("cloud-new-require-github"))
                    }
                    AppTextField(state.emailDomain, { viewModel.onField(CloudField.EMAIL_DOMAIN, it) }, Modifier.fillMaxWidth(),
                        label = stringResource(Res.string.cloud_new_email_domain), hint = stringResource(Res.string.cloud_new_email_domain_hint), mono = true,
                        tag = "cloud-new-email-domain")
                    Text(stringResource(Res.string.cloud_new_secrets_note), style = MetaStyle, color = colors.muted)
                } else {
                    AppTextField(state.repo, { viewModel.onField(CloudField.REPO, it) }, Modifier.fillMaxWidth(), label = stringResource(Res.string.cloud_new_repo),
                        placeholder = stringResource(Res.string.cloud_new_repo_hint), mono = true, tag = "cloud-new-repo")
                    AppTextField(state.ref, { viewModel.onField(CloudField.REF, it) }, Modifier.fillMaxWidth(), label = stringResource(Res.string.cloud_new_ref), mono = true,
                        tag = "cloud-new-ref")
                }
                state.createError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("cloud-new-error")) }
                AppButton(stringResource(Res.string.cloud_new_start), onClick = viewModel::create, enabled = state.canCreate, busy = state.creating,
                    modifier = Modifier.testTag("cloud-new-submit"))
            }
        }
    }
}

@Composable
private fun RepositoryRow(index: Int, repository: CloudRepository, removable: Boolean, viewModel: NewCloudSessionViewModel) {
    val colors = Ostrom.colors
    Column(Modifier.fillMaxWidth().border(1.dp, colors.line, Radii.control).padding(10.dp).testTag("cloud-new-repository-$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.cloud_new_repository_n, index + 1), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (removable) {
                AppIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.cloud_new_repository_remove), onClick = { viewModel.removeRepository(index) },
                    modifier = Modifier.testTag("cloud-new-repository-remove-$index"))
            }
        }
        AppTextField(repository.url, { viewModel.onRepository(index, RepositoryField.URL, it) }, Modifier.fillMaxWidth(), label = stringResource(Res.string.cloud_new_repository_url),
            placeholder = stringResource(Res.string.cloud_new_repo_hint), mono = true, tag = "cloud-new-repository-url-$index")
        AppTextField(repository.path.orEmpty(), { viewModel.onRepository(index, RepositoryField.PATH, it) }, Modifier.fillMaxWidth(),
            label = stringResource(Res.string.cloud_new_repository_path), mono = true, tag = "cloud-new-repository-path-$index")
        AppTextField(repository.ref.orEmpty(), { viewModel.onRepository(index, RepositoryField.REF, it) }, Modifier.fillMaxWidth(), label = stringResource(Res.string.cloud_new_ref),
            mono = true, tag = "cloud-new-repository-ref-$index")
        AppTextField(repository.branch.orEmpty(), { viewModel.onRepository(index, RepositoryField.BRANCH, it) }, Modifier.fillMaxWidth(),
            label = stringResource(Res.string.cloud_new_repository_branch), mono = true, tag = "cloud-new-repository-branch-$index")
    }
}

@Composable
private fun agentWords(agent: CloudAgent): String = stringResource(
    when (agent) {
        CloudAgent.CLAUDE -> Res.string.cloud_agent_claude
        CloudAgent.CODEX -> Res.string.cloud_agent_codex
        CloudAgent.OPENCODE -> Res.string.cloud_agent_opencode
        CloudAgent.GEMINI -> Res.string.cloud_agent_gemini
        CloudAgent.SHELL -> Res.string.cloud_agent_shell
    },
)
