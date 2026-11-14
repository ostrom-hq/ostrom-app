// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/ArtifactContent.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** True where artifacts show inside the app (a web view, iOS and Android); the desktop opens the console page in the browser. */
expect val embedsArtifacts: Boolean

/**
 * One artifact version in a web view without cookies, storage, JavaScript bridge or file access. Only pages under
 * [prefix] (the ticket's directory) load in it; a link elsewhere goes to [onExternal] (the screen asks before
 * opening it in the browser), and other schemes are refused. [html], the demo's page, is shown in place of
 * [contentUrl], with nothing to navigate to. A `404` for the page itself calls [onMissing] (an expired ticket, or a
 * link to a file the version lacks); a dead renderer calls [onCrashed]. A new [reload] builds a new web view, even
 * for the same URL. The content's own sandbox comes from the server's `Content-Security-Policy: sandbox`.
 */
@Composable
expect fun ArtifactContentView(
    contentUrl: String,
    prefix: String,
    html: String?,
    reload: Int,
    onExternal: (String) -> Unit,
    onMissing: (String) -> Unit,
    onCrashed: () -> Unit,
    modifier: Modifier = Modifier,
)
