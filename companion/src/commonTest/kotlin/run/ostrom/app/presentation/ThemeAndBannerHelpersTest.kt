// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import androidx.compose.ui.platform.UriHandler
import run.ostrom.app.demo.DemoContent
import run.ostrom.app.presentation.components.openRepository
import run.ostrom.app.presentation.files.FileSource
import run.ostrom.app.presentation.theme.DarkPalette
import run.ostrom.app.presentation.theme.LightPalette
import run.ostrom.app.presentation.theme.materialScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ThemeAndBannerHelpersTest {
    @Test
    fun theMaterialSchemeTakesTheTokensOfItsPalette() {
        for (palette in listOf(DarkPalette, LightPalette)) {
            val scheme = palette.materialScheme()
            assertEquals(palette.accent, scheme.primary)
            assertEquals(palette.background, scheme.background)
            assertEquals(palette.panel, scheme.surface)
            assertEquals(palette.danger, scheme.error)
        }
        // The unchanged parts of Material's own dark and light schemes tell the two apart.
        assertNotEquals(DarkPalette.materialScheme().inverseSurface, LightPalette.materialScheme().inverseSurface)
    }

    @Test
    fun theFileSourcesAreThePaperclipsMenu() {
        assertEquals(listOf(FileSource.PHOTOS, FileSource.CAMERA, FileSource.DOCUMENTS), FileSource.entries)
        assertEquals(FileSource.CAMERA, FileSource.valueOf("CAMERA"))
    }

    private class Browser(private val failure: Exception? = null) : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            failure?.let { throw it }
            opened += uri
        }
    }

    @Test
    fun theRepositoryOpensInTheBrowserAndADeviceWithoutOneLeavesTheAppAsItIs() {
        val browser = Browser()
        openRepository(browser)
        assertEquals(listOf(DemoContent.REPOSITORY_URL), browser.opened)
        val none = Browser(IllegalStateException("no browser"))
        openRepository(none)
        assertEquals(emptyList(), none.opened)
    }
}
