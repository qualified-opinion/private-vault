# PR4 plan: Import photos and videos

Branch: `claude/project-thread-av3x1w` (cut from `feat/pr3-storage` @ baa3aba; PR3 is still a draft). The planning pack (§6 PR4) was not available when this was written (it has since been rebuilt as `docs/PLANNING_PACK.md`), so the defaults below were chosen here. Jan confirmed them on 2026-10-28.

## Defaults (confirmed by Jan, 2026-10-28)
| Question | Default | Why |
|---|---|---|
| Delete originals after import? | **No.** Originals are only read. The summary says they are still in the gallery. | Deleting needs a system prompt per batch (`MediaStore.createDeleteRequest`) and is the one step that can lose data. Better as its own small PR once import is proven on the phone. |
| How are files picked? | System photo picker (`PickMultipleVisualMedia`, images and videos). | Needs no storage permission, works on API 26+ through the backport. |
| Thumbnail | 320 px JPEG made in memory, encrypted into `.vthm`. API 29+ uses the system thumbnail; older uses a downsampled decode or the first video frame. | Never writes plaintext to disk. |
| Free-space check | Skip an item when `fileSizeFor(size) + 16 MiB` is more than the volume's free bytes (SAF root `COLUMN_AVAILABLE_BYTES`). Unknown size or free space: try, and a failed write counts as failed. | Cheap, and the write path already cleans up. |
| 4 GiB FAT32 limit | No pre-check. A write failure counts as failed and its partial files are removed. | Card format is not known from SAF; revisit if the phone shows FAT32. |
| Order and speed | One item at a time, in the order picked. | Simple, low memory, matches the single write lock. |
| Lock during import | Locking cancels the run. The item in progress stops on its next read and its partial files are removed. Items already done stay. | No half items, no key outlives the session. |
| Duplicates | Not detected. | Needs the hash before writing; later PR. |

## Scope
- `:core:vault`: `VaultStorage.freeBytes()` (SAF root query, `File.usableSpace`). Startup cleanup also removes `.vdat`/`.vthm` files with no `.vmeta` (review finding F8). It shares one write lock with import (F9).
- `:feature:import` (package `.importer`): `ImportSource` / `UriImportSource`, `MediaProbe` (size, rotation, duration, date taken, thumbnail), `MediaDates`, `Importer` (the loop and its rules), `ImportController` (one run at a time in the app scope, `StateFlow<ImportState>`, cancelled on lock). Placeholder `ImportModule` removed.
- `:app`: `AppGraph.writeLock` + `importer`; startup cleanup under the write lock; Import button, progress bar and summary on the unlocked screen (hidden on a read-only vault). No new permissions.

Out of scope: grid and viewer (PR5), deleting originals, duplicate detection, background import (a foreground service).

## Tests
- JVM `ImporterTest` (real Tink, `FileVaultStorage` on a temp dir): photo + video imported and readable, other types skipped, no-space skip writes nothing, an unreadable item is counted and the run continues, lock between items, lock mid-item leaves no files in `items/` or `staging/`, locked session, read-only folder, rules and date parsing.
- JVM `VaultFilesTest`: orphan `.vdat`/`.vthm` cleanup keeps complete items and unrelated names; `FileVaultStorage.freeBytes()`.
- Not covered by JVM tests: `MediaProbe`/`UriImportSource` (Android decoders) and the picker. Phone checks are in the PR description.

## Known risks
- If an item finishes in the same instant the vault locks, it can be re-added to the in-memory index after lock cleared it. `ImportController` locks again at the end, which clears it; the files on the card are complete either way.
- HEIC thumbnails below API 29 depend on the phone's decoder; a failure gives an empty thumbnail, not a failed import.
- Lint may warn that `androidx.exifinterface` is preferred over `android.media.ExifInterface`; kept to avoid a new dependency.

## Status
Draft PR into `feat/pr3-storage`. Built and tested by GitHub CI only (no Android SDK in this session).
