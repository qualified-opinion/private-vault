package com.qualifiedopinion.privatevault.vault

import com.google.crypto.tink.KeysetHandle
import com.qualifiedopinion.privatevault.crypto.VaultCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The unlocked state (planning pack §2): the data keyset and its [VaultCrypto], plus every channel
 * opened under it. [lock] drops the key references, closes tracked channels, clears the [VaultIndex]
 * and runs the lock listeners (later PRs add the Coil cache and the player there).
 *
 * The JVM can't reliably zeroize the Tink keyset (risk 7); dropping all references is what we can do.
 */
class VaultSession(private val index: VaultIndex) {
    private val guard = Any()
    private var keyset: KeysetHandle? = null
    private var crypto: VaultCrypto? = null
    private var openVaultId: ByteArray? = null
    private val tracked = LinkedHashSet<Closeable>()
    private val lockListeners = CopyOnWriteArrayList<() -> Unit>()
    private val unlocked = MutableStateFlow(false)

    val isUnlocked: StateFlow<Boolean> = unlocked.asStateFlow()

    /** The id of the unlocked vault, or null while locked. */
    val vaultId: ByteArray? get() = synchronized(guard) { openVaultId?.copyOf() }

    /** Starts a session with [dk]. Any previous session is locked first. */
    fun unlock(dk: KeysetHandle, vaultId: ByteArray) {
        val newCrypto = VaultCrypto.fromKeyset(dk)
        lock()
        synchronized(guard) {
            keyset = dk
            crypto = newCrypto
            openVaultId = vaultId.copyOf()
        }
        unlocked.value = true
    }

    /** The streaming cipher for vault files. Throws [VaultLockedException] while locked. */
    fun crypto(): VaultCrypto = synchronized(guard) { crypto ?: throw VaultLockedException() }

    /** The data keyset, for re-wrapping (change PIN in PR7, recovery in PR8). Throws while locked. */
    fun keyset(): KeysetHandle = synchronized(guard) { keyset ?: throw VaultLockedException() }

    /**
     * Registers [c] to be closed on [lock]. If the vault is already locked, closes [c] and throws
     * [VaultLockedException], so no channel outlives the key.
     */
    fun <T : Closeable> track(c: T): T {
        val accepted = synchronized(guard) { crypto != null && tracked.add(c) }
        if (!accepted) {
            closeQuietly(c)
            throw VaultLockedException()
        }
        return c
    }

    fun untrack(c: Closeable) {
        synchronized(guard) { tracked.remove(c) }
    }

    fun addLockListener(listener: () -> Unit) {
        lockListeners += listener
    }

    /** Idempotent. Safe to call from any thread, including the main thread (no I/O beyond closing fds). */
    fun lock() {
        val toClose: List<Closeable>
        val wasUnlocked: Boolean
        synchronized(guard) {
            wasUnlocked = crypto != null
            keyset = null
            crypto = null
            openVaultId = null
            toClose = tracked.toList()
            tracked.clear()
        }
        unlocked.value = false
        index.clear()
        toClose.forEach(::closeQuietly)
        if (wasUnlocked) lockListeners.forEach { it() }
    }

    private fun closeQuietly(c: Closeable) {
        try {
            c.close()
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception) {
            // Closing during lock: the channel may already be dead (card removed). Nothing to do.
        }
    }
}
