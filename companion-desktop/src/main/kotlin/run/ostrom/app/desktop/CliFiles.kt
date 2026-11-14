// SPDX-License-Identifier: Apache-2.0
// The stamps are ported from TeamCode (Apache-2.0), companion-desktop/.../CliCredentialStore.kt (CliConfigFile); see NOTICE.
package run.ostrom.app.desktop

import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The CLI's sign-in as its files say it: the [server] `ostrom` works with, without a trailing slash. */
data class CliSignIn(val server: String)

/**
 * The two files of the `ostrom` CLI's folder the app follows, read only, never written: `config.toml` (its top-level
 * `server`, set by `ostrom login`) and `credentials.json` (`{"<server>": {"login": "<login>"}}`, an entry per server
 * signed in; `ostrom logout` removes it). The device token itself is not in a file: it is in the OS secret store
 * ([CliKeyring]).
 */
class CliFiles(val directory: String?) {
    /** What identifies one version of a file: its modification time, size and identity; null when there is none. */
    data class Stamp(val modified: Long, val size: Long, val key: Any?)

    /** The stamps of both files: a change of either is a sign-in, a sign-out or another server. */
    data class Stamps(val config: Stamp?, val credentials: Stamp?)

    private val config: Path? = directory?.let { Path.of(it, CONFIG) }
    private val credentials: Path? = directory?.let { Path.of(it, CREDENTIALS) }

    fun stamps(): Stamps = Stamps(config?.let(::stamp), credentials?.let(::stamp))

    /**
     * The CLI's sign-in: the server of `config.toml` (Ostrom's own when it names none, as the CLI does), when
     * `credentials.json` has an entry for it. Null without one, and for files the CLI could not read either.
     */
    fun signIn(): CliSignIn? {
        val server = (config?.let(::text)?.let(::serverOf) ?: DEFAULT_SERVER).trimEnd('/')
        val entries = credentials?.let(::text)?.let(::entriesOf) ?: return null
        // The login in the entry is only what the CLI shows: the account reads the person from the server.
        if (entries[server] !is JsonObject) return null
        return CliSignIn(server)
    }

    private fun stamp(path: Path): Stamp? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        Stamp(attributes.lastModifiedTime().toMillis(), attributes.size(), attributes.fileKey())
    } catch (_: NoSuchFileException) {
        null
    }

    private fun text(path: Path): String? = try {
        if (Files.size(path) > MAX_FILE_BYTES) null else Files.readString(path)
    } catch (_: NoSuchFileException) {
        null
    } catch (_: CharacterCodingException) {
        null
    } catch (_: IOException) {
        null
    }

    companion object {
        const val CONFIG = "config.toml"
        const val CREDENTIALS = "credentials.json"

        /** The server the CLI works with when `config.toml` names none (ostrom-cli's `DEFAULT_SERVER`). */
        const val DEFAULT_SERVER = "https://api.ostrom.run"

        /** Both files are a few hundred bytes; anything larger is not one the CLI wrote. */
        private const val MAX_FILE_BYTES = 1_048_576L

        private val json = Json { ignoreUnknownKeys = true }

        private fun entriesOf(text: String): JsonObject? = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        /**
         * The top-level `server` of a `config.toml`, read as the CLI reads it: a key before the first `[table]`, its
         * value a basic (`"…"`, escapes read) or literal (`'…'`) string, with an optional trailing comment. Null when
         * there is none or it is not a string.
         */
        internal fun serverOf(toml: String): String? {
            var server: String? = null
            for (raw in toml.lines()) {
                val line = raw.trim()
                if (line.startsWith("[")) break
                if (line.startsWith("#") || '=' !in line) continue
                if (line.substringBefore('=').trim() != "server") continue
                server = unquote(line.substringAfter('=').trim())
            }
            return server?.takeIf { it.isNotBlank() }
        }

        private fun unquote(text: String): String? = when {
            // A multi-line string is not one the CLI writes: skipped, never misread as an empty one.
            text.startsWith("\"\"\"") || text.startsWith("'''") -> null
            text.startsWith("'") -> text.indexOf('\'', startIndex = 1).takeIf { it > 0 }?.let { text.substring(1, it) }
            text.startsWith("\"") -> unquoteBasic(text)
            else -> null
        }

        private fun unquoteBasic(text: String): String? {
            val value = StringBuilder()
            var index = 1
            while (index < text.length && text[index] != '"') {
                val character = text[index]
                if (character == '\\' && index + 1 < text.length) {
                    val escape = text[index + 1]
                    val digits = when (escape) { 'u' -> 4; 'U' -> 8; else -> 0 }
                    val code = if (digits > 0) text.substring(index + 2, minOf(text.length, index + 2 + digits)).takeIf { it.length == digits }?.toIntOrNull(16) else null
                    when {
                        code != null && Character.isValidCodePoint(code) -> { value.appendCodePoint(code); index += 2 + digits }
                        else -> { value.append(ESCAPES[escape] ?: escape); index += 2 }
                    }
                } else {
                    value.append(character)
                    index++
                }
            }
            return if (index < text.length) value.toString() else null
        }

        private val ESCAPES = mapOf('b' to '\b', 't' to '\t', 'n' to '\n', 'f' to '\u000c', 'r' to '\r', '"' to '"', '\\' to '\\')
    }
}
