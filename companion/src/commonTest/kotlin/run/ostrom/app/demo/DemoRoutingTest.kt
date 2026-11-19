// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.demo

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import run.ostrom.app.CountingRandom
import run.ostrom.app.FakePlatform
import run.ostrom.app.ORIGIN
import run.ostrom.app.SERVER
import run.ostrom.app.account
import run.ostrom.app.di.appModules
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.NewDeviceSession
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.PickedFile
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferRequest
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.ActivityRepository
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.repositories.ArtifactRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.CloudSessionRepository
import run.ostrom.app.domain.repositories.DeviceRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.GitHubLinkRepository
import run.ostrom.app.domain.repositories.MemberSetupRepository
import run.ostrom.app.domain.repositories.NotificationPreferencesRepository
import run.ostrom.app.domain.repositories.NotificationSettingsRepository
import run.ostrom.app.domain.repositories.ReceiptRepository
import run.ostrom.app.domain.repositories.SessionFileRepository
import run.ostrom.app.domain.repositories.SessionRepository
import run.ostrom.app.domain.repositories.SpendingRepository
import run.ostrom.app.domain.repositories.TodoRepository
import run.ostrom.app.domain.repositories.TranscriptRepository
import run.ostrom.app.domain.repositories.TransferRepository
import run.ostrom.app.domain.repositories.WaitRepository
import run.ostrom.app.domain.repositories.WorkflowEditorRepository
import run.ostrom.app.domain.repositories.WorkflowRepository
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.domain.services.SecureRandom
import run.ostrom.app.infrastructure.ScriptedServer
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's repositories as the modules wire them: every call for the demo account is answered from memory without a
 * request, and every call for any other account reaches its server.
 */
class DemoRoutingTest {
    private val server = ScriptedServer()
    private val demoId = DemoContent.ACCOUNT_ID

    private fun platform(): Module = module {
        single<Settings> { MapSettings() }
        single<CredentialStore> { MemoryCredentialStore() }
        single<HttpClientEngineFactory<*>> { server.engine }
        single<PlatformInfo> { FakePlatform }
        single<SecureRandom> { CountingRandom }
    }

    // The modules are shared values: an application left open keeps its singletons for the next one that loads them.
    private val started = mutableListOf<KoinApplication>()

    private fun koin(): Koin = koinApplication { modules(appModules(platform())) }.also { started += it }.koin

    @AfterTest fun stop() = started.forEach { it.close() }

