// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/SessionsScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import run.ostrom.app.domain.models.CloudSession

/** What a cloud session is called: its name, else its repository's, else its agent. */
internal fun CloudSession.title(): String = name?.takeIf { it.isNotBlank() } ?: repo?.substringAfterLast('/') ?: agent
