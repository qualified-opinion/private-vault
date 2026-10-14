package com.qualifiedopinion.privatevault.vault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract.Document
import com.qualifiedopinion.privatevault.format.VaultFormatException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Watches the bound vault folder (planning pack §5.4) and publishes [state].
 *
 * Signals:
 * - a dynamic receiver for `MEDIA_EJECT`, `MEDIA_UNMOUNTED`, `MEDIA_REMOVED`, `MEDIA_BAD_REMOVAL`
 *   (immediately unavailable) and `MEDIA_MOUNTED` (triggers a full check), scheme `file`;
 * - [quickCheck]: grant + volume state, run before provider calls (at most every [QUICK_TTL_MS]) and
 *   by [pollWhileActive] every second while the UI is in the foreground;
 * - [fullCheck]: quick + `vault.meta` exists with the bound vault id + read-only flags. Run at start,
 *   on resume and on Retry;
 * - [reportProviderFailure]: any provider exception from [SafVaultStorage] means "vault gone".
 *
 * The app locks the session whenever [state] becomes [VaultAvailability.Unavailable].
 * Registering adds no permission: the receiver only gets system broadcasts.
 */
@Suppress("TooManyFunctions") // the monitor's public checks plus receiver plumbing
class VolumeMonitor(
    private val context: Context,
    private val location: VaultLocation,
    private val io: SafIo,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<VaultAvailability>(VaultAvailability.Unknown)
    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var lastQuickAt = 0L

    val state: StateFlow<VaultAvailability> = mutableState.asStateFlow()

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onMediaBroadcast(intent)
        }
        val filter = IntentFilter().apply {
            MEDIA_GONE_ACTIONS.forEach(::addAction)
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcasts still reach a non-exported receiver.
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(r, filter)
        }
        receiver = r
    }

    fun stop() {
        receiver?.let(context::unregisterReceiver)
        receiver = null
    }

    /** Grant + volume state. Can only make [state] worse; see [AvailabilityRules.mergeQuick]. */
    fun quickCheck(): VaultAvailability {
        lastQuickAt = SystemClock.elapsedRealtime()
        val record = location.load() ?: return setState(VaultAvailability.NotConfigured)
        val quick = quickFor(record)
        mutableState.update { AvailabilityRules.mergeQuick(it, quick) }
        return mutableState.value
    }

    /** Throws [VaultUnavailableException] unless the vault is available. Cheap: re-checks at most every 500 ms. */
    fun ensureAvailable() {
        val now = SystemClock.elapsedRealtime()
        val current = if (now - lastQuickAt > QUICK_TTL_MS) quickCheck() else mutableState.value
        if (current !is VaultAvailability.Available) throw VaultUnavailableException("vault folder not available")
    }

    /** The full check (§5.4). Blocking I/O: call off the main thread. */
    fun fullCheck(): VaultAvailability {
        val record = location.load()
        val quick = record?.let(::quickFor) ?: VaultAvailability.NotConfigured
        lastQuickAt = SystemClock.elapsedRealtime()
        if (record == null || quick !is VaultAvailability.Available) return setState(quick)
        return setState(checkFolder(record, quick))
    }

    private fun quickFor(record: LocationRecord): VaultAvailability =
        AvailabilityRules.quick(location.grantFor(Uri.parse(record.treeUri)), location.volumeState(record.volume))

    private fun checkFolder(record: LocationRecord, quick: VaultAvailability.Available): VaultAvailability {
        val tree = Uri.parse(record.treeUri)
        return try {
            // A private storage: no pre-check and no failure reporting, this call decides the state itself.
            val storage = SafVaultStorage(tree, io, readOnly = { true })
            VaultFiles(storage).requireVaultId(ItemRepository.idFromHex(record.vaultId))
            val canCreate = storage.rootFlags() and Document.FLAG_DIR_SUPPORTS_CREATE != 0
            VaultAvailability.Available(readOnly = quick.readOnly || !canCreate)
        } catch (@Suppress("SwallowedException") e: VaultNotFoundException) {
            VaultAvailability.Unavailable(UnavailableReason.VAULT_MISSING)
        } catch (@Suppress("SwallowedException") e: VaultIdMismatchException) {
            VaultAvailability.Unavailable(UnavailableReason.WRONG_VAULT)
        } catch (@Suppress("SwallowedException") e: VaultFormatException) {
            VaultAvailability.Unavailable(UnavailableReason.VAULT_DAMAGED)
        } catch (@Suppress("SwallowedException") e: IOException) {
            VaultAvailability.Unavailable(UnavailableReason.PROVIDER_ERROR)
        }
    }

    /** A provider call failed: the vault is gone until a full check says otherwise (§5.4). */
    fun reportProviderFailure() {
        setState(VaultAvailability.Unavailable(UnavailableReason.PROVIDER_ERROR))
    }

    /** Polls [quickCheck] every second until the calling coroutine is cancelled (e.g. the UI stops). */
    suspend fun pollWhileActive() {
        while (currentCoroutineContext().isActive) {
            if (mutableState.value is VaultAvailability.Available) quickCheck()
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * A [SafVaultStorage] for the saved location, wired to this monitor: pre-checks before every
     * provider call, reports failures, and is read-only unless the last check said writable.
     */
    fun storageFor(record: LocationRecord): SafVaultStorage = SafVaultStorage(
        treeUri = Uri.parse(record.treeUri),
        io = io,
        readOnly = { (mutableState.value as? VaultAvailability.Available)?.readOnly ?: true },
        preCheck = ::ensureAvailable,
        onProviderFailure = { reportProviderFailure() },
    )

    private fun onMediaBroadcast(intent: Intent) {
        val record = location.load() ?: return
        if (!AvailabilityRules.broadcastConcerns(record.volume, intent.data?.path)) return
        if (intent.action in MEDIA_GONE_ACTIONS) {
            // On the main thread, right away: the app locks as soon as it sees this (§6 PR3: within 1 s).
            setState(VaultAvailability.Unavailable(UnavailableReason.VOLUME_MISSING))
        } else {
            scope.launch(Dispatchers.IO) { fullCheck() }
        }
    }

    private fun setState(s: VaultAvailability): VaultAvailability {
        mutableState.value = s
        return s
    }

    companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val QUICK_TTL_MS = 500L

        val MEDIA_GONE_ACTIONS = listOf(
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
        )
    }
}
