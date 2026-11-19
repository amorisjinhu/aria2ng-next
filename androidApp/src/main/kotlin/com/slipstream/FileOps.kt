package com.slipstream

import android.content.Intent
import android.widget.Toast
import com.slipstream.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 */
internal suspend fun deleteDownloadFiles(d: Download) =
    withContext(Dispatchers.IO) {
        val paths =
            d.files.map { it.path }.filter { it.isNotEmpty() }.ifEmpty {
                listOf(File(d.dir, d.name).path)
            }
        paths.forEach { p ->
            runCatching {
                val f = File(p)
                f.delete()
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
            }
        }
    }
