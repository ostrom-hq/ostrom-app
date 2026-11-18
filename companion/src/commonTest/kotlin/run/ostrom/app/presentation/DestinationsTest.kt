// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer
import run.ostrom.app.presentation.navigation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The routes are saved and restored as JSON by the navigation back stack, and compared as values. */
class DestinationsTest {
    private val json = Json

    private inline fun <reified T : Any> roundTrip(route: T) {
        assertEquals(route, json.decodeFromString(serializer<T>(), json.encodeToString(serializer<T>(), route)))
    }

    private fun <T : Any> value(route: T, same: T, others: List<T>) {
        assertTrue(route.equals(route))
        assertEquals(route, same)
        assertEquals(route.hashCode(), same.hashCode())
        assertEquals(route.toString(), same.toString())
        assertFalse(route.equals("not a route"))
        assertFalse(route.equals(null))
        others.forEach { assertNotEquals(route, it) }
    }

    @Test
    fun `the objects are singletons that survive a round trip`() {
        roundTrip(AddServerRoute)
        value(AddServerRoute, AddServerRoute, emptyList())
        assertTrue(AddServerRoute.toString().contains("AddServerRoute"))
        roundTrip(MainRoute)
        value(MainRoute, MainRoute, emptyList())
        assertTrue(MainRoute.toString().contains("MainRoute"))
    }

