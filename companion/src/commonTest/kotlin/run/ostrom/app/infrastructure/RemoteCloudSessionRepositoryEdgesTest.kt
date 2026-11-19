// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.infrastructure.repositories.RemoteCloudSessionRepository
import run.ostrom.app.infrastructure.repositories.cloudRefusalOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** A session record with little in it, and the statuses and keys a start's refusal stands for. */
class RemoteCloudSessionRepositoryEdgesTest {
    private val server = ScriptedServer()

    private fun session(id: String, extra: String = "") = """{"id":"$id","name":"Mill","owner":"m1","agent":"claude","limits":{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":false},"status":"running","desiredStatus":"running","epoch":1,
        "createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:01:00Z","githubAccess":"none"$extra}"""

    @Test fun aSessionWithoutARecipeOrASetupHasNoneAndNeverRequiresGitHubUnlessAsked() = runTest {
        server.signedIn()
        server.json(
            """{"items":[${session("bare")},
            ${session("recipe", ""","environment":{"repositories":[{"url":"https://github.com/acme/api"}],"requiredVariables":[],"requireGitHub":false}""")}]}""",
        )
        val (bare, recipe) = RemoteCloudSessionRepository(server.calls).sessions("a1", null, emptySet()).sessions
        assertNull(bare.recipe)
        val kept = recipe.recipe!!
        assertEquals(emptyList(), kept.setupCreate)
        assertEquals(emptyList(), kept.setupStart)
        assertFalse(kept.requireGitHub)
    }

    @Test fun theGitHubKeysAreRefusalsOnlyWhenTheGitHubAccessIsChanged() {
        assertEquals(CloudSessionRefusal.GITHUB_OWNER_ONLY, cloudRefusalOf(403, "github_owner_only", creating = false, githubAccess = true))
        assertNull(cloudRefusalOf(403, "github_owner_only", creating = false, githubAccess = false))
        assertEquals(CloudSessionRefusal.GITHUB_NOT_PAUSED, cloudRefusalOf(409, "session_not_paused", creating = false, githubAccess = true))
        assertNull(cloudRefusalOf(409, "session_not_paused", creating = false, githubAccess = false))
    }

    @Test fun anInvalidRequestIsARefusalOnlyForAStartAndOnlyForAClientError() {
        assertEquals(CloudSessionRefusal.INVALID, cloudRefusalOf(400, "invalid_request", creating = true))
        assertNull(cloudRefusalOf(400, "invalid_request", creating = false))
        assertEquals(CloudSessionRefusal.PLAN_LIMIT, cloudRefusalOf(402, "payment_required", creating = false))
        assertNull(cloudRefusalOf(500, "payment_required", creating = false))
        assertNull(cloudRefusalOf(399, "payment_required", creating = false))
        assertNull(cloudRefusalOf(402, "something_else", creating = false))
        assertNull(cloudRefusalOf(402, null, creating = true))
    }
}
