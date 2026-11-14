// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/TodoPanel.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Circle
import com.adamglin.phosphoricons.regular.MinusCircle
import com.adamglin.phosphoricons.regular.Warning
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.SessionTodo
import run.ostrom.app.domain.models.TodoItem
import run.ostrom.app.domain.models.TodoItemState
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.todo_added_along
import run.ostrom.app.resources.todo_added_reason
import run.ostrom.app.resources.todo_attested_flag
import run.ostrom.app.resources.todo_counter_description
import run.ostrom.app.resources.todo_current
import run.ostrom.app.resources.todo_done_when
import run.ostrom.app.resources.todo_drop_reason
import run.ostrom.app.resources.todo_hook_errors
import run.ostrom.app.resources.todo_last_proof
import run.ostrom.app.resources.todo_last_proof_failed
import run.ostrom.app.resources.todo_none
import run.ostrom.app.resources.todo_reconnecting
import run.ostrom.app.resources.todo_reference
import run.ostrom.app.resources.todo_state_doing
import run.ostrom.app.resources.todo_state_done
import run.ostrom.app.resources.todo_state_drop_requested
import run.ostrom.app.resources.todo_state_dropped
import run.ostrom.app.resources.todo_state_todo
import run.ostrom.app.resources.todo_status_abandoned
import run.ostrom.app.resources.todo_status_done
import run.ostrom.app.resources.todo_unverified

/** "4 / 9": the todo's counter. */
fun todoCounter(todo: SessionTodo) = "${todo.done} / ${todo.total}"

/**
 * A session's todo, kept current by the feed: the plan's reference, the counter and its bar, the item in progress, the
 * items in order with their state, end condition and last proof, those added along the way and those dropped (with
 * why), and the Stop hook's errors. [loaded] false: not read yet; [todo] null once read: the agent has none.
 */
@Composable
fun TodoPanel(todo: SessionTodo?, loaded: Boolean, reconnecting: Boolean, now: Long, modifier: Modifier = Modifier) {
    val colors = Ostrom.colors
    Column(modifier.fillMaxWidth().testTag("todo-view"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (reconnecting) Text(stringResource(Res.string.todo_reconnecting), fontSize = 12.sp, color = colors.warn, modifier = Modifier.testTag("todo-reconnecting"))
        if (!loaded) { BusyRow(true); return@Column }
        if (todo == null) { Text(stringResource(Res.string.todo_none), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("todo-none")); return@Column }
        AppCard {
            Text(stringResource(Res.string.todo_reference).uppercase(), fontSize = 11.sp, color = colors.muted)
            Text(todo.reference, fontSize = 14.sp, lineHeight = 19.sp, color = colors.text, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("todo-reference"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val description = stringResource(Res.string.todo_counter_description, todo.done, todo.total)
                Text(todoCounter(todo), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.text, modifier = Modifier.testTag("todo-counter"))
                Text(description, fontSize = 12.sp, color = colors.muted)
                when (todo.status) {
                    "done" -> Chip(stringResource(Res.string.todo_status_done), tone = Tone.ACCENT)
                    "abandoned" -> Chip(stringResource(Res.string.todo_status_abandoned), tone = Tone.WARN)
                }
            }
            ProgressBar(todo.done, todo.total)
            todo.current?.let {
                Text(stringResource(Res.string.todo_current, it.name), fontSize = 13.sp, color = colors.accentText, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("todo-current"))
            }
            if (todo.mostlyAttested) Text(stringResource(Res.string.todo_attested_flag), fontSize = 12.sp, lineHeight = 16.sp, color = colors.warn, modifier = Modifier.testTag("todo-attested-flag"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (item in todo.items) TodoRow(item, now)
        }
        val errors = todo.hookEvents.filter { it.kind == "hook_error" }
        if (errors.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("todo-hook-errors")) {
            SectionTitle(stringResource(Res.string.todo_hook_errors))
            val today = localEpochDay(now)
            for (event in errors.takeLast(3)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(PhosphorIcons.Regular.Warning, contentDescription = null, tint = colors.warn, modifier = Modifier.padding(top = 1.dp).size(14.dp))
                Text(listOfNotNull(event.at?.let { formatEventTime(it, today) }, event.text).joinToString(" · "), fontSize = 12.sp, lineHeight = 16.sp, color = colors.muted)
            }
        }
    }
}

@Composable
private fun ProgressBar(done: Int, total: Int) {
    val colors = Ostrom.colors
    val shape = RoundedCornerShape(3.dp)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(shape).background(colors.line)) {
        if (total > 0 && done > 0) Box(Modifier.fillMaxWidth(done.toFloat() / total).fillMaxHeight().background(colors.accent, shape))
    }
}

@Composable
private fun TodoRow(item: TodoItem, now: Long) {
    val colors = Ostrom.colors
    val (icon, tint) = when (item.state) {
        TodoItemState.DONE -> PhosphorIcons.Regular.CheckCircle to colors.accentText
        TodoItemState.DOING -> PhosphorIcons.Regular.Circle to colors.accent
        TodoItemState.DROPPED -> PhosphorIcons.Regular.MinusCircle to colors.muted
        TodoItemState.DROP_REQUESTED -> PhosphorIcons.Regular.Warning to colors.warn
        else -> PhosphorIcons.Regular.Circle to colors.muted
    }
    val label = stringResource(when (item.state) {
        TodoItemState.DONE -> Res.string.todo_state_done
        TodoItemState.DOING -> Res.string.todo_state_doing
        TodoItemState.DROPPED -> Res.string.todo_state_dropped
        TodoItemState.DROP_REQUESTED -> Res.string.todo_state_drop_requested
        else -> Res.string.todo_state_todo
    })
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).then(if (item.state == TodoItemState.DOING) Modifier.background(colors.accentDim, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 7.dp).testTag("todo-item-${item.id}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, color = if (item.state == TodoItemState.DROPPED) colors.muted else colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (item.description.isNotBlank()) Text(item.description, color = colors.muted, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.todo_done_when, item.doneWhen), color = colors.muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            item.proof?.let { proof ->
                val at = proof.at?.let { formatEventTime(it, localEpochDay(now)) } ?: ""
                Text(stringResource(if (proof.passed == false) Res.string.todo_last_proof_failed else Res.string.todo_last_proof, at, proof.text),
                    color = if (proof.passed == false) colors.danger else colors.text, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("todo-proof-${item.id}"))
            }
            if (item.state == TodoItemState.DROPPED || item.state == TodoItemState.DROP_REQUESTED) item.dropReason?.let {
                Text(stringResource(Res.string.todo_drop_reason, it), color = colors.muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("todo-drop-${item.id}"))
            }
            if (item.addedLater) item.addedReason?.let { Text(stringResource(Res.string.todo_added_reason, it), color = colors.muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (item.addedLater || item.unverified || item.state == TodoItemState.DROP_REQUESTED || item.state == TodoItemState.DROPPED) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.addedLater) Chip(stringResource(Res.string.todo_added_along), tone = Tone.NEUTRAL, modifier = Modifier.testTag("todo-added-${item.id}"))
                    if (item.unverified) Chip(stringResource(Res.string.todo_unverified), tone = Tone.WARN, modifier = Modifier.testTag("todo-unverified-${item.id}"))
                    if (item.state == TodoItemState.DROP_REQUESTED) Chip(stringResource(Res.string.todo_state_drop_requested), tone = Tone.WARN)
                    if (item.state == TodoItemState.DROPPED) Chip(stringResource(Res.string.todo_state_dropped), tone = Tone.MUTED, modifier = Modifier.testTag("todo-dropped-${item.id}"))
                }
            }
        }
    }
}
