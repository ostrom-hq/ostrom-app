// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/SessionScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.CloudFailure
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.visibleCode
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Segmented
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.theme.Radii
import run.ostrom.app.presentation.viewmodels.CloudAction
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.cloud_failure_cluster_unavailable
import run.ostrom.app.resources.cloud_failure_execution_lost
import run.ostrom.app.resources.cloud_failure_hours_exhausted
import run.ostrom.app.resources.cloud_failure_idle_timeout
import run.ostrom.app.resources.cloud_failure_max_duration
import run.ostrom.app.resources.cloud_failure_namespace_not_allowed
import run.ostrom.app.resources.cloud_failure_other
import run.ostrom.app.resources.cloud_failure_preparation
import run.ostrom.app.resources.cloud_failure_preparation_fix
import run.ostrom.app.resources.cloud_failure_preparation_new
import run.ostrom.app.resources.cloud_failure_preparation_retry
import run.ostrom.app.resources.cloud_failure_start_failed
import run.ostrom.app.resources.cloud_failure_unknown
import run.ostrom.app.resources.cloud_github_none
import run.ostrom.app.resources.cloud_github_none_hint
import run.ostrom.app.resources.cloud_github_owner
import run.ostrom.app.resources.cloud_github_owner_hint
import run.ostrom.app.resources.cloud_github_shared
import run.ostrom.app.resources.cloud_github_shared_hint
import run.ostrom.app.resources.cloud_github_title
import run.ostrom.app.resources.cloud_pause
import run.ostrom.app.resources.cloud_recipe
import run.ostrom.app.resources.cloud_recipe_email_domain
import run.ostrom.app.resources.cloud_recipe_github
import run.ostrom.app.resources.cloud_recipe_repo_branch
import run.ostrom.app.resources.cloud_recipe_repo_path
import run.ostrom.app.resources.cloud_recipe_repo_ref
import run.ostrom.app.resources.cloud_recipe_setup_create
import run.ostrom.app.resources.cloud_recipe_setup_start
import run.ostrom.app.resources.cloud_recipe_variables
import run.ostrom.app.resources.cloud_recipe_working_directory
import run.ostrom.app.resources.cloud_resume
import run.ostrom.app.resources.cloud_session_created
import run.ostrom.app.resources.cloud_session_ended
import run.ostrom.app.resources.cloud_session_last_activity
import run.ostrom.app.resources.cloud_session_limits
import run.ostrom.app.resources.cloud_session_not_served
import run.ostrom.app.resources.cloud_session_paused_at
import run.ostrom.app.resources.cloud_session_preparing
import run.ostrom.app.resources.cloud_session_ready
import run.ostrom.app.resources.cloud_session_refresh
import run.ostrom.app.resources.cloud_session_repo_ref
import run.ostrom.app.resources.cloud_session_restricted
import run.ostrom.app.resources.cloud_session_title
import run.ostrom.app.resources.cloud_session_transferring
import run.ostrom.app.resources.cloud_stop
import run.ostrom.app.resources.cloud_stop_body
import run.ostrom.app.resources.cloud_stop_keep
import run.ostrom.app.resources.cloud_stop_title
import run.ostrom.app.resources.sessions_title

/**
 * One cloud session's record: its status, agent and model, repository or recipe, times and limits; while it is
 * prepared, which step; why it failed (a failed preparation says whether a resume may pass); and Pause, Resume and Stop
 * (confirmed first), each followed by a fresh read. Its transcript and terminal are not served to the app.
 */
@Composable
fun CloudSessionScreen(
    viewModel: CloudSessionViewModel,
    onBack: (() -> Unit)?,
    backLabel: String = stringResource(Res.string.sessions_title),
    /** More actions of the loaded session (the transfer one, shown when the member may give it away). */
    extraActions: @Composable (CloudSession) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Read at each return: the server publishes no cloud session event.
    LifecycleStartEffect(viewModel) {
        viewModel.load()
        onStopOrDispose { }
    }
    val now = rememberNow()
    val today = localEpochDay(now)
    val colors = Ostrom.colors
    val session = state.session
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, session?.title() ?: stringResource(Res.string.cloud_session_title)) {
            AppIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.cloud_session_refresh), onClick = viewModel::load, enabled = !state.loading,
                modifier = Modifier.testTag("cloud-session-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
                .testTag("cloud-session-page"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("cloud-session-error"), action = if (!state.gone) {
                    { AppButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, fill = false) }
                } else null)
            }
            state.actionError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("cloud-session-action-error")) }
            BusyRow(state.loading && session == null)
            if (session != null) {
                Overview(session, today)
                if (session.preparing) {
                    Banner(stringResource(Res.string.cloud_session_preparing, phaseWords(session.preparationPhase)), Tone.NEUTRAL, Modifier.testTag("cloud-session-phase"))
                }
                session.failure?.let { FailureBanner(it, session.canResume) }
                if (session.status == CloudSessionStatus.TRANSFERRING) Banner(stringResource(Res.string.cloud_session_transferring), Tone.NEUTRAL, Modifier.testTag("cloud-session-transferring"))
                Actions(session, state.acting, onPause = viewModel::pause, onResume = viewModel::resume, onStop = { confirmStop = true }, extra = { extraActions(session) })
                if (state.canChooseAccess) GitHubAccessChoice(session.githubAccess, state.settingAccess, onChoose = viewModel::chooseGitHubAccess)
                session.recipe?.takeIf { !it.empty }?.let { Recipe(it) }
                Text(stringResource(Res.string.cloud_session_not_served), style = MetaStyle, color = colors.muted)
            }
        }
    }
    if (confirmStop) ConfirmStop(onConfirm = { confirmStop = false; viewModel.stop() }, onDismiss = { confirmStop = false })
}

