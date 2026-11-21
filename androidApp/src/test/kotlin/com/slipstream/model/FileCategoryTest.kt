package com.slipstream.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Ported from RayBurst category.rs tests (L213-336) — same semantics.
class FileCategoryTest {
    private val base = "/storage/emulated/0/Download"

    private fun category() =
        FileCategory(
            label = "Documents",
            extensions = listOf("pdf"),
            directory = "Documents",
            builtIn = true,
        )

    private fun candidate(path: String) = Candidate(path)

    @Test
    fun relativeTargetsFollowBaseAndFixedTargetsDoNot() {
        for (b in listOf("/first", "/second")) {
            assertEquals(
                "$b/Documents",
                FileCategories.resolveDir(listOf(candidate("file.PDF")), listOf(category()), b),
            )
        }
        val abs = category().copy(directoryMode = DirectoryMode.ABSOLUTE, directory = "/first")
        assertEquals("/first", FileCategories.resolveDir(listOf(candidate("a.pdf")), listOf(abs), "/second"))
        val traversal = category().copy(directory = "../outside")
        assertNotNull(FileCategories.validate(listOf(traversal), "/second"))
    }

    @Test
    fun groupClassificationRequiresEveryFileAndRespectsUrlRules() {
        val rule = category()
        assertNull(
            FileCategories.resolveDir(
                listOf(candidate("a.pdf"), candidate("b.bin")),
                listOf(rule),
                base,
            ),
        )
        val urlRule = rule.copy(urlPatterns = listOf("https://*.example.test/*"))
        assertNull(FileCategories.resolveDir(listOf(candidate("a.pdf")), listOf(urlRule), base))
        val matching = Candidate(path = "a.pdf", urls = listOf("https://cdn.example.test/download"))
        assertEquals(
            "$base/Documents",
            FileCategories.resolveDir(listOf(matching), listOf(urlRule), base),
        )
        val badRegex = urlRule.copy(urlPatternMode = PatternMode.REGEX, urlPatterns = listOf("("))
        assertNotNull(FileCategories.validate(listOf(badRegex), base))
    }

    @Test
    fun ruleOrderAndMixedDestinationsHaveOnePolicy() {
        val first = category()
        val second = category().copy(directory = "Elsewhere")
        // First hit in list order wins.
        assertEquals(
            "$base/Documents",
            FileCategories.resolveDir(listOf(candidate("a.pdf")), listOf(first, second), base),
        )
        // Different resolved dirs across the group → no classification.
        val secondTxt = second.copy(extensions = listOf("txt"))
        assertNull(
            FileCategories.resolveDir(
                listOf(candidate("a.pdf"), candidate("b.txt")),
                listOf(first, secondTxt),
                base,
            ),
        )
        // A trailing slash still counts as the same directory.
        val secondSame = secondTxt.copy(directory = "Documents/")
        assertEquals(
            "$base/Documents",
            FileCategories.resolveDir(
                listOf(candidate("a.pdf"), candidate("b.txt")),
                listOf(first, secondSame),
                base,
            ),
        )
        assertNull(FileCategories.resolveDir(emptyList(), emptyList(), base))
        assertNull(FileCategories.resolveDir(listOf(Candidate("  ")), listOf(first), base))
    }

    @Test
    fun filenamesAreTextAndOnlyUrlPathsAreDecoded() {
        assertEquals(listOf("pdf"), FileCategories.extensionsOf("https://example.test/file%2Epdf?x=1"))
        assertEquals(listOf(""), FileCategories.extensionsOf("file%2Epdf"))
        assertEquals(listOf(""), FileCategories.extensionsOf("magnet:?dn=file.pdf"))
        assertEquals(listOf(""), FileCategories.extensionsOf(".gitignore"))
        assertEquals(listOf("gz"), FileCategories.extensionsOf("archive.tar.gz"))
    }

    @Test
    fun partsOfSplitArchivesGoByTheArchive() {
        assertEquals(listOf("001", "7z"), FileCategories.extensionsOf("backup.7z.001"))
        assertEquals(listOf("002", "zip"), FileCategories.extensionsOf("https://example.test/a.zip.002"))
        assertEquals(listOf("z01", "zip"), FileCategories.extensionsOf("disk.z01"))
        assertEquals(listOf("r00", "rar"), FileCategories.extensionsOf("disk.R00"))
        assertEquals(listOf("001"), FileCategories.extensionsOf("track.001"))
        assertEquals(listOf("mp4"), FileCategories.extensionsOf("movie.mp4"))
        assertEquals(listOf("zip"), FileCategories.extensionsOf("photo.zip"))
    }

    @Test
    fun magnetClassifiesByUrlPatternOnly() {
        val magnets = category().copy(extensions = emptyList(), urlPatterns = listOf("magnet:*"))
        assertEquals(
            "$base/Documents",
            FileCategories.resolveDir(
                listOf(candidate("magnet:?xt=urn:btih:deadbeef")),
                listOf(magnets),
                base,
            ),
        )
    }

    @Test
    fun normalizeAndValidate() {
        val dirty =
            FileCategory(
                label = " x ",
                extensions = listOf(".PDF", " pdf ", "", "png"),
                urlPatterns = listOf(" a ", "a", "x".repeat(513)),
                directory = "Docs",
            )
        val n = FileCategories.normalize(dirty)
        assertEquals("x", n.label)
        assertEquals(listOf("pdf", "png"), n.extensions)
        assertEquals(listOf("a"), n.urlPatterns)

        assertEquals("base", FileCategories.validate(emptyList(), "relative/base"))
        assertNull(FileCategories.validate(FileCategories.BUILTIN, base))
        val absBad = category().copy(directoryMode = DirectoryMode.ABSOLUTE, directory = "not/absolute")
        assertEquals("dir:1", FileCategories.validate(listOf(absBad), base))
        val tooLong = category().copy(urlPatterns = listOf("x".repeat(513)))
        assertEquals("toolong:1:1", FileCategories.validate(listOf(tooLong), base))
    }

