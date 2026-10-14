package com.qualifiedopinion.privatevault.vault

import com.qualifiedopinion.privatevault.format.FileHeader
import com.qualifiedopinion.privatevault.format.VaultFormatException
import com.qualifiedopinion.privatevault.format.VaultMeta
import com.qualifiedopinion.privatevault.format.VaultMetaCodec
import com.qualifiedopinion.privatevault.vault.VaultStorage.Companion.ROOT
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** `vault.meta` bytes as read from disk, parsed. [fromBackup] is true if `vault.meta` was unusable. */
class StoredVaultMeta(bytes: ByteArray, val meta: VaultMeta, val fromBackup: Boolean) {
    private val raw = bytes.copyOf()
    val bytes: ByteArray get() = raw.copyOf()
}

/**
 * The on-disk layout of one vault folder (planning pack §3.1) on top of a [VaultStorage]:
 *
 * ```
 * vault.meta, vault.meta.bak, items/<id>.vdat|.vthm|.vmeta, staging/<id>.vdat.part, journal/
 * ```
 */
@Suppress("TooManyFunctions") // one function per layout operation
class VaultFiles(val storage: VaultStorage) {

    val isReadOnly: Boolean get() = storage.isReadOnly

    /** True if the folder holds `vault.meta` or its backup. */
    fun hasVault(): Boolean = storage.exists(ROOT, VAULT_META) || storage.exists(ROOT, VAULT_META_BAK)

    /**
     * Reads `vault.meta`, falling back to `vault.meta.bak` if it is missing or doesn't parse (§3.3).
     * Provider I/O errors are not a reason to fall back: they propagate, because they mean the
     * folder is unavailable, not that the file is bad.
     *
     * @throws VaultNotFoundException neither file exists
     * @throws VaultFormatException both files exist but neither parses (the primary's error is thrown)
     */
    fun readVaultMeta(): StoredVaultMeta {
        val primaryError = try {
            readMetaFile(VAULT_META)?.let { return StoredVaultMeta(it.first, it.second, fromBackup = false) }
            null
        } catch (e: VaultFormatException) {
            e
        }
        val backup = try {
            readMetaFile(VAULT_META_BAK)
        } catch (e: VaultFormatException) {
            throw primaryError ?: e
        }
        if (backup != null) return StoredVaultMeta(backup.first, backup.second, fromBackup = true)
        throw primaryError ?: VaultNotFoundException("no vault.meta in the folder")
    }

    /** Throws [VaultIdMismatchException] unless the folder's vault has id [expected]. */
    fun requireVaultId(expected: ByteArray): StoredVaultMeta {
        val stored = readVaultMeta()
        if (!stored.meta.vaultId.contentEquals(expected)) throw VaultIdMismatchException()
        return stored
    }

    /**
     * Writes a new vault into an empty (vault-less) folder: creates the layout directories, then
     * `vault.meta`. Throws [VaultAlreadyExistsException] rather than ever overwriting a vault.
     */
    fun createVault(vaultMeta: ByteArray) {
        requireWritable()
        if (hasVault()) throw VaultAlreadyExistsException()
        for (dir in LAYOUT_DIRS) storage.ensureDir(dir)
        writeVaultMeta(vaultMeta)
    }

    /**
     * Replaces `vault.meta` as atomically as SAF allows (§3.3):
     * write `vault.meta.tmp` and fsync it, read it back, move the current file to `.bak`,
     * then rename `.tmp` into place. A crash at any point leaves a parsable `vault.meta` or `.bak`.
     */
    fun writeVaultMeta(vaultMeta: ByteArray) {
        requireWritable()
        VaultMetaCodec.decode(vaultMeta) // never write something we couldn't read back
        if (storage.exists(ROOT, VAULT_META_TMP)) storage.delete(ROOT, VAULT_META_TMP)
        storage.create(ROOT, VAULT_META_TMP).use { ch ->
            writeFully(ch, vaultMeta)
            ch.force(true)
        }
        if (!readSmall(VAULT_META_TMP, MAX_VAULT_META_SIZE).contentEquals(vaultMeta)) {
            storage.delete(ROOT, VAULT_META_TMP)
            throw VaultFormatException("vault.meta.tmp read back differently")
        }
        if (storage.exists(ROOT, VAULT_META)) {
            if (parses(VAULT_META)) {
                if (storage.exists(ROOT, VAULT_META_BAK)) storage.delete(ROOT, VAULT_META_BAK)
                storage.rename(ROOT, VAULT_META, VAULT_META_BAK)
            } else {
                // A corrupt current file must not replace a good backup.
                storage.delete(ROOT, VAULT_META)
            }
        }
        storage.rename(ROOT, VAULT_META_TMP, VAULT_META)
    }

