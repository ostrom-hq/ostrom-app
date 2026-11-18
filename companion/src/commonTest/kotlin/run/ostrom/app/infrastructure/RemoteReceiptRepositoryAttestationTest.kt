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
            "nodes":[${node("laptop", "workstation_declared")},${node("runner", "isolated_runner")}],"createdAt":"2026-11-13T09:00:00.000Z","signature":"s"}]}""",
        )
        val summary = RemoteReceiptRepository(server.calls).page("a1").items.single().summary!!
        assertEquals(
            listOf(ReceiptCheckNode("laptop", "passed", RunOrigin.WORKSTATION_DECLARED), ReceiptCheckNode("runner", "passed", RunOrigin.ISOLATED_RUNNER)),
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
}
