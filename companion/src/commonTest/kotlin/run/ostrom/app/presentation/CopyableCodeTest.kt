// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import run.ostrom.app.presentation.transcript.copyableCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the copy button of a code block puts in the clipboard. */
class CopyableCodeTest {
    @Test fun aBlockIsCopiedAsItsContentWithoutItsTrailingBlankLines() {
        assertEquals("./gradlew build", copyableCode("./gradlew build"), "A lone command is worth copying")
        assertEquals("a\n  b\nc", copyableCode("a\n  b\nc\n\n  \n"), "Indentation is kept, the trailing newline and blank lines go")
        assertEquals("one\n\ntwo", copyableCode("one\r\n\r\ntwo\r\n"), "Windows line ends are dropped")
    }

    @Test fun anEmptyBlockHasNothingToCopy() {
        assertNull(copyableCode(""))
        assertNull(copyableCode("\n\n"))
        assertNull(copyableCode("  \r\n \t\n"))
    }
}