    /** One call of every method of every repository the demo stands in for, for [id] on [origin]. */
    private fun Koin.calls(id: String, origin: String): List<Triple<String, Boolean, suspend () -> Any?>> {
        val pick = this
        val file = PickedFile("a.txt", 1) { Buffer() }
        val device = NewDeviceSession("n", DemoDevices.LAPTOP, "engine", "claude", null, null, PermissionMode.ASK, null)
        // The boolean says whether the call must reach the network for an account that is not the demo's.
        return buildList {
            fun add(name: String, network: Boolean = true, call: suspend () -> Any?) = add(Triple(name, network, call))
            with(pick.get<AuthRepository>()) {
                add("auth.discover") { discover(origin) }
                add("auth.prepareBrowserSignIn", false) { prepareBrowserSignIn(origin, "r", "s") }
                add("auth.exchangeCode") { exchangeCode(origin, "c", "v", "r", null) }
                add("auth.startDeviceLogin") { startDeviceLogin(SERVER.copy(origin = origin), null) }
                add("auth.pollDeviceLogin") { pollDeviceLogin(origin, "d") }
                add("auth.capabilities(origin)") { capabilities(origin, "t") }
                add("auth.capabilities(account)") { capabilities(id) }
                add("auth.organizations(origin)") { organizations(origin, "t") }
                add("auth.organizations(account)") { organizations(id) }
                add("auth.me(origin)") { me(origin, "t") }
                add("auth.me(account)") { me(id) }
                add("auth.logout") { logout(id) }
                add("auth.release", false) { release(id) }
                add("auth.abandon", false) { abandon(origin, "t") }
            }
            with(pick.get<FeaturesRepository>()) {
                add("features.capabilities") { capabilities(id, true) }
                add("features.cached", false) { cached(id) }
            }
            with(pick.get<WaitRepository>()) {
                add("waits.list") { list(id, WaitStatus.OPEN, null) }
                add("waits.answer") { answer(id, "w1", "approve", emptyMap()) }
                add("waits.inProgress") { inProgress(id) }
            }
            with(pick.get<NotificationPreferencesRepository>()) {
                add("preferences.get") { preferences(id) }
                add("preferences.save") { save(id, NotificationPreferences(true, true, true)) }
            }
            with(pick.get<NotificationSettingsRepository>()) {
                add("notifications.settings") { settings(id) }
                add("notifications.set") { set(id, "push", NotificationKind.WAITING, true) }
                add("notifications.test") { test(id) }
            }
            with(pick.get<SpendingRepository>()) { add("spending.report") { report(id, SpendPeriod.WEEK) } }
            with(pick.get<ActivityRepository>()) { add("activity.page") { page(id, null) } }
            with(pick.get<ArtifactRepository>()) {
                add("artifacts.list") { artifacts(id, null) }
                add("artifacts.get") { artifact(id, DemoArtifactIds.PLAN) }
                add("artifacts.versions") { versions(id, DemoArtifactIds.PLAN) }
                add("artifacts.ticket") { ticket(id, DemoArtifactIds.PLAN, null) }
            }
            with(pick.get<ReceiptRepository>()) {
                add("receipts.page") { page(id, null) }
                add("receipts.get") { get(id, "rcpt_demo-merge") }
                add("receipts.keys") { keys(id) }
            }
            with(pick.get<SessionRepository>()) {
                add("sessions.list") { sessions(id, null, null) }
                add("sessions.get") { session(id, DemoSessionIds.OPEN) }
            }
            with(pick.get<TodoRepository>()) { add("todos.get") { todo(id, DemoSessionIds.OPEN) } }
            with(pick.get<WorkflowRepository>()) {
                add("workflows.list") { workflows(id) }
                add("workflows.get") { workflow(id, DemoWorkflowIds.RELEASE) }
                add("workflows.runs") { runs(id, null, null, null) }
                add("workflows.run") { run(id, DemoWorkflowIds.RUN_DONE) }
                add("workflows.start") { start(id, DemoWorkflowIds.RELEASE, emptyMap(), "k") }
                add("workflows.cancel") { cancel(id, DemoWorkflowIds.RUN_WAITING) }
            }
            with(pick.get<WorkflowEditorRepository>()) {
                add("editor.rights") { rights(id) }
                add("editor.editable") { editable(id, DemoWorkflowIds.RELEASE) }
                add("editor.create") { create(id, WorkflowDraft("n")) }
                add("editor.save") { save(id, editable(id, DemoWorkflowIds.RELEASE), WorkflowDraft("n")) }
                add("editor.delete") { delete(id, DemoWorkflowIds.TRIAGE) }
            }
            with(pick.get<MemberSetupRepository>()) {
                add("setup.environment") { environment(id) }
                add("setup.mcp") { mcpServers(id) }
            }
            with(pick.get<GitHubLinkRepository>()) {
                add("github.link") { link(id) }
                add("github.start") { start(id) }
                add("github.poll") { poll(id, "demo-device") }
                add("github.unlink") { unlink(id) }
            }
            with(pick.get<CloudSessionRepository>()) {
                add("cloud.sessions") { sessions(id, null, emptySet()) }
                add("cloud.createOnDevice") { createOnDevice(id, device, "k") }
                add("cloud.session") { session(id, DemoCloudSessionIds.RUNNING) }
                add("cloud.create") { create(id, NewCloudSession(CloudAgent.CLAUDE), "k") }
                add("cloud.pause") { pause(id, DemoCloudSessionIds.RUNNING) }
                add("cloud.resume") { resume(id, DemoCloudSessionIds.RUNNING) }
                add("cloud.stop") { stop(id, DemoCloudSessionIds.RUNNING) }
                add("cloud.setGitHubAccess") { setGitHubAccess(id, DemoCloudSessionIds.RUNNING, GitHubAccess.OWNER) }
                add("cloud.send") { send(id, DemoCloudSessionIds.CONVERSATION, "hi", "m", emptyList()) }
                add("cloud.interrupt") { interrupt(id, DemoCloudSessionIds.CONVERSATION) }
                add("cloud.answer") { answer(id, DemoCloudSessionIds.CONVERSATION, PermissionAnswer("r", true), "k") }
                add("cloud.stopTask") { stopTask(id, DemoCloudSessionIds.CONVERSATION, "t") }
                add("cloud.setPermissionBypass") { setPermissionBypass(id, DemoCloudSessionIds.CONVERSATION, true) }
                add("cloud.setModel") { setModel(id, DemoCloudSessionIds.CONVERSATION, "m") }
                add("cloud.setEffort") { setEffort(id, DemoCloudSessionIds.CONVERSATION, "low") }
            }
            with(pick.get<DeviceRepository>()) {
                add("devices.online") { onlineDevices(id) }
                add("devices.folders") { folders(id, DemoDevices.LAPTOP) }
                add("devices.resumable") { resumable(id, DemoDevices.LAPTOP, "engine", 10, null) }
            }
            with(pick.get<TransferRepository>()) {
                add("transfers.transfer") { transfer(id, "s", TransferRequest("m"), "k") }
                add("transfers.list") { transfers(id, TransferDirection.INCOMING, null) }
                add("transfers.accept") { accept(id, "t", null) }
                add("transfers.decline") { decline(id, "t") }
                add("transfers.cancel") { cancel(id, "t") }
                add("transfers.recipients") { recipients(id) }
            }
            with(pick.get<SessionFileRepository>()) {
                add("files.policy") { policy(id) }
                add("files.upload", false) { upload(id, "s", file, "k") { _, _ -> } }
                add("files.typeFor", false) { typeFor("a.png") }
            }
            with(pick.get<TranscriptRepository>()) {
                add("transcripts.page") { page(id, DemoCloudSessionIds.CONVERSATION, null) }
                add("transcripts.feedPage") { feedPage(id, DemoCloudSessionIds.CONVERSATION, null, "main") }
                add("transcripts.agents") { agents(id, DemoCloudSessionIds.CONVERSATION) }
                add("transcripts.follow", false) { follow(id, DemoCloudSessionIds.CONVERSATION, 0) }
            }
            with(pick.get<ApprovalRepository>()) {
                add("approvals.queue") { queue(id) }
                add("approvals.view") { view(id, "demo-tool") }
                add("approvals.answer") { answer(id, "demo-tool", ApprovalAnswer.APPROVE, "sha256:demo-tool", null) }
                add("approvals.unlock") { unlock(id, "k", null) }
            }
        }
    }

