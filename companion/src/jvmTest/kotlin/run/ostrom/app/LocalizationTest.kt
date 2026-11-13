// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../LocalizationTest.kt; see NOTICE.
package run.ostrom.app

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The translations of the app: every language has every text of the default (English) one, with the same placeholders,
 * and each language is declared to the operating systems. Gradle runs the tests of a module from its folder.
 */
class LocalizationTest {
    private val resources = File("src/commonMain/composeResources")

    private fun load(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resources, "$folder/strings.xml"))
        val entries = document.documentElement.childNodes
        return (0 until entries.length).mapNotNull { entries.item(it) as? Element }.associate { node ->
            assertEquals("string", node.tagName, "Unexpected <${node.tagName}> in $folder/strings.xml")
            node.getAttribute("name") to node.textContent
        }
    }

    private fun languages(): Set<String> =
        setOf("en") + resources.listFiles().orEmpty().map { it.name }.filter { it.startsWith("values-") }.map { it.removePrefix("values-") }

    private fun placeholders(text: String) = Regex("%(\\d+)\\$[sdf]").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyLanguageHasEveryTextWithItsPlaceholders() {
        val default = load("values")
        assertTrue("fr" in languages())
        for (language in languages() - "en") {
            val translated = load("values-$language")
            assertEquals(default.keys.sorted(), translated.keys.sorted(), "values-$language and values differ in their keys")
            for ((key, text) in default) assertEquals(placeholders(text), placeholders(translated.getValue(key)), "$key in values-$language")
        }
    }

    @Test
    fun noTextIsEmptyOrEscapesAnApostrophe() {
        for (language in languages()) {
            val folder = if (language == "en") "values" else "values-$language"
            for ((key, text) in load(folder)) {
                assertTrue(text.isNotBlank(), "$key in $folder is empty")
                // Compose does not unescape \', so the apostrophe stays bare.
                assertFalse(text.contains("\\'"), "$key in $folder escapes an apostrophe")
            }
        }
    }

    @Test
    fun everyLanguageIsDeclaredToTheSystems() {
        val languages = languages()
        val android = File("../companion-android/src/main/res/xml/locales_config.xml").readText()
        assertEquals(languages, Regex("<locale android:name=\"([^\"]+)\"").findAll(android).map { it.groupValues[1] }.toSet(), "locales_config.xml")
        assertTrue("android:localeConfig=\"@xml/locales_config\"" in File("../companion-android/src/main/AndroidManifest.xml").readText())

        val plist = File("../companion-ios/Ostrom/Info.plist").readText()
        val bundle = Regex("<key>CFBundleLocalizations</key>\\s*<array>(.*?)</array>", RegexOption.DOT_MATCHES_ALL).find(plist)?.groupValues?.get(1).orEmpty()
        assertEquals(languages, Regex("<string>([^<]+)</string>").findAll(bundle).map { it.groupValues[1] }.toSet(), "CFBundleLocalizations in Info.plist")

        val project = File("../companion-ios/Ostrom.xcodeproj/project.pbxproj").readText()
        val regions = Regex("knownRegions = \\((.*?)\\);", RegexOption.DOT_MATCHES_ALL).find(project)?.groupValues?.get(1).orEmpty()
        assertEquals(languages + "Base", Regex("[A-Za-z_-]+").findAll(regions).map { it.value }.toSet(), "knownRegions in project.pbxproj")
    }
}
