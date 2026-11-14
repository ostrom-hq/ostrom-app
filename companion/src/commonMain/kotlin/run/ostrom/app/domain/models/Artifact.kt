// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/models/Artifact.kt; see NOTICE.
package run.ostrom.app.domain.models

/**
 * A published page the person can read. [url] is its page in the console, the link to share; [access] is `personal`,
 * `team` or `link`; [relation] says what the person is to it (`owner`, `viewer`, `team`), when the server says.
 * [updatedAt] in epoch milliseconds, null when unreadable.
 */
data class Artifact(
    val id: String,
    val title: String,
    val owner: String,
    val access: String,
    val relation: String?,
    val latestVersion: Int,
    val url: String,
    val updatedAt: Long?,
)

/** A page of artifacts, newest first; [next] is the cursor of the older page, null on the last one. */
data class ArtifactPage(val artifacts: List<Artifact>, val next: String?)

/** One version: its number, when and by whom it was published (epoch milliseconds), and how many files it holds. */
data class ArtifactVersion(val number: Int, val createdAt: Long?, val createdBy: String, val fileCount: Int)

/**
 * A reading ticket: [contentUrl] serves one version's entry page and its files on the server's content origin until
 * [expiresAt], without any credential. [inlineHtml] is the demo's page, held in memory and shown without any network;
 * a server's ticket never has one.
 */
data class ArtifactTicket(val artifactId: String, val version: Int, val contentUrl: String, val expiresAt: Long?, val inlineHtml: String? = null)
