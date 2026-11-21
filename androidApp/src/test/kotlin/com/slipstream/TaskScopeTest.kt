package com.slipstream

import com.slipstream.model.Download
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskScopeTest {
    private fun dl(
        status: Download.Status,
        persisted: Boolean = false,
    ) = Download(
        gid = "g",
        status = status,
        totalLength = 0,
        completedLength = 0,
        downloadSpeed = 0,
        uploadSpeed = 0,
        connections = 0,
        dir = "",
        name = "x",
        errorCode = null,
        errorMessage = null,
        persisted = persisted,
    )

    @Test
    fun membership() {
        Download.Status.entries.forEach { st ->
            val d = dl(st)
            when (st) {
                Download.Status.ACTIVE, Download.Status.WAITING, Download.Status.PAUSED -> {
                    assertTrue(TaskScope.PROGRESS.matches(d), "$st in PROGRESS")
                    assertFalse(TaskScope.FAILED.matches(d))
                    assertFalse(TaskScope.COMPLETED.matches(d))
                }

                Download.Status.ERROR -> {
                    assertFalse(TaskScope.PROGRESS.matches(d))
                    assertTrue(TaskScope.FAILED.matches(d))
                    assertFalse(TaskScope.COMPLETED.matches(d))
                }

                Download.Status.COMPLETE -> {
                    assertFalse(TaskScope.PROGRESS.matches(d))
                    assertFalse(TaskScope.FAILED.matches(d))
                    assertTrue(TaskScope.COMPLETED.matches(d))
                }

                // REMOVED / UNKNOWN live only under ALL (RayBurst filter parity)
                else -> {
                    assertFalse(TaskScope.PROGRESS.matches(d), "$st not in PROGRESS")
                    assertFalse(TaskScope.FAILED.matches(d))
                    assertFalse(TaskScope.COMPLETED.matches(d))
                }
            }
            assertTrue(TaskScope.ALL.matches(d), "$st in ALL")
        }
    }

    @Test
    fun persistedRecordsParticipateByStatus() {
        assertTrue(TaskScope.COMPLETED.matches(dl(Download.Status.COMPLETE, persisted = true)))
        assertTrue(TaskScope.FAILED.matches(dl(Download.Status.ERROR, persisted = true)))
        assertFalse(TaskScope.PROGRESS.matches(dl(Download.Status.COMPLETE, persisted = true)))
    }

    @Test
    fun counts() {
        val list =
            listOf(
                dl(Download.Status.ACTIVE),
                dl(Download.Status.WAITING),
                dl(Download.Status.PAUSED),
                dl(Download.Status.ERROR),
                dl(Download.Status.COMPLETE),
                dl(Download.Status.COMPLETE, persisted = true),
                dl(Download.Status.REMOVED),
                dl(Download.Status.UNKNOWN),
            )
        val counts = scopeCounts(list)
        assertEquals(8, counts[TaskScope.ALL])
        assertEquals(3, counts[TaskScope.PROGRESS])
        assertEquals(1, counts[TaskScope.FAILED])
        assertEquals(2, counts[TaskScope.COMPLETED])
    }

    @Test
    fun selectionPrunesToTheVisibleScope() {
        // A selected task migrating out of the partition (ACTIVE → COMPLETE)
        // leaves the selection; visible gids are retained.
        assertEquals(setOf("a"), pruneSelection(setOf("a", "b"), setOf("a")))
        // Fully visible selection → identity.
        val sel = setOf("a", "b")
        assertEquals(sel, pruneSelection(sel, setOf("a", "b", "c")))
        // Empty snapshot (engine stopped) → selection mode exits.
        assertEquals(emptySet(), pruneSelection(sel, emptySet()))
        assertEquals(setOf("g"), visibleGids(listOf(dl(Download.Status.ACTIVE))))
    }
}
