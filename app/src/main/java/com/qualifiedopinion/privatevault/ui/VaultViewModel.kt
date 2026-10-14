package com.qualifiedopinion.privatevault.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qualifiedopinion.privatevault.AppGraph
import com.qualifiedopinion.privatevault.R
import com.qualifiedopinion.privatevault.crypto.KdfUnavailableException
import com.qualifiedopinion.privatevault.crypto.KeyWrapperKeyMissingException
import com.qualifiedopinion.privatevault.crypto.KeystoreKeyWrapper
import com.qualifiedopinion.privatevault.crypto.Pins
import com.qualifiedopinion.privatevault.crypto.WrongPinException
import com.qualifiedopinion.privatevault.format.VaultFormatException
import com.qualifiedopinion.privatevault.format.toHex
import com.qualifiedopinion.privatevault.vault.AvailabilityRules
import com.qualifiedopinion.privatevault.vault.ItemMeta
import com.qualifiedopinion.privatevault.vault.ItemRepository
import com.qualifiedopinion.privatevault.vault.LocationRecord
import com.qualifiedopinion.privatevault.vault.SafVaultStorage
import com.qualifiedopinion.privatevault.vault.TreeVolume
import com.qualifiedopinion.privatevault.vault.UnavailableReason
import com.qualifiedopinion.privatevault.vault.VaultAlreadyExistsException
import com.qualifiedopinion.privatevault.vault.VaultAvailability
import com.qualifiedopinion.privatevault.vault.VaultFiles
import com.qualifiedopinion.privatevault.vault.VaultIdMismatchException
import com.qualifiedopinion.privatevault.vault.VaultNotFoundException
import com.qualifiedopinion.privatevault.vault.VaultReadOnlyException
import com.qualifiedopinion.privatevault.vault.VaultUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest

/** What the single vault screen shows. */
sealed interface Screen {
    data object Loading : Screen

    /** Setup start: pick the vault folder. [error] explains why the last pick was refused. */
    data class Welcome(val hasCard: Boolean, @param:StringRes val error: Int? = null) : Screen

    /** The picked folder is on internal storage: an explicit choice, with a warning (§5.5). */
    data object PrimaryWarning : Screen

    /** The picked folder already holds a vault. [canOpen] is false if this install has no key for it. */
    data class ExistingVault(val canOpen: Boolean) : Screen

    data class SetPin(val confirming: Boolean, @param:StringRes val error: Int? = null) : Screen

    data object ConfirmDataLoss : Screen

    data class Working(@param:StringRes val message: Int) : Screen

    data class Locked(@param:StringRes val error: Int? = null) : Screen

    /** The item count comes from [VaultViewModel.items], which fills in as the index loads. */
    data class Unlocked(val readOnly: Boolean) : Screen

    /** §5.4 error UI. [error] is set when a re-pick was refused. */
    data class Unavailable(val reason: UnavailableReason, @param:StringRes val error: Int? = null) : Screen
}

/**
 * Setup, unlock and the "folder not available" flow (planning pack §5.3, §5.4, §6 PR3).
 *
 * Rules kept here:
 * - a new vault is only ever created in a folder without `vault.meta` (never silently, never over one);
 * - a re-pick must hold the bound vault id;
 * - there is no fallback to internal storage: internal is only a setup choice, after a warning;
 * - PINs are `ByteArray`s; the pending setup PIN is wiped on every exit path.
 */