@Composable
private fun Overview(session: CloudSession, today: Long) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("cloud-session-overview")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.title(), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            CloudStatusChip(session, Modifier.testTag("cloud-session-status"))
        }
        Text(listOfNotNull(session.agent, session.model).joinToString(" · "), color = colors.text, fontSize = 14.sp)
        session.repo?.let { repo ->
            Text(session.ref?.let { stringResource(Res.string.cloud_session_repo_ref, repo, it) } ?: repo, style = MonospaceStyle, color = colors.text,
                modifier = Modifier.testTag("cloud-session-repo"))
        }
        val times = listOfNotNull(
            session.createdAt?.let { stringResource(Res.string.cloud_session_created, formatEventTime(it, today)) },
            session.readyAt?.let { stringResource(Res.string.cloud_session_ready, formatEventTime(it, today)) },
            session.lastActivityAt?.let { stringResource(Res.string.cloud_session_last_activity, formatEventTime(it, today)) },
            session.pausedAt?.let { stringResource(Res.string.cloud_session_paused_at, formatEventTime(it, today)) },
            session.endedAt?.let { stringResource(Res.string.cloud_session_ended, formatEventTime(it, today)) },
        )
        times.forEach { Text(it, style = MetaStyle, color = colors.muted) }
        session.limits?.let { limits ->
            val words = stringResource(Res.string.cloud_session_limits, limits.idleTimeoutMinutes, limits.maxRunMinutes)
            val restricted = if (limits.restrictedEgress) stringResource(Res.string.cloud_session_restricted) else null
            Text(listOfNotNull(words, restricted).joinToString(" · "), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("cloud-session-limits"))
        }
        SelectionContainer { Text(session.id, style = MonospaceStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * Why it failed or paused on its own. A failed preparation shows the server's sentence (which step failed), then
 * whether a resume may pass ([resumable]: only on a session that can still be resumed; a failed one is started anew) or
 * the recipe or the variables must be fixed; every other code is worded here.
 */
@Composable
private fun FailureBanner(failure: CloudFailure, resumable: Boolean) {
    val retry = failure.retryable == true
    val text = if (failure.preparation) {
        val next = stringResource(
            when {
                !retry -> Res.string.cloud_failure_preparation_fix
                resumable -> Res.string.cloud_failure_preparation_retry
                else -> Res.string.cloud_failure_preparation_new
            },
        )
        stringResource(Res.string.cloud_failure_preparation, failure.message) + " " + next
    } else {
        when (failure.code) {
            "start_failed" -> stringResource(Res.string.cloud_failure_start_failed)
            "execution_lost" -> stringResource(Res.string.cloud_failure_execution_lost)
            "idle_timeout" -> stringResource(Res.string.cloud_failure_idle_timeout)
            "max_duration" -> stringResource(Res.string.cloud_failure_max_duration)
            "hours_exhausted" -> stringResource(Res.string.cloud_failure_hours_exhausted)
            "cluster_unavailable" -> stringResource(Res.string.cloud_failure_cluster_unavailable)
            "namespace_not_allowed" -> stringResource(Res.string.cloud_failure_namespace_not_allowed)
            else -> visibleCode(failure.code)?.let { stringResource(Res.string.cloud_failure_other, it) } ?: stringResource(Res.string.cloud_failure_unknown)
        }
    }
    val tone = if (failure.preparation && retry && resumable || failure.code in PAUSES) Tone.WARN else Tone.DANGER
    Banner(text, tone, Modifier.testTag("cloud-session-failure"))
}

/** The codes of a session that paused on its own, not of a failure. */
private val PAUSES = setOf("idle_timeout", "max_duration", "hours_exhausted")

@Composable
private fun Actions(session: CloudSession, acting: CloudAction?, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit, extra: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session.canResume) {
            AppButton(stringResource(Res.string.cloud_resume), onClick = onResume, small = true, fill = false, icon = PhosphorIcons.Regular.Play, enabled = acting == null,
                busy = acting == CloudAction.RESUME, modifier = Modifier.testTag("cloud-session-resume"))
        }
        if (session.canPause) {
            AppButton(stringResource(Res.string.cloud_pause), onClick = onPause, kind = ButtonKind.SECONDARY, small = true, fill = false, icon = PhosphorIcons.Regular.Pause,
                enabled = acting == null, busy = acting == CloudAction.PAUSE, modifier = Modifier.testTag("cloud-session-pause"))
        }
        if (session.canStop) {
            AppButton(stringResource(Res.string.cloud_stop), onClick = onStop, kind = ButtonKind.DANGER, small = true, fill = false, enabled = acting == null,
                busy = acting == CloudAction.STOP, modifier = Modifier.testTag("cloud-session-stop"))
        }
        extra()
    }
}

