// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/ArtifactContent.jvm.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual val embedsArtifacts: Boolean = false

/** Never shown: the desktop has no embedded browser, the screen opens the console page in the system browser instead. */
@Composable
actual fun ArtifactContentView(
    contentUrl: String,
    prefix: String,
    html: String?,
    reload: Int,
    onExternal: (String) -> Unit,
    onMissing: (String) -> Unit,
    onCrashed: () -> Unit,
    modifier: Modifier,
) = Unit