@Suppress("TooManyFunctions") // one handler per UI event
class VaultViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutableScreen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = mutableScreen.asStateFlow()

    /** The in-memory index (empty while locked). */
    val items: StateFlow<List<ItemMeta>> = graph.index.items

    /** The folder being set up (not yet saved as the vault location). */
    private var pendingTree: Uri? = null
    private var pendingVolume: TreeVolume? = null
    private var pendingVaultId: ByteArray? = null
    private var pendingPin: ByteArray? = null

    init {
        viewModelScope.launch {
            combine(graph.monitor.state, graph.session.isUnlocked) { a, u -> a to u }.collect { (availability, unlocked) ->
                onVaultState(availability, unlocked)
            }
        }
    }

    /** The picker opens on the SD card root when one is mounted (API 29+). */
    fun initialPickerUri(): Uri? = graph.location.removableVolumeInitialUri()

    fun onFolderPicked(tree: Uri?) {
        tree ?: return
        viewModelScope.launch {
            mutableScreen.value = Screen.Working(R.string.working_checking)
            mutableScreen.value = withContext(Dispatchers.IO) { evaluatePickedFolder(tree) }
        }
    }

    fun onPrimaryWarningAccepted() {
        mutableScreen.value = Screen.SetPin(confirming = false)
    }

    fun onPickAnother() {
        abandonPending()
        mutableScreen.value = welcome()
    }

    /** Use the existing vault in the picked folder (only offered when this install holds its key). */
    fun onUseExistingVault() {
        val tree = pendingTree ?: return
        val volume = pendingVolume ?: return
        val id = pendingVaultId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                graph.location.save(LocationRecord(treeUri = tree.toString(), volume = volume.volume, vaultId = id.toHex()))
                clearPending(releaseGrant = false)
                graph.monitor.fullCheck()
            }
        }
    }

    /** PIN pad submit during setup: first entry, then confirmation. The array is owned (and wiped) here. */
    fun onSetupPin(pin: ByteArray) {
        val first = pendingPin
        when {
            !Pins.isValid(pin) -> {
                pin.fill(0)
                mutableScreen.value = Screen.SetPin(confirming = first != null, error = R.string.pin_rules)
            }
            first == null -> {
                pendingPin = pin
                mutableScreen.value = Screen.SetPin(confirming = true)
            }
            MessageDigest.isEqual(first, pin) -> {
                pin.fill(0)
                mutableScreen.value = Screen.ConfirmDataLoss
            }
            else -> {
                pin.fill(0)
                wipePin()
                mutableScreen.value = Screen.SetPin(confirming = false, error = R.string.pin_mismatch)
            }
        }
    }

    /** The user confirmed "forgot PIN and lost phrase = data lost": calibrate Argon2 and create the vault. */
    fun onCreateVault() {
        val tree = pendingTree ?: return
        val volume = pendingVolume ?: return
        val pin = pendingPin ?: return
        pendingPin = null
        viewModelScope.launch {
            mutableScreen.value = Screen.Working(R.string.working_calibrating)
            val result = withContext(Dispatchers.Default) {
                try {
                    val params = graph.kdf.calibrate()
                    mutableScreen.value = Screen.Working(R.string.working_creating)
                    val files = VaultFiles(SafVaultStorage(tree, graph.safIo, readOnly = { false }))
                    val id = graph.keys.create(files, pin, params, graph.session) // wipes pin
                    graph.location.save(LocationRecord(treeUri = tree.toString(), volume = volume.volume, vaultId = id.toHex()))
                    clearPending(releaseGrant = false)
                    graph.monitor.fullCheck()
                    null
                } catch (e: VaultAlreadyExistsException) {
                    setupError(e)
                } catch (e: VaultReadOnlyException) {
                    setupError(e)
                } catch (e: KdfUnavailableException) {
                    setupError(e)
                } catch (e: IOException) {
                    setupError(e)
                } catch (e: GeneralSecurityException) {
                    setupError(e)
                } finally {
                    pin.fill(0)
                }
            }
            if (result != null) mutableScreen.value = result
        }
    }

    /** Lock screen PIN submit. The array is wiped by the unlock call. */
    fun onUnlock(pin: ByteArray) {
        viewModelScope.launch {
            mutableScreen.value = UNLOCKING
            val error = withContext(Dispatchers.Default) { unlock(pin) }
            if (error != null) mutableScreen.value = error
        }
    }

    fun onLock() {
        graph.session.lock()
    }

    /** §5.4 Retry: a full check (grant, volume, vault id). Success lands on the lock screen. */
    fun onRetry() {
        viewModelScope.launch {
            mutableScreen.value = Screen.Working(R.string.working_checking)
            val state = withContext(Dispatchers.IO) { graph.monitor.fullCheck() }
            if (state is VaultAvailability.Unavailable) mutableScreen.value = Screen.Unavailable(state.reason)
        }
    }

    /** §5.4 Re-pick: only a folder whose `vault.meta` has the bound vault id is accepted. */
    fun onRepicked(tree: Uri?) {
        tree ?: return
        viewModelScope.launch {
            mutableScreen.value = Screen.Working(R.string.working_checking)
            val refused = withContext(Dispatchers.IO) { repick(tree) }
            if (refused != null) mutableScreen.value = refused
        }
    }

    override fun onCleared() {
        wipePin()
    }

    /**
     * Follows the monitor and the session. Setup screens are left alone while no location is saved;
     * once one is, an unavailable folder always wins (the session is already locked by [AppGraph]).
     */
    private fun onVaultState(availability: VaultAvailability, unlocked: Boolean) {
        val current = mutableScreen.value
        mutableScreen.value = when (availability) {
            VaultAvailability.Unknown -> Screen.Loading
            VaultAvailability.NotConfigured -> if (isSetupScreen(current)) current else welcome()
            is VaultAvailability.Unavailable -> Screen.Unavailable(availability.reason)
            is VaultAvailability.Available -> when {
                unlocked -> Screen.Unlocked(availability.readOnly)
                current == UNLOCKING || current is Screen.Locked -> current
                else -> Screen.Locked()
            }
        }
    }

    private fun isSetupScreen(s: Screen) = s is Screen.Welcome || s is Screen.PrimaryWarning ||
        s is Screen.ExistingVault || s is Screen.SetPin || s is Screen.ConfirmDataLoss ||
        (s is Screen.Working && s != UNLOCKING)

    /** Runs on IO. Decides what the setup flow does with a freshly picked folder. */
    private fun evaluatePickedFolder(tree: Uri): Screen {
        abandonPending()
        try {
            graph.location.takeGrant(tree)
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            return welcome(R.string.error_folder_grant)
        }
        val volume = graph.location.volumeOf(tree)
        if (volume == null) {
            graph.location.releaseGrant(tree)
            return welcome(R.string.error_folder_provider)
        }
        pendingTree = tree
        pendingVolume = volume
        val files = VaultFiles(SafVaultStorage(tree, graph.safIo, readOnly = { false }))
        return try {
            if (files.hasVault()) {
                val id = files.readVaultMeta().meta.vaultId
                pendingVaultId = id
                Screen.ExistingVault(canOpen = KeystoreKeyWrapper.forVault(id).keyExists())
            } else if (graph.location.volumeState(volume.volume) == AvailabilityRules.MOUNTED_READ_ONLY) {
                abandonPending()
                welcome(R.string.error_folder_read_only)
            } else if (volume.isPrimary) {
                Screen.PrimaryWarning
            } else {
                Screen.SetPin(confirming = false)
            }
        } catch (@Suppress("SwallowedException") e: VaultFormatException) {
            abandonPending()
            welcome(R.string.error_vault_damaged)
        } catch (@Suppress("SwallowedException") e: IOException) {
            abandonPending()
            welcome(R.string.error_folder_io)
        }
    }

    /** Runs off the main thread. Returns the screen to show on failure, or null on success. */
    private fun unlock(pin: ByteArray): Screen? {
        val record = graph.location.load()
        val files = graph.files()
        if (record == null || files == null) {
            pin.fill(0)
            return welcome()
        }
        return try {
            graph.keys.unlock(files, pin, ItemRepository.idFromHex(record.vaultId), graph.session)
            graph.repository(files).loadAll()
            null // the session flow moves the UI to Unlocked
        } catch (@Suppress("SwallowedException") e: WrongPinException) {
            Screen.Locked(R.string.error_wrong_pin)
        } catch (@Suppress("SwallowedException") e: KeyWrapperKeyMissingException) {
            Screen.Locked(R.string.error_key_missing)
        } catch (@Suppress("SwallowedException") e: GeneralSecurityException) {
            Screen.Locked(R.string.error_cannot_open)
        } catch (@Suppress("SwallowedException") e: VaultIdMismatchException) {
            Screen.Unavailable(UnavailableReason.WRONG_VAULT)
        } catch (@Suppress("SwallowedException") e: VaultFormatException) {
            graph.session.lock()
            Screen.Locked(R.string.error_vault_damaged)
        } catch (@Suppress("SwallowedException") e: VaultNotFoundException) {
            Screen.Unavailable(UnavailableReason.VAULT_MISSING)
        } catch (@Suppress("SwallowedException") e: VaultUnavailableException) {
            graph.session.lock()
            Screen.Unavailable(currentReason(UnavailableReason.PROVIDER_ERROR))
        } catch (@Suppress("SwallowedException") e: IOException) {
            graph.session.lock()
            Screen.Locked(R.string.error_folder_io)
        }
    }

    /** Runs on IO. Returns the refusal screen, or null if the folder was accepted. */
    private fun repick(tree: Uri): Screen? {
        val record = graph.location.load() ?: return welcome()
        val reason = currentReason(UnavailableReason.VAULT_MISSING)
        try {
            graph.location.takeGrant(tree)
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            return Screen.Unavailable(reason, R.string.error_folder_grant)
        }
        val volume = graph.location.volumeOf(tree)
        val refusal = if (volume == null) {
            R.string.error_folder_provider
        } else {
            try {
                VaultFiles(SafVaultStorage(tree, graph.safIo, readOnly = { true }))
                    .requireVaultId(ItemRepository.idFromHex(record.vaultId))
                null
            } catch (@Suppress("SwallowedException") e: VaultIdMismatchException) {
                R.string.error_repick_other_vault
            } catch (@Suppress("SwallowedException") e: VaultNotFoundException) {
                R.string.error_repick_no_vault
            } catch (@Suppress("SwallowedException") e: IOException) {
                R.string.error_folder_io
            }
        }
        if (refusal != null || volume == null) {
            if (tree.toString() != record.treeUri) graph.location.releaseGrant(tree)
            return Screen.Unavailable(reason, refusal)
        }
        val old = Uri.parse(record.treeUri)
        graph.location.save(record.copy(treeUri = tree.toString(), volume = volume.volume))
        if (old != tree) graph.location.releaseGrant(old)
        graph.monitor.fullCheck()
        return null
    }

    private fun currentReason(fallback: UnavailableReason) =
        (graph.monitor.state.value as? VaultAvailability.Unavailable)?.reason ?: fallback

    private fun setupError(e: Exception): Screen {
        graph.session.lock()
        clearPending(releaseGrant = true)
        val message = when (e) {
            is VaultAlreadyExistsException -> R.string.error_vault_exists
            is VaultReadOnlyException -> R.string.error_folder_read_only
            is KdfUnavailableException -> R.string.error_kdf_memory
            is IOException -> R.string.error_folder_io
            else -> R.string.error_cannot_create
        }
        return welcome(message)
    }

    private fun welcome(@StringRes error: Int? = null) = Screen.Welcome(graph.location.hasRemovableVolume(), error)

    private fun abandonPending() = clearPending(releaseGrant = true)

    private fun clearPending(releaseGrant: Boolean) {
        val tree = pendingTree
        if (releaseGrant && tree != null && tree.toString() != graph.location.load()?.treeUri) {
            graph.location.releaseGrant(tree)
        }
        pendingTree = null
        pendingVolume = null
        pendingVaultId = null
        wipePin()
    }

    private fun wipePin() {
        pendingPin?.fill(0)
        pendingPin = null
    }

    private companion object {
        val UNLOCKING = Screen.Working(R.string.working_unlocking)
    }
}
