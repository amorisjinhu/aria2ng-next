package com.slipstream

import com.slipstream.model.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HistoryStore contract: merge folds stopped entries, evict removes them for
 * good (tombstones block stale-snapshot resurrection), and the on-disk file
 * survives a reload. All ops run on the store's single thread in submission
 * order, so awaiting the flow state is a reliable barrier.
 */
class HistoryStoreTest {
    private fun entry(
        gid: String,
        status: Download.Status = Download.Status.COMPLETE,
    ) = Download(
        gid = gid,
        status = status,
        totalLength = 10,
        completedLength = 10,
        downloadSpeed = 0,
        uploadSpeed = 0,
        connections = 0,
        dir = "/d",
        name = "n-$gid",
        errorCode = null,
        errorMessage = null,
    )

    private fun withStore(block: suspend (HistoryStore, File) -> Unit) {
        val dir = createTempDirectory("hist").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = HistoryStore(dir, scope)
            runBlocking { withTimeout(5_000) { block(store, dir) } }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun mergeFoldsStoppedOnlyAndMarksPersisted() =
        withStore { store, _ ->
            store.merge(listOf(entry("a"), entry("b", Download.Status.ACTIVE), entry("c", Download.Status.ERROR)))
            val h = store.history.first { it.size == 2 }
            assertEquals(setOf("a", "c"), h.map { it.gid }.toSet())
            assertTrue(h.all { it.persisted })
        }

    @Test
    fun evictBlocksStaleSnapshotResurrection() =
        withStore { store, _ ->
            val snapshot = listOf(entry("a"))
            store.merge(snapshot)
            store.history.first { it.size == 1 }
            store.evict("a")
            store.history.first { it.isEmpty() }
            // A snapshot captured BEFORE the evict arrives AFTER it — the
            // tombstone must keep the deleted record dead.
            store.merge(snapshot)
            delay(200)
            assertTrue(store.history.value.isEmpty())
        }

    @Test
    fun recordsSurviveReload() =
        withStore { store, dir ->
            store.merge(listOf(entry("keep")))
            store.history.first { it.size == 1 }
            // Give the persist op (queued after the flow update) time to land.
            withTimeout(5_000) {
                while (!File(dir, "history.json").isFile) delay(20)
            }
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val reloaded = HistoryStore(dir, scope2)
                val h = reloaded.history.first { it.isNotEmpty() }
                assertEquals("keep", h.single().gid)
                assertTrue(h.single().persisted)
            } finally {
                scope2.cancel()
            }
        }

    @Test
    fun corruptFileLoadsWithoutCrash() =
        withStore { _, dir ->
            File(dir, "history.json").writeText("""[{"gid": "truncated""")
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val reloaded = HistoryStore(dir, scope2)
                delay(200)
                assertTrue(reloaded.history.value.isEmpty())
            } finally {
                scope2.cancel()
            }
        }

    @Test
    fun mergeIsCapped() =
        withStore { store, _ ->
            store.merge((1..350).map { entry("g$it") })
            val h = store.history.first { it.size == 300 }
            assertEquals(300, h.size)
            // Input order wins the cap: the first 300 entries are kept.
            assertEquals("g1", h.first().gid)
        }
}
