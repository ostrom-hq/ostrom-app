// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/NewSessionSheet.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import org.jetbrains.compose.resources.pluralStringResource
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.ResumableSession
import run.ostrom.app.presentation.components.ErrorText
import run.ostrom.app.presentation.components.Group
import run.ostrom.app.presentation.components.GroupDivider
import run.ostrom.app.presentation.components.relativeTime
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.components.systemOf
import run.ostrom.app.presentation.viewmodels.NewCloudSessionUiState
import run.ostrom.app.presentation.viewmodels.SessionTarget
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
import run.ostrom.app.resources.local_session_agent
import run.ostrom.app.resources.local_session_agent_claude
import run.ostrom.app.resources.local_session_agent_codex
import run.ostrom.app.resources.local_session_agent_default
import run.ostrom.app.resources.new_session_device
import run.ostrom.app.resources.new_session_device_this
import run.ostrom.app.resources.new_session_effort
import run.ostrom.app.resources.new_session_folder
import run.ostrom.app.resources.new_session_folder_hint
import run.ostrom.app.resources.new_session_no_device
import run.ostrom.app.resources.new_session_no_folder
import run.ostrom.app.resources.new_session_permissions
import run.ostrom.app.resources.new_session_permissions_ask
import run.ostrom.app.resources.new_session_permissions_ask_hint
import run.ostrom.app.resources.new_session_permissions_bypass
import run.ostrom.app.resources.new_session_permissions_bypass_warning
import run.ostrom.app.resources.new_session_refresh_devices
import run.ostrom.app.resources.new_session_resume
import run.ostrom.app.resources.new_session_resume_empty
import run.ostrom.app.resources.new_session_resume_hint
import run.ostrom.app.resources.new_session_resume_loading
import run.ostrom.app.resources.new_session_resume_more
import run.ostrom.app.resources.new_session_resume_none
import run.ostrom.app.resources.new_session_resume_none_hint
import run.ostrom.app.resources.new_session_resume_old_daemon
import run.ostrom.app.resources.new_session_resume_turns
import run.ostrom.app.resources.new_session_start_on_device
import run.ostrom.app.resources.new_session_target_cloud
import run.ostrom.app.resources.new_session_target_device

/**
 * The new cloud session form: the agent, a name, a model and a first message; then, on a server that takes a
 * preparation recipe (`runners.cloud.environment.v1`), its repositories (address, directory, ref, local branch), working
 * directory, setup commands, required variables, GitHub requirement and Git e-mail domain; otherwise the single
 * repository and ref. On a server that starts sessions on the member's online devices (`runners.cloud.remote.daemon.v1`),
 * a switch offers that target instead ([DeviceForm]). Start lands on the new session.
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
            if (!state.loading && state.error == null && state.onDevices) {
                Segmented(
                    listOf(SessionTarget.CLOUD to stringResource(Res.string.new_session_target_cloud), SessionTarget.DEVICE to stringResource(Res.string.new_session_target_device)),
                    state.target, viewModel::onTarget, Modifier.fillMaxWidth(), tag = { "target-${it.name.lowercase()}" },
                )
            }
            if (!state.loading && state.error == null && state.target == SessionTarget.DEVICE) DeviceForm(state, viewModel)
            if (!state.loading && state.error == null && state.target == SessionTarget.CLOUD) {
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

/**
 * The form on one of the member's online devices (`runners.cloud.remote.daemon.v1`), TeamCode's DeviceFields: the online
 * devices only, then the folders the chosen one offers (never a path typed here), the conversation to continue, the
 * agent; then a name (the folder's by default), a first message, the model and effort, and the permission mode.
 */
