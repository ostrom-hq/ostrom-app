// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.services.unlockKeyEntry
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test

/**
 * The app against the real Ostrom server, run by ostrom-server's app-journey workflow (demo/PARCOURS.md 6.7); skipped
 * when `OSTROM_E2E_SERVER` is not set, as in this repository's CI. The workflow prepared the server with
 * scripts/real-server-session.sh: the phone's person signed in as the app does (PKCE, `POST /auth/mobile/token`), and
 * that session and its unlock key are given here; another member asked two pushes to main that wait for them, and the
 * organization's budget is passed. The whole app, over the JDK HTTP client: the yes (biometrics stood in for, then the
 * unlock on the server) and the no with its reason, their signed receipts verified in the history, the spending past
 * its budget, the activity, the account. The workflow then checks the gates and their signed receipts through the API.
 */
@OptIn(ExperimentalTestApi::class)
class RealServerJourneyTest {
    private val env = System.getenv()

    private fun required(name: String): String = env[name]?.takeIf { it.isNotBlank() } ?: error("$name is not set")

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 30_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.tap(tag: String, scroll: Boolean = true) {
        val node = onNodeWithTag(tag)
        (if (scroll) node.performScrollTo() else node).performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun thePhonesPersonSaysYesAndNoAndSeesTheSpendingAndActivity() {
        val server = env["OSTROM_E2E_SERVER"]?.takeIf { it.isNotBlank() } ?: return
        val login = required("OSTROM_E2E_LOGIN")
        val yes = required("OSTROM_E2E_YES")
        val no = required("OSTROM_E2E_NO")
        val repo = required("OSTROM_E2E_REPO")
        val settings = MapSettings()
        // A phone's account: the session and the unlock key its sign-in gave.
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = server, login = login, method = SignInMethod.BROWSER))
        val credentials = MemoryCredentialStore()
        runBlocking {
            credentials.write("a1", required("OSTROM_E2E_TOKEN"))
            credentials.write(unlockKeyEntry("a1"), required("OSTROM_E2E_UNLOCK_KEY"))
        }
        testApp(Java, credentials = credentials, settings = settings, biometrics = FakeBiometricGate()) {
            // The two requests wait in the inbox.
            waitFor("request-$yes")
            waitFor("request-$no")

            // The yes: the unlock bound to the request, then the answer.
            tap("request-$yes")
            waitFor("request-approve")
            tap("request-approve")
            waitFor("request-outcome")
            tap("back", scroll = false)

            // The no, with its reason.
            waitFor("request-$no")
            tap("request-$no")
            waitFor("request-decline")
            tap("request-decline")
            onNodeWithTag("request-reason").performTextInput("Not this push")
            tap("request-send-decline")
            waitFor("request-outcome")
            tap("back", scroll = false)

            // The history: the receipts the server signed for the two answers, each verified on this device against the
            // key it pinned on this first view.
            tap("tab-history", scroll = false)
            waitFor("check-verified")
            onNodeWithTag("history-error").assertDoesNotExist()

            // The spending (PARCOURS 3.5): the organization's monthly budget, and the asker's own, passed.
            tap("tab-spending", scroll = false)
            waitForText("The organization is past its monthly budget of $0.01")
            waitForText("The model calls are refused until the month ends.")
            waitForText("${login.replace("app-owner-", "app-asker-")} is past their monthly budget of $0.01")

            // The organization's activity: the requests of the repository and their answers.
            tap("tab-activity", scroll = false)
            waitForText(repo)
            onNodeWithTag("activity-empty").assertDoesNotExist()
            onNodeWithTag("activity-error-state").assertDoesNotExist()

            // Who is signed in, and their only organization, read from the server (`GET /me/organizations`): the
            // session was given here, so the account names none until the person chooses.
            tap("tab-settings", scroll = false)
            waitForText(login)
            tap("organization")
            waitForText("acme-e2e")
        }
    }
}
