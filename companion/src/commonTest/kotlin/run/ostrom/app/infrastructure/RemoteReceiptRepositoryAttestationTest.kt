// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.ReceiptCheckNode
import run.ostrom.app.domain.models.RunOrigin
import run.ostrom.app.infrastructure.repositories.RemoteReceiptRepository
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import run.ostrom.app.infrastructure.repositories.readReceipt
import run.ostrom.app.infrastructure.repositories.textAt
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a check node's attestation says about where the check ran. */
class RemoteReceiptRepositoryAttestationTest {
    private val server = ScriptedServer()

    @Test fun everyAttestationOfACheckIsItsOrigin() = runTest {
        server.signedIn()
        fun node(id: String, attestation: String) = """{"id":"$id","workflowId":"w","kind":"check","label":"$id","outcome":"passed","attestation":"$attestation"}"""
        server.json(
            """{"items":[{"id":"rcpt_c","subject":{"kind":"workflow","id":"w@p","digest":"d"},"initiator":"ada","rules":[],"decisions":[],
            "nodes":[${node("laptop", "workstation_declared")},${node("runner", "isolated_runner")},{"id":"old","workflowId":"w","kind":"check","label":"old","outcome":"passed"}],"createdAt":"2026-11-13T09:00:00.000Z","signature":"s"}]}""",
        )
        val summary = RemoteReceiptRepository(server.calls).page("a1").items.single().summary!!
        assertEquals(
            listOf(
                ReceiptCheckNode("laptop", "passed", RunOrigin.WORKSTATION_DECLARED),
                ReceiptCheckNode("runner", "passed", RunOrigin.ISOLATED_RUNNER),
                // A check of a server that did not say where it ran.
                ReceiptCheckNode("old", "passed", null),
            ),
            summary.checkNodes,
        )
    }

    @Test fun aReceiptWhoseFieldsHaveTheWrongShapeIsListedWithoutASummary() {
        val base = """{"id":"rcpt_x","subject":{"kind":"workflow","id":"w@p","digest":"d"},"initiator":"ada","rules":[],"decisions":[],"createdAt":"2026-11-13T09:00:00.000Z","signature":"s""""
        for (extra in listOf(""","costUsd":"cheap"""", ""","costUsd":{"a":1}""", ""","decisions":"none"""", ""","nodes":7""")) {
            val raw = Json.parseToJsonElement(base + extra + "}").jsonObject
            val entry = readReceipt(raw)
            assertNull(entry.summary, extra)
            assertEquals(raw, entry.raw, "the object is kept for its signature whatever its shape")
        }
    }

    @Test fun aKindThisVersionDoesNotKnowIsShownAsWrittenAndAnyOtherShapeReadsAsEmpty() {
        val unknown = """{"id":"rcpt_n","subject":{"kind":"budget","id":"b1","digest":"d"},"initiator":"ada","rules":[],"decisions":[],"createdAt":"2026-11-13T09:00:00.000Z","signature":"s"}"""
        assertEquals("budget", readReceipt(Json.parseToJsonElement(unknown).jsonObject).summary?.subjectKind)
        fun text(json: String) = textAt(Json.parseToJsonElement(json).jsonObject, "subject", "kind")
        assertEquals("budget", text("""{"subject":{"kind":"budget"}}"""))
        assertEquals("", text("""{}"""), "no subject")
        assertEquals("", text("""{"subject":"budget"}"""), "a subject that is no object")
        assertEquals("", text("""{"subject":{}}"""), "no kind")
        assertEquals("", text("""{"subject":{"kind":{"name":"budget"}}}"""), "a kind that is no text")
    }
}