@Composable
private fun DeviceForm(state: NewCloudSessionUiState, viewModel: NewCloudSessionViewModel) {
    val colors = Ostrom.colors
    val device = state.devices?.firstOrNull { it.id == state.deviceId }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.new_session_device), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AppButton(stringResource(Res.string.new_session_refresh_devices), onClick = viewModel::reloadDevices, kind = ButtonKind.GHOST, small = true, fill = false,
                enabled = !state.loadingDevices && !state.creating, modifier = Modifier.testTag("refresh-devices"))
        }
        BusyRow(state.loadingDevices)
        val devices = state.devices
        if (!state.loadingDevices && devices != null && devices.isEmpty()) {
            Text(stringResource(Res.string.new_session_no_device), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("no-device"))
        }
        if (!devices.isNullOrEmpty()) Group(Modifier.selectableGroup()) {
            devices.forEachIndexed { index, item ->
                if (index > 0) GroupDivider()
                val system = systemOf(item.platform, item.osVersion)
                val subtitle = if (item.current) listOfNotNull(stringResource(Res.string.new_session_device_this), system).joinToString(" · ") else system
                Option(item.name, subtitle, selected = state.deviceId == item.id, tag = "device-${item.id}") { viewModel.onDevice(item.id) }
            }
        }
    }
    if (state.deviceId != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.new_session_folder), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        BusyRow(state.loadingFolders)
        val folders = state.folders
        if (!state.loadingFolders && folders != null && folders.isEmpty() && state.createError == null) {
            Text(stringResource(Res.string.new_session_no_folder), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("no-folder"))
        }
        if (!folders.isNullOrEmpty()) {
            Group(Modifier.selectableGroup()) {
                folders.forEachIndexed { index, folder ->
                    if (index > 0) GroupDivider()
                    Option(folder.name, folder.label?.takeIf { it != folder.name }, selected = state.folderId == folder.id, tag = "folder-${folder.id}") { viewModel.onFolder(folder.id) }
                }
            }
            Text(stringResource(Res.string.new_session_folder_hint), style = MetaStyle, color = colors.muted)
        }
    }
    ResumeFields(state, viewModel, device?.name)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.local_session_agent), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Segmented(listOf("claude" to stringResource(Res.string.local_session_agent_claude), "codex" to stringResource(Res.string.local_session_agent_codex)),
            state.deviceAgent, viewModel::onDeviceAgent, Modifier.fillMaxWidth(), tag = { "agent-$it" })
    }
    AppTextField(state.name, { viewModel.onField(CloudField.NAME, it) }, Modifier.fillMaxWidth(), label = stringResource(Res.string.cloud_new_name), tag = "cloud-new-name")
    AppTextField(state.prompt, { viewModel.onField(CloudField.PROMPT, it) }, Modifier.fillMaxWidth(), label = stringResource(Res.string.cloud_new_prompt),
        hint = stringResource(Res.string.cloud_new_prompt_hint), singleLine = false, minLines = 3, tag = "cloud-new-prompt")
    val default = stringResource(Res.string.local_session_agent_default)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppTextField(state.model, { viewModel.onField(CloudField.MODEL, it) }, Modifier.weight(1f), label = stringResource(Res.string.cloud_new_model), placeholder = default,
            mono = true, tag = "cloud-new-model")
        AppTextField(state.effort, { viewModel.onField(CloudField.EFFORT, it) }, Modifier.weight(1f), label = stringResource(Res.string.new_session_effort), placeholder = default,
            mono = true, tag = "cloud-new-effort")
    }
    PermissionFields(state, viewModel, device?.name)
    state.createError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("cloud-new-error")) }
    val label = if (device != null) stringResource(Res.string.new_session_start_on_device, device.name) else stringResource(Res.string.cloud_new_start)
    AppButton(label, onClick = viewModel::create, enabled = state.canCreate, busy = state.creating, modifier = Modifier.testTag("cloud-new-submit"))
}

/**
 * "Resume a session", under the folder: closed and set to None by default; opened, it lists the folder's Claude Code
 * conversations the device read now (title, how long ago, how many messages). Another folder or device clears it.
 */
@Composable
private fun ResumeFields(state: NewCloudSessionUiState, viewModel: NewCloudSessionViewModel, deviceName: String?) {
    if (!state.showsResume) return
    val colors = Ostrom.colors
    val device = deviceName ?: stringResource(Res.string.new_session_device).lowercase()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("resume")) {
        Text(stringResource(Res.string.new_session_resume), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (!state.deviceResumes) {
            Text(stringResource(Res.string.new_session_resume_old_daemon, device), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("resume-old-daemon"))
            return@Column
        }
        ResumePicker(state, viewModel)
        Text(stringResource(Res.string.new_session_resume_hint, device), style = MetaStyle, color = colors.muted)
    }
}

