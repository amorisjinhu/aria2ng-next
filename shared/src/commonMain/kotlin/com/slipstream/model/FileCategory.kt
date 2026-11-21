package com.slipstream.model

import kotlinx.serialization.Serializable

@Serializable
enum class PatternMode { WILDCARD, REGEX }

@Serializable
enum class DirectoryMode { RELATIVE, ABSOLUTE }

/**
 * IDM-style file category: an add-time rule that routes a download into a
 * subdirectory of the base dir (or an absolute path) when its filename
 * extension and/or source URL matches. Ported from RayBurst's category.rs.
 */
@Serializable
data class FileCategory(
    val label: String, // built-in entries use an i18n key suffix ("videos"…)
    val extensions: List<String> = emptyList(), // lowercase, no leading dot
    val urlPatterns: List<String> = emptyList(),
    val urlPatternMode: PatternMode = PatternMode.WILDCARD,
    val directory: String,
    val directoryMode: DirectoryMode = DirectoryMode.RELATIVE,
    val builtIn: Boolean = false,
)

@Serializable
data class CategorySpec(
    val enabled: Boolean = false,
    val categories: List<FileCategory> = emptyList(),
)

data class Candidate(
    val path: String,
    val urls: List<String> = emptyList(),
)

/**
 * Classification rule engine — pure functions, no IO.
 *
 * [validate] returns machine-readable error codes instead of message text so
 * the UI can localize: "base" (base dir not absolute), "dir:<cat#>",
 * "toolong:<cat#>:<line#>", "regex:<cat#>:<line#>" — all 1-based.
 */
object FileCategories {
    const val MAX_CATEGORIES = 20
    private const val MAX_PATTERN_LEN = 512
    private const val MAX_URL_LEN = 4096