/**
 * Whose GitHub access the session's Git and `gh` use (`runners.cloud.github.v1`): the person's own linked account, the
 * same with the terminal shared, or none. Taking it away needs the session paused: the server's refusal is worded above.
 */
@Composable
private fun GitHubAccessChoice(current: GitHubAccess?, setting: GitHubAccess?, onChoose: (GitHubAccess) -> Unit) {
    val colors = Ostrom.colors
    val shown = setting ?: current ?: return
    SectionTitle(stringResource(Res.string.cloud_github_title))
    AppCard(Modifier.testTag("cloud-session-github")) {
        Segmented(
            options = listOf(
                GitHubAccess.OWNER to stringResource(Res.string.cloud_github_owner),
                GitHubAccess.SHARED to stringResource(Res.string.cloud_github_shared),
                GitHubAccess.NONE to stringResource(Res.string.cloud_github_none),
            ),
            selected = shown,
            onSelect = onChoose,
            tag = { "cloud-session-github-${it.wire}" },
        )
        Text(
            stringResource(
                when (shown) {
                    GitHubAccess.SHARED -> Res.string.cloud_github_shared_hint
                    GitHubAccess.NONE -> Res.string.cloud_github_none_hint
                    else -> Res.string.cloud_github_owner_hint
                },
            ),
            style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("cloud-session-github-hint"),
        )
    }
}

/** The preparation recipe, read only: the repositories, the working directory, the setup, the variables, GitHub, e-mail. */
@Composable
private fun Recipe(recipe: CloudRecipe) {
    val colors = Ostrom.colors
    SectionTitle(stringResource(Res.string.cloud_recipe))
    AppCard(Modifier.testTag("cloud-session-recipe")) {
        recipe.repositories.forEach { repository ->
            val details = listOfNotNull(
                repository.path?.let { stringResource(Res.string.cloud_recipe_repo_path, it) },
                repository.ref?.let { stringResource(Res.string.cloud_recipe_repo_ref, it) },
                repository.branch?.let { stringResource(Res.string.cloud_recipe_repo_branch, it) },
            )
            Text(repository.url.removePrefix("https://github.com/"), style = MonospaceStyle, color = colors.text)
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MetaStyle, color = colors.muted)
        }
        recipe.workingDirectory?.let { Text(stringResource(Res.string.cloud_recipe_working_directory, it), style = MetaStyle, color = colors.text) }
        Commands(stringResource(Res.string.cloud_recipe_setup_create), recipe.setupCreate)
        Commands(stringResource(Res.string.cloud_recipe_setup_start), recipe.setupStart)
        if (recipe.requiredVariables.isNotEmpty()) {
            Text(stringResource(Res.string.cloud_recipe_variables, recipe.requiredVariables.joinToString(", ")), style = MetaStyle, color = colors.text)
        }
        if (recipe.requireGitHub) Text(stringResource(Res.string.cloud_recipe_github), style = MetaStyle, color = colors.text)
        recipe.gitEmailDomain?.let { Text(stringResource(Res.string.cloud_recipe_email_domain, it), style = MetaStyle, color = colors.text) }
    }
}

@Composable
private fun Commands(title: String, commands: List<String>) {
    if (commands.isEmpty()) return
    val colors = Ostrom.colors
    Text(title, style = MetaStyle, color = colors.muted)
    commands.forEach { Text(it, style = MonospaceStyle, color = colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis) }
}

/** Asks before a session is stopped: its workspace is deleted, which cannot be undone. */
@Composable
private fun ConfirmStop(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = Ostrom.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(18.dp)
                .testTag("cloud-stop-confirm"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.cloud_stop_title), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.cloud_stop_body), color = colors.text, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(stringResource(Res.string.cloud_stop_keep), onClick = onDismiss, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f).testTag("cloud-stop-keep"))
                AppButton(stringResource(Res.string.cloud_stop), onClick = onConfirm, kind = ButtonKind.DANGER, modifier = Modifier.weight(1f).testTag("cloud-stop-yes"))
            }
        }
    }
}
