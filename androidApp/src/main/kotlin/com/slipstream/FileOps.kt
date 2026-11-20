package com.slipstream

import android.content.Intent
import android.widget.Toast
import com.slipstream.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Shared-storage path on ANY volume (primary emulated or SD secondary) —
 * app-private dirs live under Android/data (excluded) or /data (not /storage).
 * Hardcoding /storage/emulated/0 missed SD-card public dirs entirely.
 */
internal fun isPublicStoragePath(path: String): Boolean = path.startsWith("/storage/") && !path.contains("/Android/data/")

/** Open a downloaded file via FileProvider — file:// URIs are banned on API 24+. */
internal fun openLocalFile(
    ctx: android.content.Context,
    path: String,
) {
    val f = File(path)
    if (!f.isFile) {
        Toast.makeText(ctx, ctx.getString(R.string.file_missing), Toast.LENGTH_SHORT).show()
        return
    }
    // getUriForFile throws IllegalArgumentException when no <paths> root
    // matches (e.g. a secondary SD-card volume) — it must be inside the guard.
    val uri =
        runCatching {
            androidx.core.content.FileProvider
                .getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        }.getOrNull()
    if (uri == null) {
        Toast.makeText(ctx, ctx.getString(R.string.open_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val mime =
        android.webkit.MimeTypeMap
            .getSingleton()
            .getMimeTypeFromExtension(f.extension.lowercase())
            ?: "*/*"
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }.onFailure {
        Toast.makeText(ctx, ctx.getString(R.string.no_app), Toast.LENGTH_SHORT).show()
    }
}

/** Streamed SHA-256 — 1 MiB chunks with a progress callback, off the main thread. */
internal suspend fun sha256Hex(
    path: String?,
    onProgress: (Int) -> Unit,
): String? =
    withContext(Dispatchers.IO) {
        val f = File(path ?: return@withContext null)
        if (!f.isFile) return@withContext null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val total = f.length().coerceAtLeast(1)
        var done = 0L
        f.inputStream().buffered().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                // Closing the dialog cancels us — stop hashing a multi-GB file
                // nobody is waiting for anymore.
                coroutineContext.ensureActive()
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                done += n
                onProgress((done * 100 / total).toInt())
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

/**
 * Wipe a download's payload: every listed file + its .aria2 control file,
 * then walk up empty dirs (BT layout) without ever leaving the download dir.
 * Returns the number of files kept/failed (0 = everything wiped).
 *
 * Persisted records carry completion-time path snapshots: if the user deleted
 * the old file and ANOTHER download since produced a file with the same name,
 * blind deletion destroys unrelated data. Only wipe a persisted file whose
 * size still matches the record; unverifiable entries are kept and reported.
 */
internal suspend fun deleteDownloadFiles(d: Download): Int =
    withContext(Dispatchers.IO) {
        var problems = 0
        val paths =
            d.files.map { it.path }.filter { it.isNotEmpty() }.ifEmpty {
                listOf(File(d.dir, d.name).path)
            }
        paths.forEach { p ->
            runCatching {
                val f = File(p)
                if (d.persisted) {
                    val recorded = d.files.firstOrNull { it.path == p }
                    if (recorded == null || recorded.length <= 0 || !f.isFile || f.length() != recorded.length) {
                        problems++
                        return@runCatching
                    }
                }
                if (f.exists() && !f.delete()) problems++
                File("$p.aria2").delete()
                if (d.dir.isNotEmpty()) {
                    // Canonical paths + trailing separator: "/a/bc" must NOT
                    // match root "/a/b" (bare startsWith would delete a
                    // sibling's empty dirs).
                    val rootPath = File(d.dir).canonicalPath
                    var parent = f.canonicalFile.parentFile
                    while (
                        parent != null &&
                        parent.canonicalPath != rootPath &&
                        parent.canonicalPath.startsWith("$rootPath/")
                    ) {
                        if (!parent.delete()) break // non-empty → stop
                        parent = parent.parentFile
                    }
                }
            }.onFailure { problems++ }
        }
        problems
    }
