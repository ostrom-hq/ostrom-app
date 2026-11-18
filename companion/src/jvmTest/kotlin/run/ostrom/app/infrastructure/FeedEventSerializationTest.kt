// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The event the organization feed reads is private to its repository and only ever decoded there; this writes it back
 * (through the class itself, by reflection) to pin the wire shape it stands for, and the defaults of its optional fields.
 */
class FeedEventSerializationTest {
    private val type = Class.forName("run.ostrom.app.infrastructure.repositories.FeedEvent")

    @Suppress("UNCHECKED_CAST")
    private val serializer = type.getField("Companion").also { it.isAccessible = true }.get(null)
        .let { companion -> companion.javaClass.getMethod("serializer").also { it.isAccessible = true }.invoke(companion) } as KSerializer<Any>

    private fun encoded(event: Any): JsonElement = Json.encodeToJsonElement(serializer, event)

    @Test fun anEventIsWrittenWithItsOptionalFieldsOnlyWhenTheyAreSet() {
        val full = type.getConstructor(Long::class.javaPrimitiveType, String::class.java, String::class.java, JsonElement::class.java)
            .also { it.isAccessible = true }.newInstance(7L, "r1", "m1", buildJsonObject { put("workflowId", "wf1") })
        assertEquals(
            buildJsonObject { put("seq", 7L); put("subjectId", "r1"); put("memberId", "m1"); put("data", buildJsonObject { put("workflowId", "wf1") }) },
            encoded(full),
        )
        val ctor = type.getConstructor(Long::class.javaPrimitiveType, String::class.java, String::class.java, JsonElement::class.java).also { it.isAccessible = true }
        assertEquals(buildJsonObject { put("seq", 1L); put("subjectId", "r"); put("memberId", "m") }, encoded(ctor.newInstance(1L, "r", "m", null)))
        assertEquals(buildJsonObject { put("seq", 2L); put("subjectId", "r"); put("data", JsonPrimitive(3)) }, encoded(ctor.newInstance(2L, "r", null, JsonPrimitive(3))))
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        // The generated constructor Kotlin calls when the optional fields are left out (bits 4 and 8 of its mask).
        val bare = type.getConstructor(Long::class.javaPrimitiveType, String::class.java, String::class.java, JsonElement::class.java, Int::class.javaPrimitiveType, marker)
            .also { it.isAccessible = true }.newInstance(8L, "r2", null, null, 12, null)
        assertEquals(buildJsonObject { put("seq", 8L); put("subjectId", "r2") }, encoded(bare))
    }

    @Test fun anEventIsReadBackFromItsWireShape() {
        val wire: JsonObject = buildJsonObject { put("seq", 9L); put("subjectId", "r3"); put("data", JsonPrimitive("text")) }
        val read = Json.decodeFromJsonElement(serializer, wire)
        assertEquals(wire, encoded(read))
    }
}
