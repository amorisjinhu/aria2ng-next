package com.slipstream.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class FileEntry(
    val index: Int,
    val path: String,
    val length: Long,
    val completedLength: Long,
    val selected: Boolean,
    val uris: List<String> = emptyList(),
) {
    val progress: Float
        get() = if (length > 0) (completedLength.toFloat() / length).coerceIn(0f, 1f) else 0f
}

@Serializable
data class Download(
    val gid: String,
    val status: Status,
    val totalLength: Long,
    val completedLength: Long,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val connections: Int,
    val dir: String,
    val name: String,
    val errorCode: String?,
    val errorMessage: String?,
    val files: List<FileEntry> = emptyList(),
    // True when this entry comes from the on-disk history store, not a live
    // aria2 — the gid no longer exists engine-side, so only file/record ops work.
    val persisted: Boolean = false,
) {
    val progress: Float
        get() = if (totalLength > 0) (completedLength.toFloat() / totalLength).coerceIn(0f, 1f) else 0f

    /** -1 when unknown (no speed or already complete). */
    val etaSeconds: Long
        get() =
            if (downloadSpeed > 0 && totalLength > completedLength) {
                (totalLength - completedLength) / downloadSpeed
            } else {
                -1L
            }

    @Serializable
    enum class Status {
        ACTIVE,
        WAITING,
        PAUSED,
        ERROR,
        COMPLETE,
        REMOVED,
        UNKNOWN,
        ;

        companion object {
            fun of(s: String?): Status =
                when (s) {
                    "active" -> ACTIVE
                    "waiting" -> WAITING
                    "paused" -> PAUSED
                    "error" -> ERROR
                    "complete" -> COMPLETE
                    "removed" -> REMOVED
                    else -> UNKNOWN
                }
        }
    }

    companion object {
        private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

        private fun JsonObject.lng(key: String): Long = str(key)?.toLongOrNull() ?: 0L

        /** Magnet URIs have no path — the display name lives in the dn= parameter. */
        private fun nameFromUri(uri: String): String? =
            if (uri.startsWith("magnet:")) {
                uri.substringAfter("dn=", "").substringBefore('&').takeIf { it.isNotEmpty() }
            } else {
                uri.substringAfterLast('/').substringBefore('?').takeIf { it.isNotEmpty() }
            }

        fun fromJson(o: JsonObject): Download {
            val btName =
                o["bittorrent"]
                    ?.jsonObject
                    ?.get("info")
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content

            val firstFile = (o["files"] as? JsonArray)?.firstOrNull()?.jsonObject

            val fileName =
                firstFile
                    ?.get("path")
                    ?.jsonPrimitive
                    ?.content
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotEmpty() }
                    // No local path yet (failed/zero-byte): fall back to the request URI
                    ?: (firstFile?.get("uris") as? JsonArray)
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("uri")
                        ?.jsonPrimitive
                        ?.content
                        ?.let(::nameFromUri)

            val gid = o["gid"]!!.jsonPrimitive.content

            return Download(
                gid = gid,
                status = Status.of(o.str("status")),
                totalLength = o.lng("totalLength"),
                completedLength = o.lng("completedLength"),
                downloadSpeed = o.lng("downloadSpeed"),
                uploadSpeed = o.lng("uploadSpeed"),
                connections = o.lng("connections").toInt(),
                dir = o.str("dir") ?: "",
                name = btName ?: fileName ?: gid,
                errorCode = o.str("errorCode"),
                errorMessage = o.str("errorMessage"),
                files =
                    (o["files"] as? JsonArray)?.mapNotNull { f ->
                        (f as? JsonObject)?.let { entry ->
                            FileEntry(
                                index = entry["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                path = entry["path"]?.jsonPrimitive?.content ?: "",
                                length = entry["length"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                                completedLength = entry["completedLength"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                                selected = entry["selected"]?.jsonPrimitive?.content == "true",
                                uris =
                                    (entry["uris"] as? JsonArray)?.mapNotNull {
                                        (it as? JsonObject)?.get("uri")?.jsonPrimitive?.content
                                    } ?: emptyList(),
                            )
                        }
                    } ?: emptyList(),
            )
        }
    }
}

data class GlobalStat(
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val numActive: Int,
    val numWaiting: Int,
    val numStopped: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): GlobalStat =
            GlobalStat(
                downloadSpeed = o["downloadSpeed"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                uploadSpeed = o["uploadSpeed"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                numActive = o["numActive"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                numWaiting = o["numWaiting"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                numStopped = o["numStopped"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
    }
}
