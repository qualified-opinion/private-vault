package com.qualifiedopinion.privatevault.importer

import com.qualifiedopinion.privatevault.crypto.VaultCrypto
import com.qualifiedopinion.privatevault.vault.ItemKind
import com.qualifiedopinion.privatevault.vault.ItemRepository
import com.qualifiedopinion.privatevault.vault.NewItem
import com.qualifiedopinion.privatevault.vault.VaultLockedException
import com.qualifiedopinion.privatevault.vault.VaultReadOnlyException
import com.qualifiedopinion.privatevault.vault.VaultUnavailableException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** Why an import stopped before the end of the list. */
enum class StopReason { LOCKED, UNAVAILABLE, READ_ONLY }

/** Progress while importing: [done] of [total] items handled, [imported] of them saved. */
data class ImportProgress(val done: Int, val total: Int, val imported: Int)

/** The outcome of one import run. Items after a stop are not counted in any bucket. */
data class ImportResult(
    val total: Int,
    val imported: Int = 0,
    val unsupported: Int = 0,
    val noSpace: Int = 0,
    val failed: Int = 0,
    val stoppedBy: StopReason? = null,
)

/** Pure rules, kept apart so they are easy to test. */
object ImportRules {
    /** Head room on top of the encrypted size: thumbnail, `.vmeta`, and filesystem overhead. */
    const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024

    fun kindOf(mime: String): ItemKind? = when {
        mime.startsWith("image/") -> ItemKind.PHOTO
        mime.startsWith("video/") -> ItemKind.VIDEO
        else -> null
    }

    /** False only when both sizes are known and the encrypted item would not fit. */
    fun fits(size: Long?, freeBytes: Long?): Boolean =
        size == null || freeBytes == null || VaultCrypto.fileSizeFor(size) + SPACE_MARGIN_BYTES <= freeBytes
}

/**
 * Encrypts picked photos and videos into the vault, one at a time (planning pack §6 PR4).
 *
 * - Each item goes through [ItemRepository.create]: streamed via `staging/`, never written in
 *   plaintext, and either complete or removed.
 * - Originals are only read, never changed or deleted.
 * - A bad item (unreadable, not a photo or video, too big for the free space) is counted and
 *   skipped. A locked, missing or read-only vault stops the run.
 * - [isActive] is checked before each item and on every read of the original, so locking stops
 *   an item part-way and its partial files are removed.
 *
 * Blocking I/O: run it off the main thread, holding the app's write lock.
 */
class Importer(
    private val repository: ItemRepository,
    private val freeBytes: () -> Long?,
    private val isActive: () -> Boolean,
) {
    fun importAll(sources: List<ImportSource>, onProgress: (ImportProgress) -> Unit = {}): ImportResult {
        var result = ImportResult(total = sources.size)
        for ((i, source) in sources.withIndex()) {
            val outcome = if (isActive()) attempt(source) else Outcome.STOP_LOCKED
            if (outcome.stop != null) return result.copy(stoppedBy = outcome.stop)
            result = result.add(outcome)
            onProgress(ImportProgress(done = i + 1, total = sources.size, imported = result.imported))
        }
        return result
    }

    private fun attempt(source: ImportSource): Outcome = try {
        importOne(source)
    } catch (e: IOException) {
        outcomeOf(e)
    } catch (e: SecurityException) {
        outcomeOf(e) // the picker's read grant is gone
    } catch (e: VaultLockedException) {
        outcomeOf(e)
    }

    @Suppress("ReturnCount") // guard clauses
    private fun importOne(source: ImportSource): Outcome {
        val info = source.describe()
        val kind = ImportRules.kindOf(info.mime) ?: return Outcome.UNSUPPORTED
        if (!ImportRules.fits(info.size, freeBytes())) return Outcome.NO_SPACE
        val details = source.probe(info)
        val item = NewItem(
            kind = kind,
            mime = info.mime,
            name = info.name,
            w = details.w,
            h = details.h,
            rot = details.rot,
            durMs = details.durMs,
            takenAt = details.takenAt,
        )
        source.open().use { input ->
            repository.create(item, StopOnInactive(input, isActive), details.thumbnail)
        }
        return Outcome.IMPORTED
    }

    private fun outcomeOf(e: Exception): Outcome = when (e) {
        is VaultReadOnlyException -> Outcome.STOP_READ_ONLY
        is VaultUnavailableException -> Outcome.STOP_UNAVAILABLE
        is VaultLockedException, is ImportStoppedException -> Outcome.STOP_LOCKED
        else -> Outcome.FAILED
    }

    private fun ImportResult.add(outcome: Outcome): ImportResult = when (outcome) {
        Outcome.IMPORTED -> copy(imported = imported + 1)
        Outcome.UNSUPPORTED -> copy(unsupported = unsupported + 1)
        Outcome.NO_SPACE -> copy(noSpace = noSpace + 1)
        else -> copy(failed = failed + 1)
    }

    private enum class Outcome(val stop: StopReason? = null) {
        IMPORTED,
        UNSUPPORTED,
        NO_SPACE,
        FAILED,
        STOP_LOCKED(StopReason.LOCKED),
        STOP_UNAVAILABLE(StopReason.UNAVAILABLE),
        STOP_READ_ONLY(StopReason.READ_ONLY),
    }
}

/** Thrown from a read of the original once the import should stop (the vault locked). */
class ImportStoppedException : InterruptedIOException("import stopped")

/** Fails the next read once [isActive] turns false, so [ItemRepository.create] aborts and cleans up. */
internal class StopOnInactive(input: InputStream, private val isActive: () -> Boolean) : FilterInputStream(input) {
    override fun read(): Int {
        ensureActive()
        return super.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        ensureActive()
        return super.read(b, off, len)
    }

    private fun ensureActive() {
        if (!isActive()) throw ImportStoppedException()
    }
}
