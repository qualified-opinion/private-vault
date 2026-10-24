package com.qualifiedopinion.privatevault.vault

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * [VaultStorage] over a plain directory. Used by JVM tests, the minified unlock test and the
 * benchmark's `File` baseline. The app itself only uses [SafVaultStorage] (planning pack §5.2).
 *
 * [readOnly] simulates a read-only mount: every write throws [VaultReadOnlyException].
 */
@Suppress("TooManyFunctions") // the VaultStorage interface plus path helpers
class FileVaultStorage(private val root: File, private val readOnly: Boolean = false) : VaultStorage {

    override val isReadOnly: Boolean get() = readOnly

    override fun list(dir: String): List<String> =
        dirFile(dir).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()

    override fun exists(dir: String, name: String): Boolean = file(dir, name).isFile

    override fun ensureDir(dir: String) {
        requireWritable()
        val d = dirFile(dir)
        if (!d.isDirectory && !d.mkdirs()) throw IOException("could not create directory")
    }

    override fun openRead(dir: String, name: String): FileChannel {
        val f = file(dir, name)
        if (!f.isFile) throw FileNotFoundException("no such file")
        return FileChannel.open(f.toPath(), StandardOpenOption.READ)
    }

    override fun create(dir: String, name: String): FileChannel {
        requireWritable()
        return FileChannel.open(file(dir, name).toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    override fun rename(dir: String, from: String, to: String) {
        requireWritable()
        moveFile(file(dir, from), file(dir, to))
    }

    override fun move(fromDir: String, name: String, toDir: String) {
        requireWritable()
        moveFile(file(fromDir, name), file(toDir, name))
    }

    override fun delete(dir: String, name: String): Boolean {
        requireWritable()
        return Files.deleteIfExists(file(dir, name).toPath())
    }

    private fun moveFile(src: File, dst: File) {
        if (!src.isFile) throw FileNotFoundException("no such file")
        // No ATOMIC_MOVE: on POSIX it silently replaces an existing target.
        if (dst.exists()) throw FileAlreadyExistsException(dst.name)
        Files.move(src.toPath(), dst.toPath())
    }

    private fun requireWritable() {
        if (readOnly) throw VaultReadOnlyException()
    }

    private fun dirFile(dir: String): File = if (dir == VaultStorage.ROOT) root else File(root, checkName(dir))

    private fun file(dir: String, name: String): File = File(dirFile(dir), checkName(name))
}

/** Rejects anything that isn't a single plain path segment. */
internal fun checkName(name: String): String {
    require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) {
        "invalid file name"
    }
    return name
}