    /**
     * Startup cleanup (§3.1, §6 PR4): deletes `*.part` files in `staging/` (and in `items/`, where a
     * crash between move and rename can leave one). Returns how many were deleted. Does nothing on a
     * read-only folder.
     */
    fun cleanStaging(): Int {
        if (isReadOnly) return 0
        var deleted = 0
        for (dir in listOf(STAGING, ITEMS)) {
            for (name in storage.list(dir)) {
                if (name.endsWith(PART_SUFFIX) && storage.delete(dir, name)) deleted++
            }
        }
        return deleted
    }

    fun requireWritable() {
        if (isReadOnly) throw VaultReadOnlyException()
    }

    /** Reads a whole small file (at most [maxSize] bytes, else [VaultFormatException]). */
    fun readSmall(dir: String, name: String, maxSize: Int): ByteArray =
        storage.openRead(dir, name).use { ch -> readBounded(ch, maxSize) }

    private fun readSmall(name: String, maxSize: Int) = readSmall(ROOT, name, maxSize)

    /** Bytes and parsed meta, or null if the file doesn't exist. */
    private fun readMetaFile(name: String): Pair<ByteArray, VaultMeta>? {
        if (!storage.exists(ROOT, name)) return null
        val bytes = readSmall(name, MAX_VAULT_META_SIZE)
        return bytes to VaultMetaCodec.decode(bytes)
    }

    private fun parses(name: String): Boolean = try {
        readMetaFile(name) != null
    } catch (@Suppress("SwallowedException") e: VaultFormatException) {
        false
    }

    companion object {
        const val VAULT_META = "vault.meta"
        const val VAULT_META_BAK = "vault.meta.bak"
        const val VAULT_META_TMP = "vault.meta.tmp"
        const val ITEMS = "items"
        const val STAGING = "staging"
        const val JOURNAL = "journal"
        val LAYOUT_DIRS = listOf(ITEMS, STAGING, JOURNAL)

        const val DATA_SUFFIX = ".vdat"
        const val THUMB_SUFFIX = ".vthm"
        const val META_SUFFIX = ".vmeta"
        const val PART_SUFFIX = ".part"

        /** Header + fixed fields + the largest outer blob [VaultMetaCodec] accepts. */
        const val MAX_VAULT_META_SIZE = VaultMetaCodec.AAD_LENGTH + Int.SIZE_BYTES + VaultMetaCodec.MAX_OUTER_LENGTH

        private val ITEM_ID = Regex("[0-9a-f]{${FileHeader.ID_SIZE * 2}}")

        fun isItemId(idHex: String): Boolean = ITEM_ID.matches(idHex)

        fun dataName(idHex: String) = idHex + DATA_SUFFIX
        fun thumbName(idHex: String) = idHex + THUMB_SUFFIX
        fun metaName(idHex: String) = idHex + META_SUFFIX
        fun partName(idHex: String) = idHex + DATA_SUFFIX + PART_SUFFIX
    }
}

internal fun writeFully(ch: FileChannel, bytes: ByteArray) {
    val buf = ByteBuffer.wrap(bytes)
    while (buf.hasRemaining()) ch.write(buf)
}

/** Reads the whole channel; throws [VaultFormatException] if it is longer than [maxSize]. */
internal fun readBounded(ch: FileChannel, maxSize: Int): ByteArray {
    val buf = ByteBuffer.allocate(maxSize + 1)
    while (buf.hasRemaining()) {
        if (ch.read(buf) < 0) break
    }
    if (!buf.hasRemaining()) throw VaultFormatException("file too large")
    return buf.array().copyOf(buf.position())
}
