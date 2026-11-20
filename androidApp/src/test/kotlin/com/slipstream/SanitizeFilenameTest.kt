package com.slipstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Filename hints cross a trust boundary (browser → engine fs). */
class SanitizeFilenameTest {
    @Test
    fun stripsPathTraversal() {
        assertEquals("passwd", ExtensionApiServer.sanitizeFilename("../../etc/passwd"))
        assertEquals("passwd", ExtensionApiServer.sanitizeFilename("..\\..\\windows\\passwd"))
        assertEquals("file.txt", ExtensionApiServer.sanitizeFilename("/a/b/file.txt"))
    }

    @Test
    fun stripsControlAndReservedChars() {
        assertEquals("ab", ExtensionApiServer.sanitizeFilename("a\u0000b"))
        assertEquals("file.txt", ExtensionApiServer.sanitizeFilename("file<>.txt"))
        assertEquals("name", ExtensionApiServer.sanitizeFilename("na|me?"))
    }

    @Test
    fun rejectsEmptyAndDotNames() {
        assertNull(ExtensionApiServer.sanitizeFilename(""))
        assertNull(ExtensionApiServer.sanitizeFilename("   "))
        assertNull(ExtensionApiServer.sanitizeFilename("."))
        assertNull(ExtensionApiServer.sanitizeFilename(".."))
        assertNull(ExtensionApiServer.sanitizeFilename("/"))
    }

    @Test
    fun keepsOrdinaryNames() {
        assertEquals("ubuntu-24.04.iso", ExtensionApiServer.sanitizeFilename("ubuntu-24.04.iso"))
        assertEquals("中文 文件.zip", ExtensionApiServer.sanitizeFilename("中文 文件.zip"))
    }
}