    // Extension lists copied verbatim from RayBurst BUILTIN_CATEGORY_TEMPLATES.
    val BUILTIN: List<FileCategory> =
        listOf(
            FileCategory(
                label = "videos",
                extensions = listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "rmvb"),
                directory = "Videos",
                builtIn = true,
            ),
            FileCategory(
                label = "music",
                extensions = listOf("mp3", "flac", "aac", "ogg", "wav", "wma", "m4a", "opus", "ape"),
                directory = "Music",
                builtIn = true,
            ),
            FileCategory(
                label = "images",
                extensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "svg", "webp", "ico", "tiff", "psd", "raw"),
                directory = "Images",
                builtIn = true,
            ),
            FileCategory(
                label = "documents",
                extensions =
                    listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub", "md", "rtf"),
                directory = "Documents",
                builtIn = true,
            ),
            FileCategory(
                label = "archives",
                extensions = listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "dmg", "iso", "zst"),
                directory = "Archives",
                builtIn = true,
            ),
            FileCategory(
                label = "programs",
                extensions = listOf("exe", "msi", "deb", "rpm", "appimage", "pkg", "apk", "snap"),
                directory = "Programs",
                builtIn = true,
            ),
        )

    fun defaults(): CategorySpec = CategorySpec(enabled = false, categories = BUILTIN)

    fun normalize(c: FileCategory): FileCategory =
        c.copy(
            label = c.label.trim(),
            extensions =
                c.extensions
                    .map { it.trim().trimStart('.').lowercase() }
                    .filter { it.isNotEmpty() }
                    .distinct(),
            urlPatterns =
                c.urlPatterns
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.length <= MAX_PATTERN_LEN }
                    .distinct(),
        )

    /** First error code, or null when the whole table is usable. */
    fun validate(
        categories: List<FileCategory>,
        baseDir: String,
    ): String? {
        if (!baseDir.startsWith("/")) return "base"
        categories.forEachIndexed { i, c ->
            if (dirInvalid(c)) return "dir:${i + 1}"
            c.urlPatterns.forEachIndexed { j, p ->
                if (p.isBlank()) return@forEachIndexed
                if (p.length > MAX_PATTERN_LEN) return "toolong:${i + 1}:${j + 1}"
                if (c.urlPatternMode == PatternMode.REGEX &&
                    runCatching { Regex(p, RegexOption.IGNORE_CASE) }.isFailure
                ) {
                    return "regex:${i + 1}:${j + 1}"
                }
            }
        }
        return null
    }

    /**
     * The absolute target dir for [candidates], or null when any candidate
     * matches no category or the group resolves to different directories
     * (first-hit per candidate, in [categories] order).
     */
    fun resolveDir(
        candidates: List<Candidate>,
        categories: List<FileCategory>,
        baseDir: String,
    ): String? {
        if (!baseDir.startsWith("/")) return null
        val compiled = categories.map { it to compiledPatterns(it) }
        var resolved: String? = null
        for (candidate in candidates) {
            if (candidate.path.isBlank()) continue
            val names = extensionsOf(candidate.path)
            val match =
                compiled.firstOrNull { (cat, pats) ->
                    val extOk =
                        cat.extensions.isEmpty() ||
                            cat.extensions.any { e ->
                                val v = e.trim().trimStart('.')
                                names.any { it.equals(v, ignoreCase = true) }
                            }
                    val urlOk =
                        pats.isEmpty() ||
                            (candidate.urls + candidate.path).any { u ->
                                u.length <= MAX_URL_LEN && pats.any { it.containsMatchIn(u) }
                            }
                    // A rule with neither extensions nor patterns can never hit.
                    !(cat.extensions.isEmpty() && pats.isEmpty()) && extOk && urlOk
                } ?: return null
            val dir = resolveCategoryDir(match.first, baseDir)?.let(::normalizeDir) ?: return null
            if (resolved != null && resolved != dir) return null
            resolved = dir
        }
        return resolved
    }

    /** Candidate list for a URL-add: explicit `out` filename first, then each URL. */
    fun addCandidates(
        urls: List<String>,
        options: Map<String, String>,
    ): List<Candidate> =
        (options["out"]?.let { listOf(Candidate(it, urls)) } ?: emptyList()) +
            urls.map { Candidate(it, listOf(it)) }

    /**
     * Extensions a file goes by: its own, and — for a split-archive part
     * (.7z.001, .z01, .r00) — the archive's, so parts land with the archive.
     */
    fun extensionsOf(path: String): List<String> {
        val name = fileNameOf(path)
        val own = lowerExtension(name)
        val digits = { t: String -> t.isNotEmpty() && t.all { it in '0'..'9' } }
        val archive =
            when {
                // ".001"-style part: the inner stem's extension (".7z" → "7z")
                own.length == 3 && digits(own) ->
                    lowerExtension(name.dropLast(own.length + 1)).takeIf { it.isNotEmpty() }

                own.length == 3 && own[0] == 'z' && digits(own.substring(1)) -> "zip"
                own.length == 3 && own[0] == 'r' && digits(own.substring(1)) -> "rar"
                else -> null
            }
        return listOfNotNull(own, archive)
    }

    /**
     * Basename used for extension checks. http/https/ftp URLs contribute only
     * their (percent-decoded) path; schemes that carry no filename contribute
     * nothing; anything else is treated as a literal path.
     */
    private fun fileNameOf(value: String): String {
        fun starts(scheme: String) = value.startsWith(scheme, ignoreCase = true)
        return when {
            starts("http://") || starts("https://") || starts("ftp://") -> {
                val path =
                    value
                        .substringAfter("://")
                        .substringAfter('/', "")
                        .substringBefore('?')
                        .substringBefore('#')
                percentDecode(path).substringAfterLast('/')
            }

            starts("magnet:") ||
                starts("data:") ||
                starts("blob:") ||
                starts("ed2k:") ||
                starts("thunder:") -> ""

            else -> value.substringAfterLast('/')
        }
    }

    /** Path-extension semantics: ".gitignore" has none, "foo." has "". */
    private fun lowerExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot + 1).lowercase() else ""
    }

    private fun dirInvalid(c: FileCategory): Boolean =
        when (c.directoryMode) {
            DirectoryMode.ABSOLUTE -> !c.directory.startsWith("/")
            DirectoryMode.RELATIVE ->
                c.directory.startsWith("/") || c.directory.split('/').any { it == ".." }
        }

    private fun resolveCategoryDir(
        c: FileCategory,
        baseDir: String,
    ): String? =
        if (dirInvalid(c)) {
            null
        } else {
            when (c.directoryMode) {
                DirectoryMode.ABSOLUTE -> c.directory
                DirectoryMode.RELATIVE -> baseDir.trimEnd('/') + "/" + c.directory
            }
        }

    /** Same-dir check: trailing slashes and repeated separators don't matter. */
    private fun normalizeDir(d: String): String = d.replace(Regex("/+"), "/").removeSuffix("/").ifEmpty { "/" }

    private fun compiledPatterns(c: FileCategory): List<Regex> =
        c.urlPatterns.filter { it.isNotBlank() }.map { p ->
            runCatching {
                when (c.urlPatternMode) {
                    // Kotlin's Regex.escape emits \Q..\E quoting (not \*), so
                    // the wildcard build splits on '*' and escapes each literal
                    // segment — same result as Rust's escape-then-\*→.*.
                    PatternMode.WILDCARD ->
                        Regex(
                            p.split('*').joinToString(".*", prefix = "^", postfix = "$") { Regex.escape(it) },
                            RegexOption.IGNORE_CASE,
                        )

                    PatternMode.REGEX -> Regex(p, RegexOption.IGNORE_CASE)
                }
            }.getOrDefault(NEVER_MATCH) // validate() gates bad regexes first
        }

    // A compile-failed pattern still counts as "present" (the URL rule stays
    // required) but can never match anything — (?!) fails even on empty input
    // ("$^" would match the empty string on JVM).
    private val NEVER_MATCH = Regex("(?!)")

    /** UTF-8 percent decoder — hand-rolled, no new dependency. */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val src = s.encodeToByteArray()
        val out = ByteArray(src.size)
        var n = 0
        var i = 0
        while (i < src.size) {
            val decoded =
                if (src[i] == '%'.code.toByte() && i + 2 < src.size) {
                    hexPair(src, i + 1)
                } else {
                    -1
                }
            if (decoded >= 0) {
                out[n++] = decoded.toByte()
                i += 3
            } else {
                out[n++] = src[i]
                i++
            }
        }
        return out.copyOf(n).decodeToString() // malformed UTF-8 → U+FFFD, never throws
    }

    private fun hexPair(
        src: ByteArray,
        at: Int,
    ): Int {
        val hi = hexDigit(src[at])
        val lo = hexDigit(src[at + 1])
        return if (hi >= 0 && lo >= 0) hi * 16 + lo else -1
    }

    private fun hexDigit(b: Byte): Int =
        when (b.toInt().toChar()) {
            in '0'..'9' -> b - '0'.code
            in 'a'..'f' -> b - 'a'.code + 10
            in 'A'..'F' -> b - 'A'.code + 10
            else -> -1
        }
}