    private val failures = mutableMapOf<String, String>()

    private suspend fun attempt(name: String = "", call: suspend () -> Any?) {
        try {
            call()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Refusals are the point of several calls: only where a call went is checked.
            failures[name] = "${error::class.simpleName}: ${error.message}"
        }
    }

    @Test fun everyCallForTheDemoAccountIsAnsweredFromMemoryWithoutARequest() = runTest {
        val koin = koin()
        assertEquals(emptyList(), koin.get<SettingsAccountRepository>().accounts.value, "accounts before the demo")
        koin.get<StartDemoUseCase>().invoke()
        koin.calls(demoId, DemoContent.ORIGIN).forEach { (name, _, call) -> attempt(name, call) }
        assertEquals(emptyList(), server.requests.map { it.url.toString() })
    }

    @Test fun everyCallForAnotherAccountReachesItsServer() = runTest {
        val koin = koin()
        koin.get<AccountRepository>().save(account())
        koin.get<CredentialStore>().write("a1", "osm_token")
        val silent = mutableListOf<String>()
        koin.calls("a1", ORIGIN).forEach { (name, network, call) ->
            val before = server.requests.size
            attempt(name, call)
            if (network && server.requests.size == before) silent += name
        }
        assertTrue(silent.isEmpty(), "reached no server: ${silent.map { it + " -> " + failures[it] }}")
    }

    @Test fun theDemoStaysIdleWithoutAStartAndTheDemoAccountThenFindsNothing() = runTest {
        val koin = koin()
        val failures = koin.calls(demoId, DemoContent.ORIGIN).filter { it.first == "waits.list" || it.first == "spending.report" }
        failures.forEach { (name, _, call) ->
            val failed = try { call(); false } catch (_: run.ostrom.app.domain.models.AppException.NotFound) { true }
            assertTrue(failed, name)
        }
        assertTrue(server.requests.isEmpty())
        emptyFlow<Unit>()
    }
}
