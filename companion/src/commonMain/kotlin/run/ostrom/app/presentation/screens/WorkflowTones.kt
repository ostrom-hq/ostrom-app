// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WorkflowComponents.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.presentation.components.Tone

internal fun runStatusTone(status: RunStatus): Tone = when (status) {
    RunStatus.RUNNING, RunStatus.QUEUED -> Tone.ACCENT
    RunStatus.WAITING -> Tone.WARN
    RunStatus.FAILED -> Tone.DANGER
    RunStatus.SUCCEEDED -> Tone.NEUTRAL
    RunStatus.CANCELLED, RunStatus.UNKNOWN -> Tone.MUTED
}
