package com.qualifiedopinion.privatevault.vault

import java.io.IOException

/**
 * The vault folder can't be reached: the card was removed or unmounted, the persisted grant is gone,
 * or the provider failed (planning pack §5.4). The UI locks and shows the re-pick screen.
 */
class VaultUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The volume or the grant is read-only. Viewing works; import and delete are blocked (§5.4). */
class VaultReadOnlyException : IOException("the vault folder is read-only")

/** The folder holds no `vault.meta` (nor a usable `vault.meta.bak`). */
class VaultNotFoundException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The folder holds a different vault than the one this install is bound to (§5.4: re-pick check). */
class VaultIdMismatchException : IOException("the folder holds a different vault")

/** Setup was pointed at a folder that already holds a vault. Never overwrite one. */
class VaultAlreadyExistsException : IOException("the folder already holds a vault")

/** An operation needed the data key, but the vault is locked. */
class VaultLockedException : IllegalStateException("the vault is locked")
