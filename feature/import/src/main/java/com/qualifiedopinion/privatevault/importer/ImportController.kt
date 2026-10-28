package com.qualifiedopinion.privatevault.importer

import com.qualifiedopinion.privatevault.vault.ItemRepository
import com.qualifiedopinion.privatevault.vault.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the import part of the unlocked screen shows. */
sealed interface ImportState {
    data object Idle : ImportState

    data class Running(val progress: ImportProgress) : ImportState

    data class Finished(val result: ImportResult) : ImportState
}

/** Where imported items go: the bound vault's repository and its volume's free space. */
class ImportTarget(val repository: ItemRepository, val freeBytes: () -> Long?)

/**
 * Runs one import at a time in the app's process scope, so it survives screen rotation, and
 * publishes [state] for the UI.
 *
 * - It holds [writeLock] for the whole run; startup cleanup takes the same lock, because an item's
 *   `.vdat` exists before its `.vmeta` while it is being created.
 * - Locking the vault cancels the run: the item in progress stops at its next read and its partial
 *   files are removed.
 */
class ImportController(
    private val scope: CoroutineScope,
    private val session: VaultSession,
    private val writeLock: Mutex,
    private val target: () -> ImportTarget?,
) {
    private val mutableState = MutableStateFlow<ImportState>(ImportState.Idle)
    @Volatile private var job: Job? = null

    val state: StateFlow<ImportState> = mutableState.asStateFlow()

    init {
        session.addLockListener { job?.cancel() }
    }

    /** Starts importing [sources]. Returns false if the list is empty or an import is already running. */
    @Synchronized
    fun start(sources: List<ImportSource>): Boolean {
        if (sources.isEmpty() || job?.isActive == true) return false
        mutableState.value = ImportState.Running(ImportProgress(done = 0, total = sources.size, imported = 0))
        job = scope.launch(Dispatchers.IO) { run(sources) }
        return true
    }

    /** Hides the summary of a finished import. */
    fun dismiss() {
        if (mutableState.value is ImportState.Finished) mutableState.value = ImportState.Idle
    }

    private suspend fun run(sources: List<ImportSource>) {
        val context = currentCoroutineContext()
        val result = try {
            writeLock.withLock {
                val t = target()
                if (t == null) {
                    ImportResult(total = sources.size, stoppedBy = StopReason.UNAVAILABLE)
                } else {
                    val importer = Importer(t.repository, t.freeBytes) { context.isActive && session.isUnlocked.value }
                    importer.importAll(sources) { mutableState.value = ImportState.Running(it) }
                }
            }
        } catch (e: CancellationException) {
            finish(ImportResult(total = sources.size, stoppedBy = StopReason.LOCKED))
            throw e
        }
        finish(result)
    }

    private fun finish(result: ImportResult) {
        // An item that finished just as the vault locked may have been added to the index after
        // lock() cleared it; locking again (idempotent) clears it.
        if (!session.isUnlocked.value) session.lock()
        mutableState.value = ImportState.Finished(result)
    }
}
