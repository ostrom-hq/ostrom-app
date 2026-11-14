// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../demo/DemoMode.kt; see NOTICE.
package run.ostrom.app.demo

import kotlinx.coroutines.flow.MutableStateFlow
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.services.Clock

/**
 * The built-in demo: an account on no server, whose content lives in memory only. Started from the first screen
 * ([start]), ended by signing the account out ([end]), which drops everything it held; nothing of it is written to the
 * device, so quitting the app ends it too.
 */
class DemoMode(private val clock: Clock) {
    private val current = MutableStateFlow<DemoStore?>(null)

    /** The demo account while the demo runs; null otherwise. */
    val account: Account? get() = current.value?.account?.value

    val active: Boolean get() = current.value != null

    /** Starts a fresh demo (or restarts the running one from scratch) and returns its account. */
    fun start(): Account = DemoStore(clock.nowMillis()).also { current.value = it }.account.value

    /** Ends the demo: its account and content are gone. */
    fun end() {
        current.value = null
    }

    /** The running demo's data; a demo that ended reads as something that no longer exists. */
    internal fun store(): DemoStore = current.value ?: throw AppException.NotFound()
}

/** One run of the demo: what the person sees and changes, until the demo ends. */
internal class DemoStore(val startedAt: Long) {
    val account = MutableStateFlow(DemoContent.account())
    val waits = MutableStateFlow(demoWaits(startedAt))
    val notifications = MutableStateFlow(NotificationPreferences(requests = true, decisions = true, spending = true))

    /** The approvals inbox: two requests waiting, one answered; the demo's answers move them. */
    val approvals = MutableStateFlow(DemoContent.approvals(startedAt))
}

/** "Try the demo" on the first screen: offered only on a device without any account, so a demo never mixes with a real one. */
class StartDemoUseCase(private val accounts: AccountRepository) {
    /** False once an account exists, or where the accounts are not the demo-aware ones (a test's own repository). */
    val offered: Boolean get() = accounts is DemoAccountRepository && accounts.accounts.value.isEmpty()

    operator fun invoke(): Account {
        val demo = accounts as? DemoAccountRepository ?: error("The demo needs the demo-aware account repository.")
        return demo.startDemo()
    }
}