/**
 * The picker itself: closed, it shows the choice (None by default); opened, the conversations (title, how long ago, how
 * many messages), what is loading or failed with Retry, an empty folder, and the older ones.
 */
@Composable
private fun ResumePicker(state: NewCloudSessionUiState, viewModel: NewCloudSessionViewModel) {
    val colors = Ostrom.colors
    val now = rememberNow()
    val resumable = state.resumable
    val chosen = resumable?.firstOrNull { it.id == state.resumeId }
    Group {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !state.creating, role = Role.Button, onClick = viewModel::onResumeOpen)
                .padding(horizontal = 14.dp, vertical = 8.dp).testTag("resume-picker"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(chosen?.title ?: stringResource(Res.string.new_session_resume_none), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(chosen?.let { meta(it, now) } ?: stringResource(Res.string.new_session_resume_none_hint), style = MetaStyle, color = colors.muted)
            }
            Icon(if (state.resumeOpen) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown, null, tint = colors.muted, modifier = Modifier.size(16.dp))
        }
        if (state.resumeOpen) {
            GroupDivider()
            if (state.loadingResumable && resumable == null) {
                BusyRow(true)
                Text(stringResource(Res.string.new_session_resume_loading), color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
            state.resumableError?.let { error ->
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ErrorText(error, Modifier.testTag("resume-error"))
                    AppButton(stringResource(Res.string.action_retry), onClick = viewModel::onResumeRetry, kind = ButtonKind.SECONDARY, small = true, fill = false,
                        modifier = Modifier.testTag("resume-retry"))
                }
            }
            if (resumable != null) Column(Modifier.selectableGroup()) {
                Option(stringResource(Res.string.new_session_resume_none), stringResource(Res.string.new_session_resume_none_hint), selected = state.resumeId == null,
                    tag = "resume-none") { viewModel.onResume(null) }
                resumable.forEach { session ->
                    GroupDivider()
                    Option(session.title, meta(session, now), selected = state.resumeId == session.id, tag = "resume-${session.id}") { viewModel.onResume(session.id) }
                }
                if (resumable.isEmpty() && state.resumableError == null) {
                    GroupDivider()
                    Text(stringResource(Res.string.new_session_resume_empty), color = colors.muted, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).testTag("resume-empty"))
                }
                if (state.resumableNext != null) {
                    GroupDivider()
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        AppButton(stringResource(Res.string.new_session_resume_more), onClick = viewModel::onResumeMore, kind = ButtonKind.GHOST, small = true, fill = false,
                            busy = state.loadingResumable, modifier = Modifier.testTag("resume-more"))
                    }
                }
            }
        }
    }
}

/** "2 h ago · 5 messages". */
@Composable
private fun meta(session: ResumableSession, now: Long): String =
    relativeTime(session.lastActiveAt, now) + " · " + pluralStringResource(Res.plurals.new_session_resume_turns, session.turns, session.turns)

/** Ask or bypass, chosen at each launch; the last choice comes back the next time. */
@Composable
private fun PermissionFields(state: NewCloudSessionUiState, viewModel: NewCloudSessionViewModel, deviceName: String?) {
    val colors = Ostrom.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.new_session_permissions), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Segmented(listOf(PermissionMode.ASK to stringResource(Res.string.new_session_permissions_ask), PermissionMode.BYPASS to stringResource(Res.string.new_session_permissions_bypass)),
            state.permissionMode, viewModel::onPermissionMode, Modifier.fillMaxWidth(), tag = { "permissions-${it.wire}" })
        if (state.permissionMode == PermissionMode.BYPASS) {
            Banner(stringResource(Res.string.new_session_permissions_bypass_warning, deviceName ?: stringResource(Res.string.new_session_device).lowercase()), Tone.WARN,
                Modifier.testTag("bypass-warning"))
        } else Text(stringResource(Res.string.new_session_permissions_ask_hint), style = MetaStyle, color = colors.muted)
    }
}

/** A radio row of the device form: a device, a folder, a conversation. */
@Composable
private fun Option(title: String, subtitle: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = Ostrom.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).border(1.5.dp, if (selected) colors.accent else colors.line, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(colors.accent, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            subtitle?.let { Text(it, style = MetaStyle, color = colors.muted) }
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
