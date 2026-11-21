package com.slipstream

import com.slipstream.model.Download

/**
 * Task-list partitions (RayBurst sidebar scopes): everything, live work,
 * failures, finished. REMOVED/UNKNOWN appear only under ALL.
 */
enum class TaskScope { ALL, PROGRESS, FAILED, COMPLETED }

private val PROGRESS_STATUSES =
    setOf(Download.Status.ACTIVE, Download.Status.WAITING, Download.Status.PAUSED)

fun TaskScope.matches(d: Download): Boolean =
    when (this) {
        TaskScope.ALL -> true
        TaskScope.PROGRESS -> d.status in PROGRESS_STATUSES
        TaskScope.FAILED -> d.status == Download.Status.ERROR
        TaskScope.COMPLETED -> d.status == Download.Status.COMPLETE
    }

fun scopeCounts(list: List<Download>): Map<TaskScope, Int> = TaskScope.entries.associateWith { s -> list.count { s.matches(it) } }

/** Selection must always be a subset of the visible scope. */
fun pruneSelection(
    sel: Set<String>,
    visible: Set<String>,
): Set<String> = sel.intersect(visible)

fun visibleGids(list: List<Download>): Set<String> = list.map { it.gid }.toSet()
