package com.qualifiedopinion.privatevault

import android.content.Context
import android.net.Uri
import com.qualifiedopinion.privatevault.crypto.Argon2Kdf
import com.qualifiedopinion.privatevault.crypto.KeystoreKeyWrapper
import com.qualifiedopinion.privatevault.importer.ImportController
import com.qualifiedopinion.privatevault.importer.ImportSource
import com.qualifiedopinion.privatevault.importer.ImportTarget
import com.qualifiedopinion.privatevault.importer.UriImportSource
import com.qualifiedopinion.privatevault.vault.ItemRepository
import com.qualifiedopinion.privatevault.vault.LocationRecord
import com.qualifiedopinion.privatevault.vault.SafIo
import com.qualifiedopinion.privatevault.vault.SafVaultStorage
import com.qualifiedopinion.privatevault.vault.VaultAvailability
import com.qualifiedopinion.privatevault.vault.VaultFiles
import com.qualifiedopinion.privatevault.vault.VaultIndex
import com.qualifiedopinion.privatevault.vault.VaultKeys
import com.qualifiedopinion.privatevault.vault.VaultLocation
import com.qualifiedopinion.privatevault.vault.VaultSession
import com.qualifiedopinion.privatevault.vault.VolumeMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Manual DI (planning pack §2): one instance per process, built by [PrivateVaultApp].
 *
 * Lock orchestration lives here, not in the UI: whenever the [VolumeMonitor] says the folder is
 * unavailable (eject, removal, lost grant, provider error), the session is locked at once,
 * whatever screen is showing (§5.4).
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    /** Process-lifetime scope for the monitor and startup work. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val safIo = SafIo(appContext.contentResolver)
    val location = VaultLocation(appContext)
    val monitor = VolumeMonitor(appContext, location, safIo, scope)
    val index = VaultIndex()
    val session = VaultSession(index)
    val kdf = Argon2Kdf()
    val keys = VaultKeys(kdf, KeystoreKeyWrapper::forVault)

    /** Held by anything that writes items: import and startup cleanup must never overlap (§6 PR4). */
    val writeLock = Mutex()

    val importer = ImportController(scope, session, writeLock) {
        files()?.let { f -> ImportTarget(repository(f), f.storage::freeBytes) }
    }

    private var boundFiles: Pair<LocationRecord, VaultFiles>? = null

    init {
        monitor.start()
        scope.launch {
            combine(monitor.state, session.isUnlocked) { state, unlocked -> state to unlocked }
                .collect { (state, unlocked) ->
                    // A slow Argon2 unwrap may finish after the eject notification was handled.
                    if (state is VaultAvailability.Unavailable && unlocked) session.lock()
                }
        }
        scope.launch(Dispatchers.IO) { startupCheck() }
    }

    /** The vault files for the bound location, over a monitored [SafVaultStorage]. */
    @Synchronized
    @Suppress("ReturnCount") // guard clauses
    fun files(): VaultFiles? {
        val record = location.load() ?: return null
        boundFiles?.let { (r, f) -> if (r == record) return f }
        return VaultFiles(monitor.storageFor(record)).also { boundFiles = record to it }
    }

    fun repository(files: VaultFiles) = ItemRepository(files, session, index)

    fun importSources(uris: List<Uri>): List<ImportSource> = uris.map { UriImportSource(appContext, it) }

    /** Startup (§3.1, §5.4): check the folder, then delete stale `.part` and orphaned item files. */
    private suspend fun startupCheck() {
        val state = monitor.fullCheck()
        if (state is VaultAvailability.Available && !state.readOnly) {
            writeLock.withLock {
                try {
                    files()?.cleanStaging()
                } catch (@Suppress("SwallowedException") e: IOException) {
                    // The monitor has already been told; the UI shows the error screen.
                }
            }
        }
    }
}
