// SPDX-License-Identifier: Apache-2.0
// The demo of TeamCode's companion (Apache-2.0), with Ostrom's artifacts; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Artifact
import run.ostrom.app.domain.models.ArtifactPage
import run.ostrom.app.domain.models.ArtifactTicket
import run.ostrom.app.domain.models.ArtifactVersion
import run.ostrom.app.domain.repositories.ArtifactRepository

private const val HOUR = 3_600_000L

/** The demo's artifact ids, shaped like the server's (UUIDs). */
internal object DemoArtifactIds {
    const val PLAN = "00000000-0000-4000-8000-000000000a01"
    const val ROOT_CAUSE = "00000000-0000-4000-8000-000000000a02"
}

/** A published page of the demo, with the HTML of each of its versions (oldest first). */
internal class DemoPage(val id: String, val title: String, val owner: String, val access: String, val ageHours: Long, val versions: List<String>)

private val pages = listOf(
    DemoPage(
        DemoArtifactIds.PLAN, "Billing migration plan", "ada", "team", 2,
        listOf(
            page("Billing migration plan", "Version 1", "<p>Move the invoices to the new billing API, one service at a time.</p>"),
            page(
                "Billing migration plan", "Version 2",
                "<p>Move the invoices to the new billing API, one service at a time, behind a flag.</p>" +
                    "<ol><li>Read both APIs and compare the totals for a week.</li><li>Write to the new API only, the old one kept for reading.</li>" +
                    "<li>Remove the old client once the totals match for a month.</li></ol>",
            ),
        ),
    ),
    DemoPage(
        DemoArtifactIds.ROOT_CAUSE, "Flaky checkout test: root cause", "charles", "personal", 30,
        listOf(
            page(
                "Flaky checkout test", "Version 1",
                "<p>The test reads the cart before the session cookie is written: it fails when the write is slower than 50 ms.</p>" +
                    "<p>The fix waits for the write; the test passed 50 runs in a row.</p>",
            ),
        ),
    ),
)

/** A small self-contained page in the brand's tones, light or dark as the system is. */
private fun page(title: String, version: String, body: String) = """
    <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
    <title>$title</title><style>
    :root { color-scheme: light dark; --bg: #faf8f7; --text: #1d1a1a; --muted: #6b6363; --accent: #8c2f39; }
    @media (prefers-color-scheme: dark) { :root { --bg: #151313; --text: #f1eded; --muted: #a39a9a; --accent: #d9707b; } }
    body { margin: 0; padding: 20px 16px; background: var(--bg); color: var(--text); font: 16px/1.5 -apple-system, system-ui, sans-serif; }
    h1 { font-size: 22px; margin: 0 0 4px; text-wrap: balance; } .meta { color: var(--muted); font-size: 12px; letter-spacing: .06em; text-transform: uppercase; }
    main { max-width: 65ch; } li { margin: 6px 0; } li::marker { color: var(--accent); }
    </style></head><body><main><p class="meta">$version · demo</p><h1>$title</h1>$body</main></body></html>
""".trimIndent()

/** The demo's gallery: two pages from memory, one with two versions. */
class DemoArtifactRepository(private val demo: DemoMode) : ArtifactRepository {
    private fun find(artifactId: String) = pages.firstOrNull { it.id == artifactId } ?: throw AppException.NotFound()

    // No console page: the demo's server does not exist, so nothing is opened or shared outside the app.
    private fun DemoPage.artifact(startedAt: Long) = Artifact(id, title, owner, access, if (owner == DemoContent.account().login) "owner" else "team", versions.size, "", startedAt - ageHours * HOUR)

    override suspend fun artifacts(accountId: String, before: String?): ArtifactPage {
        val startedAt = demo.store().startedAt
        return ArtifactPage(if (before == null) pages.map { it.artifact(startedAt) } else emptyList(), null)
    }

    override suspend fun artifact(accountId: String, artifactId: String): Artifact = find(artifactId).artifact(demo.store().startedAt)

    override suspend fun versions(accountId: String, artifactId: String): List<ArtifactVersion> {
        val startedAt = demo.store().startedAt
        val page = find(artifactId)
        return page.versions.indices.reversed().map { index ->
            ArtifactVersion(index + 1, startedAt - (page.ageHours + (page.versions.size - 1 - index) * 24) * HOUR, page.owner, 1)
        }
    }

    override suspend fun ticket(accountId: String, artifactId: String, version: Int?): ArtifactTicket {
        demo.store()
        val page = find(artifactId)
        val number = version ?: page.versions.size
        val html = page.versions.getOrNull(number - 1) ?: throw AppException.NotFound()
        return ArtifactTicket(artifactId, number, "about:blank", null, inlineHtml = html)
    }
}

/** The demo account's artifacts from memory, every other account's from its server. */
class DemoAwareArtifactRepository(private val real: ArtifactRepository, private val demo: ArtifactRepository) : ArtifactRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun artifacts(accountId: String, before: String?) = of(accountId).artifacts(accountId, before)
    override suspend fun artifact(accountId: String, artifactId: String) = of(accountId).artifact(accountId, artifactId)
    override suspend fun versions(accountId: String, artifactId: String) = of(accountId).versions(accountId, artifactId)
    override suspend fun ticket(accountId: String, artifactId: String, version: Int?) = of(accountId).ticket(accountId, artifactId, version)
}
