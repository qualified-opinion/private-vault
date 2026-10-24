package com.qualifiedopinion.privatevault.vault

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Where the vault lives (planning pack §5.3): the persisted tree URI, the volume it is bound to
 * (`primary` or the SD card's UUID) and the vault id. Stored in `noBackupFilesDir/location.json`.
 * The vault id hex is not secret; it is also the Keystore alias suffix.
 */
@Serializable
data class LocationRecord(
    val v: Int = VERSION,
    val treeUri: String,
    val volume: String,
    val vaultId: String,
) {
    val isPrimary: Boolean get() = volume == TreeVolume.PRIMARY

    override fun toString(): String = "LocationRecord(primary=$isPrimary)"

    companion object {
        const val VERSION = 1
    }
}

/** Reads and writes `location.json` (plain `File`: app-private, no-backup, holds no media or keys). */
class LocationStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = false }

    /** The saved record, or null if there is none or it can't be read (setup then starts over). */
    fun load(): LocationRecord? {
        if (!file.isFile) return null
        return try {
            val record = json.decodeFromString(LocationRecord.serializer(), file.readText())
            record.takeIf { it.v == LocationRecord.VERSION }
        } catch (@Suppress("SwallowedException") e: SerializationException) {
            null
        } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
            null
        }
    }

    /** Writes via a temp file and a rename, so a crash never leaves half a record. */
    fun save(record: LocationRecord) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(LocationRecord.serializer(), record))
        if (!tmp.renameTo(file)) throw IOException("could not save the vault location")
    }

    fun clear() {
        file.delete()
    }
}

/** The volume part of an ExternalStorageProvider tree. */
data class TreeVolume(val volume: String) {
    val isPrimary: Boolean get() = volume == PRIMARY

    companion object {
        const val PRIMARY = "primary"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val HOME_ROOT = "home" // the "Documents" root on 29+, which is on the primary volume

        /**
         * Parses the tree document id (`primary:Vault`, `1234-ABCD:Vault`). Returns null for any other
         * provider: volume binding, the volume monitor and seekable `"rw"` descriptors are only known
         * to work on ExternalStorageProvider, and cloud providers make no sense offline.
         */
        fun parse(authority: String?, treeDocumentId: String): TreeVolume? {
            if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
            val root = treeDocumentId.substringBefore(':', missingDelimiterValue = "")
            return when {
                root.isEmpty() -> null
                root == PRIMARY || root == HOME_ROOT -> TreeVolume(PRIMARY)
                else -> TreeVolume(root.uppercase())
            }
        }
    }
}

/** What the persisted grant allows. */
enum class Grant { NONE, READ_ONLY, READ_WRITE }

/** Android side of the vault location: persisted grants, volumes and the picker's start folder. */
class VaultLocation(
    private val context: Context,
    private val store: LocationStore = LocationStore(File(context.noBackupFilesDir, FILE_NAME)),
) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val cacheLock = Any()
    private var cached: LocationRecord? = null
    private var cacheValid = false

    /** The saved record (read from disk once, then kept in memory; the monitor polls this every second). */
    fun load(): LocationRecord? = synchronized(cacheLock) {
        if (!cacheValid) {
            cached = store.load()
            cacheValid = true
        }
        cached
    }

    fun save(record: LocationRecord) = synchronized(cacheLock) {
        store.save(record)
        cached = record
        cacheValid = true
    }

    fun clear() = synchronized(cacheLock) {
        store.clear()
        cached = null
        cacheValid = true
    }

    /** Persists the picker's grant (§5.3). Throws `SecurityException` if the URI wasn't granted persistably. */
    fun takeGrant(tree: Uri) {
        resolver.takePersistableUriPermission(tree, READ_WRITE_FLAGS)
    }

    /** Drops a grant we no longer need (a re-pick replaced it, or setup was abandoned). */
    fun releaseGrant(tree: Uri) {
        try {
            resolver.releasePersistableUriPermission(tree, READ_WRITE_FLAGS)
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            // Not held: nothing to release.
        }
    }

    fun grantFor(tree: Uri): Grant {
        val p = resolver.persistedUriPermissions.firstOrNull { it.uri == tree } ?: return Grant.NONE
        return when {
            p.isReadPermission && p.isWritePermission -> Grant.READ_WRITE
            p.isReadPermission -> Grant.READ_ONLY
            else -> Grant.NONE
        }
    }

    /** The volume of [tree], or null if it isn't an ExternalStorageProvider tree. */
    fun volumeOf(tree: Uri): TreeVolume? = try {
        TreeVolume.parse(tree.authority, DocumentsContract.getTreeDocumentId(tree))
    } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
        null
    }

    /**
     * The volume's state (`mounted`, `mounted_ro`, `ejecting`, …), or null if it isn't present
     * (card removed). Uses [StorageManager.getStorageVolumes], which needs no permission.
     */
    fun volumeState(volume: String): String? {
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        val v = sm.storageVolumes.firstOrNull { sv ->
            if (volume == TreeVolume.PRIMARY) sv.isPrimary else sv.uuid.equals(volume, ignoreCase = true)
        }
        return v?.state
    }

    /** True if a removable volume (an SD card) is mounted. */
    fun hasRemovableVolume(): Boolean {
        val sm = context.getSystemService(StorageManager::class.java) ?: return false
        return sm.storageVolumes.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
    }

    /** The SD card root, to open the picker there (§5.3, API 29+). Null if unknown. */
    fun removableVolumeInitialUri(): Uri? {
        val sm = context.getSystemService(StorageManager::class.java)
        val volume = sm?.storageVolumes?.firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        if (volume == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val intent = volume.createOpenDocumentTreeIntent()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI)
        }
    }

    companion object {
        const val FILE_NAME = "location.json"
        private const val READ_WRITE_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
