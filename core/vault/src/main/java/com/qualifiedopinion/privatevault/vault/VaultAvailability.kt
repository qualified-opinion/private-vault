package com.qualifiedopinion.privatevault.vault

/** Whether the bound vault folder can be used right now (planning pack §5.4). */
sealed interface VaultAvailability {
    /** No vault location saved: run setup. */
    data object NotConfigured : VaultAvailability

    /** Not checked yet (process start). */
    data object Unknown : VaultAvailability

    data class Available(val readOnly: Boolean) : VaultAvailability

    data class Unavailable(val reason: UnavailableReason) : VaultAvailability
}

enum class UnavailableReason {
    /** The persisted grant is gone (revoked, cleared, or the card was reformatted). */
    NO_PERMISSION,

    /** The volume isn't mounted (ejected, removed, bad removal, being checked). */
    VOLUME_MISSING,

    /** The folder is there, but holds no `vault.meta`/`.bak`. */
    VAULT_MISSING,

    /** The folder holds a different vault id. */
    WRONG_VAULT,

    /** `vault.meta` and its backup are both unreadable. */
    VAULT_DAMAGED,

    /** The provider threw (I/O error, permission error, root vanished). */
    PROVIDER_ERROR,
}

/** Pure decision rules behind [VolumeMonitor], kept apart so they run on the JVM. */
object AvailabilityRules {
    /** `Environment.MEDIA_MOUNTED` / `MEDIA_MOUNTED_READ_ONLY`. */
    const val MOUNTED = "mounted"
    const val MOUNTED_READ_ONLY = "mounted_ro"

    /** The cheap check: grant and volume only. */
    fun quick(grant: Grant, volumeState: String?): VaultAvailability = when {
        grant == Grant.NONE -> VaultAvailability.Unavailable(UnavailableReason.NO_PERMISSION)
        volumeState != MOUNTED && volumeState != MOUNTED_READ_ONLY ->
            VaultAvailability.Unavailable(UnavailableReason.VOLUME_MISSING)
        else -> VaultAvailability.Available(readOnly = grant == Grant.READ_ONLY || volumeState == MOUNTED_READ_ONLY)
    }

    /**
     * Merges a quick check into the current state. A quick check can only make things worse (or
     * confirm them): coming back from [VaultAvailability.Unavailable] needs a full check, which also
     * verifies the vault id (§5.4: Retry).
     */
    fun mergeQuick(current: VaultAvailability, quick: VaultAvailability): VaultAvailability = when {
        quick is VaultAvailability.Unavailable -> quick
        current is VaultAvailability.Available && quick is VaultAvailability.Available ->
            VaultAvailability.Available(current.readOnly || quick.readOnly)
        else -> current
    }

    /**
     * Merges the result of a full or pre-I/O check into the current state. A check reads the folder,
     * so it can take a while; if the vault was reported gone while it ran ([goneDuringCheck]), an
     * "available" result is stale and must not undo that. A worse result is always taken.
     */
    fun mergeChecked(
        current: VaultAvailability,
        checked: VaultAvailability,
        goneDuringCheck: Boolean,
    ): VaultAvailability = if (goneDuringCheck && checked is VaultAvailability.Available) current else checked

    /** Does a `MEDIA_*` broadcast for [path] (`file:///storage/<uuid>`) concern a vault on [volume]? */
    fun broadcastConcerns(volume: String, path: String?): Boolean {
        if (path.isNullOrEmpty()) return true // can't tell: assume the worst
        val last = path.trimEnd('/').substringAfterLast('/')
        return if (volume == TreeVolume.PRIMARY) {
            path.startsWith("/storage/emulated")
        } else {
            last.equals(volume, ignoreCase = true)
        }
    }
}
