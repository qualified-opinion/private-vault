package com.qualifiedopinion.privatevault.ui.unlocked

import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.qualifiedopinion.privatevault.R
import com.qualifiedopinion.privatevault.importer.ImportResult
import com.qualifiedopinion.privatevault.importer.ImportState
import com.qualifiedopinion.privatevault.importer.StopReason
import com.qualifiedopinion.privatevault.ui.common.MessagePanel

/** Placeholder for the grid (PR5): the item count, import (PR4) and a Lock button. */
@Composable
fun UnlockedScreen(
    itemCount: Int,
    readOnly: Boolean,
    importState: ImportState,
    onImport: () -> Unit,
    onImportDismissed: () -> Unit,
    onLock: () -> Unit,
) {
    val count = pluralStringResource(R.plurals.unlocked_items, itemCount, itemCount)
    MessagePanel(
        title = stringResource(R.string.unlocked_title),
        body = if (readOnly) count + "\n\n" + stringResource(R.string.unlocked_read_only) else count,
    ) {
        when (importState) {
            ImportState.Idle -> if (!readOnly) {
                Button(onClick = onImport) { Text(stringResource(R.string.import_button)) }
            }
            is ImportState.Running -> {
                val p = importState.progress
                Text(stringResource(R.string.import_running, minOf(p.done + 1, p.total), p.total))
                LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total })
            }
            is ImportState.Finished -> {
                Text(importSummary(importState.result))
                Button(onClick = onImportDismissed) { Text(stringResource(R.string.import_dismiss)) }
            }
        }
        OutlinedButton(onClick = onLock) { Text(stringResource(R.string.lock)) }
    }
}

@Composable
private fun importSummary(r: ImportResult): String {
    val lines = mutableListOf(pluralStringResource(R.plurals.import_done_imported, r.imported, r.imported))
    if (r.unsupported > 0) {
        lines += pluralStringResource(R.plurals.import_done_unsupported, r.unsupported, r.unsupported)
    }
    if (r.noSpace > 0) lines += pluralStringResource(R.plurals.import_done_no_space, r.noSpace, r.noSpace)
    if (r.failed > 0) lines += pluralStringResource(R.plurals.import_done_failed, r.failed, r.failed)
    when (r.stoppedBy) {
        StopReason.LOCKED -> lines += stringResource(R.string.import_stopped_locked)
        StopReason.UNAVAILABLE -> lines += stringResource(R.string.import_stopped_unavailable)
        StopReason.READ_ONLY -> lines += stringResource(R.string.import_stopped_read_only)
        null -> Unit
    }
    if (r.imported > 0) lines += stringResource(R.string.import_originals_note)
    return lines.joinToString("\n\n")
}
