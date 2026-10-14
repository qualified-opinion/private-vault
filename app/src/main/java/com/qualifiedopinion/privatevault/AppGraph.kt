package com.qualifiedopinion.privatevault

import android.content.Context
import com.qualifiedopinion.privatevault.crypto.Argon2Kdf
import com.qualifiedopinion.privatevault.crypto.KeystoreKeyWrapper
import com.qualifiedopinion.privatevault.vault.ItemRepository
import com.qualifiedopinion.privatevault.vault.LocationRecord
import com.qualifiedopinion.privatevault.vault.SafIo
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
import kotlinx.coroutines.launch
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

    private var boundFiles: Pair<LocationRecord, VaultFiles>? = null

    init {
        monitor.start()
        scope.launch {
            monitor.state.collect { state ->
                if (state is VaultAvailability.Unavailable) session.lock()
            }
        }
        scope.launch(Dispatchers.IO) { startupCheck() }
    }

    /** The vault files for the bound location, over a monitored [com.qualifiedopinion.privatevault.vault.SafVaultStorage]. */
    @Synchronized
    fun files(): VaultFiles? {
        val record = location.load() ?: return null
        boundFiles?.let { (r, f) -> if (r == record) return f }
        return VaultFiles(monitor.storageFor(record)).also { boundFiles = record to it }
    }

    fun repository(files: VaultFiles) = ItemRepository(files, session, index)

    /** Startup (§3.1, §5.4): check the folder, then delete stale `.part` files in `staging/`. */
    private fun startupCheck() {
        val state = monitor.fullCheck()
        if (state is VaultAvailability.Available && !state.readOnly) {
            try {
                files()?.cleanStaging()
            } catch (@Suppress("SwallowedException") e: IOException) {
                // The monitor has already been told; the UI shows the error screen.
            }
        }
    }
}
