package com.qualifiedopinion.privatevault.vault

import java.nio.channels.FileChannel

/**
 * The file operations the vault needs, over one vault folder (planning pack §3.1).
 *
 * Paths are a directory ([ROOT] or one of the layout directories in [VaultFiles]) plus a plain file
 * name; there is no nesting below that. Production is [SafVaultStorage] over a SAF tree;
 * [FileVaultStorage] is a `java.io.File` backend for tests and benchmarks.
 *
 * Channels are raw `FileChannel`s (planning pack §5.2: bulk I/O never goes through a stream
 * wrapper). The caller closes them; closing a channel releases everything behind it.
 */
interface VaultStorage {
    /** True if the volume or the grant is read-only. Writes then throw [VaultReadOnlyException]. */
    val isReadOnly: Boolean

    /** File names in [dir] (no directories). Empty if [dir] doesn't exist. */
    fun list(dir: String): List<String>

    fun exists(dir: String, name: String): Boolean

    /** Creates [dir] under the root if it doesn't exist. */
    fun ensureDir(dir: String)

    /** Opens an existing file for reading at position 0. */
    fun openRead(dir: String, name: String): FileChannel

    /**
     * Creates [name] in [dir] and opens it for writing at position 0.
     * Throws [java.nio.file.FileAlreadyExistsException] if it exists: callers never overwrite in place.
     */
    fun create(dir: String, name: String): FileChannel

    /** Renames within [dir]. Throws if [to] exists or [from] doesn't. */
    fun rename(dir: String, from: String, to: String)

    /** Moves [name] from [fromDir] to [toDir], keeping the name. Throws if the target exists. */
    fun move(fromDir: String, name: String, toDir: String)

    /** Deletes a file. Returns false if it didn't exist. */
    fun delete(dir: String, name: String): Boolean

    companion object {
        const val ROOT = ""
    }
}