    @Test
    fun emptyRulesNeverHitAndUrlOnlyRulesSkipExtCheck() {
        val empty = FileCategory(label = "empty", extensions = emptyList(), urlPatterns = emptyList(), directory = "X")
        assertNull(FileCategories.resolveDir(listOf(candidate("a.pdf")), listOf(empty), base))
        // A URL-only rule doesn't care about the extension.
        val urlOnly = FileCategory(label = "u", urlPatterns = listOf("*cdn.example*"), directory = "X")
        assertEquals(
            "$base/X",
            FileCategories.resolveDir(
                listOf(Candidate("whatever.bin", listOf("https://cdn.example/f"))),
                listOf(urlOnly),
                base,
            ),
        )
    }

    @Test
    fun schemesWithoutFilenameContributeNothing() {
        // ftp paths decode like http/https; filename-less schemes yield "".
        assertEquals(listOf("txt"), FileCategories.extensionsOf("ftp://example.test/a%2Etxt"))
        assertEquals(listOf(""), FileCategories.extensionsOf("ed2k://|file|a.zip|1|/"))
        assertEquals(listOf(""), FileCategories.extensionsOf("thunder://QUFmdHA="))
        assertEquals(listOf(""), FileCategories.extensionsOf("data:text/plain;base64,QQ=="))
        assertEquals(listOf(""), FileCategories.extensionsOf("blob:https://x/1"))
        // Scheme match is case-insensitive.
        assertEquals(listOf("png"), FileCategories.extensionsOf("HTTP://x/a.png"))
        // Fragments are stripped before decoding.
        assertEquals(listOf("pdf"), FileCategories.extensionsOf("https://x/a.pdf#frag"))
    }

    @Test
    fun percentDecodingCoversUtf8AndMalformedEscapes() {
        assertEquals(listOf("txt"), FileCategories.extensionsOf("https://x/%E4%B8%AD%E6%96%87.txt"))
        // Bad escapes stay verbatim: %GG (non-hex), %2 (truncated), trailing %.
        assertEquals(listOf("txt"), FileCategories.extensionsOf("https://x/a%GG.txt"))
        assertEquals(listOf("txt"), FileCategories.extensionsOf("https://x/a%2.txt"))
        assertEquals(listOf("txt%"), FileCategories.extensionsOf("https://x/a.txt%"))
    }

    @Test
    fun urlPatternMatchingHonorsLengthCapAndEscaping() {
        val rule =
            FileCategory(
                label = "u",
                extensions = emptyList(),
                urlPatterns = listOf("https://example.test/*"),
                directory = "U",
            )
        val inLimit = "https://example.test/" + "x".repeat(4075) // exactly 4096 chars
        val overLimit = "https://example.test/" + "x".repeat(4076) // 4097 chars
        assertEquals(
            "$base/U",
            FileCategories.resolveDir(listOf(Candidate(inLimit, listOf(inLimit))), listOf(rule), base),
        )
        assertNull(
            FileCategories.resolveDir(listOf(Candidate(overLimit, listOf(overLimit))), listOf(rule), base),
        )

        // Wildcard escapes regex metachars: '.' stays literal — "axb" misses,
        // "a.bx" hits.
        val dotted = rule.copy(urlPatterns = listOf("a.b*"))
        assertNull(
            FileCategories.resolveDir(listOf(Candidate("axb", listOf("axb"))), listOf(dotted), base),
        )
        assertEquals(
            "$base/U",
            FileCategories.resolveDir(listOf(Candidate("a.bx", listOf("a.bx"))), listOf(dotted), base),
        )
    }

    @Test
    fun validateRejectsTraversalAndModeMismatch() {
        val rel = category()
        assertEquals("dir:1", FileCategories.validate(listOf(rel.copy(directory = "a/../b")), base))
        // A RELATIVE rule must not hold an absolute path.
        assertEquals("dir:1", FileCategories.validate(listOf(rel.copy(directory = "/abs")), base))
    }

    @Test
    fun rootDirectoryNormalizesToSlashNotEmpty() {
        val root = category().copy(directoryMode = DirectoryMode.ABSOLUTE, directory = "/")
        assertEquals("/", FileCategories.resolveDir(listOf(candidate("a.pdf")), listOf(root), base))
        // "//" counts as the same directory as "/".
        val dbl = root.copy(directory = "//")
        assertEquals("/", FileCategories.resolveDir(listOf(candidate("a.pdf")), listOf(dbl), base))
        // Empty RELATIVE directory still resolves to the base dir (regression).
        val empty = category().copy(directory = "")
        assertEquals(base, FileCategories.resolveDir(listOf(candidate("a.pdf")), listOf(empty), base))
    }

    @Test
    fun compileFailedPatternNeverMatchesNotEvenEmptyInput() {
        val rule =
            FileCategory(
                label = "u",
                extensions = emptyList(),
                urlPatterns = listOf("("),
                urlPatternMode = PatternMode.REGEX,
                directory = "U",
            )
        // "$^" (the old NEVER_MATCH) would hit the empty string on JVM.
        assertNull(
            FileCategories.resolveDir(
                listOf(Candidate("x.pdf", urls = listOf(""))),
                listOf(rule),
                base,
            ),
        )
    }
}
