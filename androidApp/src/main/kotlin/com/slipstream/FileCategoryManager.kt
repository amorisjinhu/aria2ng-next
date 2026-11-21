package com.slipstream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slipstream.model.DirectoryMode
import com.slipstream.model.FileCategories
import com.slipstream.model.FileCategory
import com.slipstream.model.PatternMode
import kotlinx.coroutines.delay

/**
 * File-category rule editor — a RayBurst FileCategoryManager port. Edits a
 * caller-owned draft; nothing is persisted here (the settings page's global
 * Save writes the table, matching the form→store two-stage flow).
 */
@Composable
internal fun FileCategoryManager(
    initial: List<FileCategory>,
    baseDir: String,
    onSave: (List<FileCategory>) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = remember { mutableStateListOf<FileCategory>().apply { addAll(initial) } }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var resetArmed by remember { mutableStateOf(false) }
    var resetTick by remember { mutableIntStateOf(0) }

    // Two-click reset: the second tap must land inside a short window,
    // like RayBurst's confirm-in-place button.
    LaunchedEffect(resetTick) {
        if (resetTick > 0) {
            delay(2000)
            resetArmed = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cat_manage)) },
        text = {
            val idx = editIndex
            if (idx != null && idx < draft.size) {
                CategoryEditForm(
                    index = idx,
                    category = draft[idx],
                    baseDir = baseDir,
                    onBack = { editIndex = null },
                    onApply = {
                        draft[idx] = it
                        editIndex = null
                        error = null
                    },
                    onDelete =
                        if (draft[idx].builtIn) {
                            null
                        } else {
                            {
                                draft.removeAt(idx)
                                editIndex = null
                                error = null
                            }
                        },
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        itemsIndexed(draft) { i, c ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { editIndex = i }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(categoryLabel(c), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(
                                            R.string.cat_summary,
                                            c.extensions.size,
                                            c.urlPatterns.size,
                                        ) + " · " + c.directory,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    error?.let { Text(categoryErrorText(it), color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                editIndex = draft.size
                                draft.add(newCategory())
                            },
                            enabled = draft.size < FileCategories.MAX_CATEGORIES,
                        ) { Text(stringResource(R.string.cat_add)) }
                        TextButton(onClick = {
                            if (resetArmed) {
                                draft.clear()
                                draft.addAll(FileCategories.BUILTIN)
                                resetArmed = false
                                error = null
                            } else {
                                resetArmed = true
                                resetTick++
                            }
                        }) {
                            Text(
                                stringResource(
                                    if (resetArmed) R.string.cat_reset_confirm else R.string.cat_reset,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editIndex == null) {
                TextButton(onClick = {
                    val e = FileCategories.validate(draft.toList(), baseDir)
                    if (e != null) {
                        error = e
                    } else {
                        onSave(draft.map(FileCategories::normalize))
                    }
                }) { Text(stringResource(R.string.save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun newCategory() = FileCategory(label = "", extensions = emptyList(), directory = "", directoryMode = DirectoryMode.RELATIVE)

@Composable
private fun categoryLabel(c: FileCategory): String =
    if (!c.builtIn) {
        c.label
    } else {
        when (c.label) {
            "videos" -> stringResource(R.string.cat_videos)
            "music" -> stringResource(R.string.cat_music)
            "images" -> stringResource(R.string.cat_images)
            "documents" -> stringResource(R.string.cat_documents)
            "archives" -> stringResource(R.string.cat_archives)
            "programs" -> stringResource(R.string.cat_programs)
            else -> c.label
        }
    }

/** Maps FileCategories.validate() codes to localized text. */
@Composable
internal fun categoryErrorText(
    code: String,
    catIndex: Int? = null,
): String {
    val p = code.split(':')

    fun num(i: Int) = p.getOrNull(i)?.toIntOrNull() ?: 0
    return when (p.firstOrNull()) {
        "base" -> stringResource(R.string.cat_err_basedir)
        "dir" -> stringResource(R.string.cat_err_dir, catIndex ?: num(1))
        "toolong" -> stringResource(R.string.cat_err_toolong, catIndex ?: num(1), num(2))
        "regex" -> stringResource(R.string.cat_err_regex, catIndex ?: num(1), num(2))
        else -> code
    }
}

/** Single-category edit form inside the same dialog (list ⇄ edit states). */
@Composable
private fun CategoryEditForm(
    index: Int,
    category: FileCategory,
    baseDir: String,
    onBack: () -> Unit,
    onApply: (FileCategory) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var label by remember { mutableStateOf(category.label) }
    var extText by remember { mutableStateOf(category.extensions.joinToString(", ")) }
    var dirText by remember { mutableStateOf(category.directory) }
    var dirMode by remember { mutableStateOf(category.directoryMode) }
    var patText by remember { mutableStateOf(category.urlPatterns.joinToString("\n")) }
    var patMode by remember { mutableStateOf(category.urlPatternMode) }
    var formError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(40) },
            label = { Text(stringResource(R.string.cat_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = extText,
            onValueChange = { extText = it },
            label = { Text(stringResource(R.string.cat_ext_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = dirText,
            onValueChange = { dirText = it },
            label = { Text(stringResource(R.string.cat_dir_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = dirMode == DirectoryMode.RELATIVE,
                onClick = { dirMode = DirectoryMode.RELATIVE },
                label = { Text(stringResource(R.string.cat_dir_mode_rel)) },
            )
            FilterChip(
                selected = dirMode == DirectoryMode.ABSOLUTE,
                onClick = { dirMode = DirectoryMode.ABSOLUTE },
                label = { Text(stringResource(R.string.cat_dir_mode_abs)) },
            )
        }
        OutlinedTextField(
            value = patText,
            onValueChange = { patText = it },
            label = { Text(stringResource(R.string.cat_url_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = patMode == PatternMode.WILDCARD,
                onClick = { patMode = PatternMode.WILDCARD },
                label = { Text(stringResource(R.string.cat_mode_wildcard)) },
            )
            FilterChip(
                selected = patMode == PatternMode.REGEX,
                onClick = { patMode = PatternMode.REGEX },
                label = { Text(stringResource(R.string.cat_mode_regex)) },
            )
        }
        formError?.let { Text(categoryErrorText(it, index + 1), color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The only other non-OK exits are dialog Cancel (drops the whole
            // table draft) and — when validate fails — nothing at all.
            TextButton(onClick = onBack) { Text(stringResource(R.string.cat_back)) }
            TextButton(onClick = {
                val applied =
                    category.copy(
                        label = label.trim().ifEmpty { category.label },
                        extensions = extText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        directory = dirText.trim(),
                        directoryMode = dirMode,
                        urlPatterns = patText.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        urlPatternMode = patMode,
                        // Renaming a built-in turns it into a custom rule.
                        builtIn = category.builtIn && label.trim() == category.label,
                    )
                val e = FileCategories.validate(listOf(applied), baseDir)
                if (e != null) {
                    formError = e
                } else {
                    onApply(applied)
                }
            }) { Text(stringResource(R.string.ok)) }
            onDelete?.let {
                TextButton(onClick = it) {
                    Text(stringResource(R.string.cat_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
