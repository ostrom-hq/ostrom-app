// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/commonMain/kotlin/me/nathanfallet/teamcode/companion/presentation/components/DemoBanner.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Flask
import com.adamglin.phosphoricons.regular.GithubLogo
import run.ostrom.app.demo.DemoContent
import run.ostrom.app.demo.isDemo
import run.ostrom.app.domain.models.Account
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.demo_banner_detail
import run.ostrom.app.resources.demo_banner_title
import run.ostrom.app.resources.demo_github
import org.jetbrains.compose.resources.stringResource

/**
 * "You are trying the demo" at the top of the main screen, under the update banner, with a link to the project on
 * GitHub (star it, deploy a server). Only for the demo account: an account on a real server never shows it. It heads
 * the screen, so it takes the status bar's inset itself (its background runs under the bar); the content below it is
 * given [underDemoBanner] so it does not pad for the status bar a second time.
 */
@Composable
fun DemoBanner(account: Account?) {
    if (account?.isDemo != true) return
    val colors = Ostrom.colors
    val browser = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().background(colors.panel).windowInsetsPadding(WindowInsets.statusBars).testTag("demo-banner")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PhosphorIcons.Regular.Flask, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.demo_banner_title), color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(Res.string.demo_banner_detail), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
            AppButton(stringResource(Res.string.demo_github), onClick = { openRepository(browser) }, small = true, fill = false, icon = PhosphorIcons.Regular.GithubLogo,
                modifier = Modifier.testTag("demo-github"))
        }
        HorizontalDivider(color = colors.line)
    }
}

/** For the content under [DemoBanner]: while the banner shows, it already took the status bar's inset. */
@Composable
fun Modifier.underDemoBanner(account: Account?): Modifier = if (account?.isDemo == true) consumeWindowInsets(WindowInsets.statusBars) else this

/** The project's page in the browser; a device without one leaves the app as it is. */
internal fun openRepository(browser: androidx.compose.ui.platform.UriHandler) {
    runCatching { browser.openUri(DemoContent.REPOSITORY_URL) }
}