    @Test
    fun `LoginRoute compares by value and survives a round trip`() {
        val route = LoginRoute(origin = "a0", accountId = "a1")
        roundTrip(route)
        value(route, LoginRoute(origin = "a0", accountId = "a1"), listOf(LoginRoute(origin = "b0", accountId = "a1"), LoginRoute(origin = "a0", accountId = "b1"), LoginRoute(origin = "a0", accountId = "a1").copy(accountId = null)))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.origin, route.component1())
        assertEquals(null, LoginRoute(origin = "a0").accountId)
        roundTrip(LoginRoute(origin = "a0"))
    }

    @Test
    fun `MoreTabRoute compares by value and survives a round trip`() {
        val route = MoreTabRoute(tab = "a0", opensRequests = true)
        roundTrip(route)
        value(route, MoreTabRoute(tab = "a0", opensRequests = true), listOf(MoreTabRoute(tab = "b0", opensRequests = true), MoreTabRoute(tab = "a0", opensRequests = false)))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.tab, route.component1())
    }

    @Test
    fun `RequestRoute compares by value and survives a round trip`() {
        val route = RequestRoute(accountId = "a0", id = "a1")
        roundTrip(route)
        value(route, RequestRoute(accountId = "a0", id = "a1"), listOf(RequestRoute(accountId = "b0", id = "a1"), RequestRoute(accountId = "a0", id = "b1")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `NotificationPreferencesRoute compares by value and survives a round trip`() {
        val route = NotificationPreferencesRoute(accountId = "a0")
        roundTrip(route)
        value(route, NotificationPreferencesRoute(accountId = "a0"), listOf(NotificationPreferencesRoute(accountId = "b0")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `MemberEnvironmentRoute compares by value and survives a round trip`() {
        val route = MemberEnvironmentRoute(accountId = "a0")
        roundTrip(route)
        value(route, MemberEnvironmentRoute(accountId = "a0"), listOf(MemberEnvironmentRoute(accountId = "b0")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `PersonalMcpServersRoute compares by value and survives a round trip`() {
        val route = PersonalMcpServersRoute(accountId = "a0")
        roundTrip(route)
        value(route, PersonalMcpServersRoute(accountId = "a0"), listOf(PersonalMcpServersRoute(accountId = "b0")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `GitHubLinkRoute compares by value and survives a round trip`() {
        val route = GitHubLinkRoute(accountId = "a0")
        roundTrip(route)
        value(route, GitHubLinkRoute(accountId = "a0"), listOf(GitHubLinkRoute(accountId = "b0")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `ArtifactRoute compares by value and survives a round trip`() {
        val route = ArtifactRoute(accountId = "a0", artifactId = "a1", version = 2)
        roundTrip(route)
        value(route, ArtifactRoute(accountId = "a0", artifactId = "a1", version = 2), listOf(ArtifactRoute(accountId = "b0", artifactId = "a1", version = 2), ArtifactRoute(accountId = "a0", artifactId = "b1", version = 2), ArtifactRoute(accountId = "a0", artifactId = "a1", version = 3), ArtifactRoute(accountId = "a0", artifactId = "a1", version = 2).copy(version = null)))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
        assertEquals(null, ArtifactRoute(accountId = "a0", artifactId = "a1").version)
        roundTrip(ArtifactRoute(accountId = "a0", artifactId = "a1"))
    }

    @Test
    fun `SessionRoute compares by value and survives a round trip`() {
        val route = SessionRoute(accountId = "a0", sessionId = "a1")
        roundTrip(route)
        value(route, SessionRoute(accountId = "a0", sessionId = "a1"), listOf(SessionRoute(accountId = "b0", sessionId = "a1"), SessionRoute(accountId = "a0", sessionId = "b1")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `ReceiptRoute compares by value and survives a round trip`() {
        val route = ReceiptRoute(accountId = "a0", receiptId = "a1")
        roundTrip(route)
        value(route, ReceiptRoute(accountId = "a0", receiptId = "a1"), listOf(ReceiptRoute(accountId = "b0", receiptId = "a1"), ReceiptRoute(accountId = "a0", receiptId = "b1")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `WorkflowRoute compares by value and survives a round trip`() {
        val route = WorkflowRoute(accountId = "a0", workflowId = "a1")
        roundTrip(route)
        value(route, WorkflowRoute(accountId = "a0", workflowId = "a1"), listOf(WorkflowRoute(accountId = "b0", workflowId = "a1"), WorkflowRoute(accountId = "a0", workflowId = "b1")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `WorkflowStartRoute compares by value and survives a round trip`() {
        val route = WorkflowStartRoute(accountId = "a0", workflowId = "a1")
        roundTrip(route)
        value(route, WorkflowStartRoute(accountId = "a0", workflowId = "a1"), listOf(WorkflowStartRoute(accountId = "b0", workflowId = "a1"), WorkflowStartRoute(accountId = "a0", workflowId = "b1")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `WorkflowRunRoute compares by value and survives a round trip`() {
        val route = WorkflowRunRoute(accountId = "a0", runId = "a1")
        roundTrip(route)
        value(route, WorkflowRunRoute(accountId = "a0", runId = "a1"), listOf(WorkflowRunRoute(accountId = "b0", runId = "a1"), WorkflowRunRoute(accountId = "a0", runId = "b1")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `WorkflowEditRoute compares by value and survives a round trip`() {
        val route = WorkflowEditRoute(accountId = "a0", workflowId = "a1")
        roundTrip(route)
        value(route, WorkflowEditRoute(accountId = "a0", workflowId = "a1"), listOf(WorkflowEditRoute(accountId = "b0", workflowId = "a1"), WorkflowEditRoute(accountId = "a0", workflowId = "b1"), WorkflowEditRoute(accountId = "a0", workflowId = "a1").copy(workflowId = null)))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
        assertEquals(null, WorkflowEditRoute(accountId = "a0").workflowId)
        roundTrip(WorkflowEditRoute(accountId = "a0"))
    }

    @Test
    fun `CloudSessionRoute compares by value and survives a round trip`() {
        val route = CloudSessionRoute(accountId = "a0", sessionId = "a1")
        roundTrip(route)
        value(route, CloudSessionRoute(accountId = "a0", sessionId = "a1"), listOf(CloudSessionRoute(accountId = "b0", sessionId = "a1"), CloudSessionRoute(accountId = "a0", sessionId = "b1")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `TransferRoute compares by value and survives a round trip`() {
        val route = TransferRoute(accountId = "a0", sessionId = "a1", ownerId = "a2")
        roundTrip(route)
        value(route, TransferRoute(accountId = "a0", sessionId = "a1", ownerId = "a2"), listOf(TransferRoute(accountId = "b0", sessionId = "a1", ownerId = "a2"), TransferRoute(accountId = "a0", sessionId = "b1", ownerId = "a2"), TransferRoute(accountId = "a0", sessionId = "a1", ownerId = "b2")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `TransfersRoute compares by value and survives a round trip`() {
        val route = TransfersRoute(accountId = "a0")
        roundTrip(route)
        value(route, TransfersRoute(accountId = "a0"), listOf(TransfersRoute(accountId = "b0")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    @Test
    fun `NewCloudSessionRoute compares by value and survives a round trip`() {
        val route = NewCloudSessionRoute(accountId = "a0")
        roundTrip(route)
        value(route, NewCloudSessionRoute(accountId = "a0"), listOf(NewCloudSessionRoute(accountId = "b0")))
        val copy = route.copy()
        assertEquals(route, copy)
        assertEquals(route.accountId, route.component1())
    }

    /** A decoder that names a field the route does not have, as a damaged saved back stack could. */
    @OptIn(ExperimentalSerializationApi::class)
    private class UnknownField : AbstractDecoder() {
        override val serializersModule = EmptySerializersModule()
        override fun decodeElementIndex(descriptor: SerialDescriptor) = 99
    }

    private inline fun <reified T : Any> refusesUnknownField() {
        assertFailsWith<SerializationException> { serializer<T>().deserialize(UnknownField()) }
    }

    @Test
    fun `a saved route naming a field it does not have is refused`() {
        refusesUnknownField<AddServerRoute>()
        refusesUnknownField<LoginRoute>()
        refusesUnknownField<MainRoute>()
        refusesUnknownField<MoreTabRoute>()
        refusesUnknownField<RequestRoute>()
        refusesUnknownField<NotificationPreferencesRoute>()
        refusesUnknownField<MemberEnvironmentRoute>()
        refusesUnknownField<PersonalMcpServersRoute>()
        refusesUnknownField<GitHubLinkRoute>()
        refusesUnknownField<ArtifactRoute>()
        refusesUnknownField<SessionRoute>()
        refusesUnknownField<ReceiptRoute>()
        refusesUnknownField<WorkflowRoute>()
        refusesUnknownField<WorkflowStartRoute>()
        refusesUnknownField<WorkflowRunRoute>()
        refusesUnknownField<WorkflowEditRoute>()
        refusesUnknownField<CloudSessionRoute>()
        refusesUnknownField<TransferRoute>()
        refusesUnknownField<TransfersRoute>()
        refusesUnknownField<NewCloudSessionRoute>()
    }
}
