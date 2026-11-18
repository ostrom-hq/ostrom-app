// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/TodoPanel.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import run.ostrom.app.domain.models.SessionTodo

/** "4 / 9": the todo's counter. */
fun todoCounter(todo: SessionTodo) = "${todo.done} / ${todo.total}"
