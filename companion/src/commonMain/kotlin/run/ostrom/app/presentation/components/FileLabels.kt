// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/FileComponents.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.foundation.layout.size

/** A size as `512 B`, `245 KB` or `1.2 MB` (powers of 1024), as the CLI words it. */
fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${(bytes * 10 + 512 * 1024) / (1024 * 1024) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** The types a team takes, by their common names: `PNG, JPEG, PDF, text`. */
fun typesLabel(types: List<String>): String = types.joinToString(", ") { TYPE_LABELS[it] ?: it }

internal fun waitLabel(seconds: Long): String = when {
    seconds >= 3600 -> "${(seconds + 1800) / 3600} h"
    seconds >= 60 -> "${(seconds + 30) / 60} min"
    else -> "$seconds s"
}

internal fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")

internal val TYPE_LABELS = mapOf(
    "image/png" to "PNG", "image/jpeg" to "JPEG", "image/webp" to "WebP", "image/gif" to "GIF", "application/pdf" to "PDF",
    "text/plain" to "text", "text/markdown" to "Markdown", "text/csv" to "CSV", "application/json" to "JSON",
)
