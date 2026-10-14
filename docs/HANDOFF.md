# Handoff log

Protocol (planning pack §9): before an engine switch, commit `wip(prN): <state>`, then add an entry at the top of this file. The incoming engine reads this file, `docs/STATUS.md` and `docs/plans/prN.md` first, then runs the tests before changing anything.

## Handoff 2026-10-14 22:40 Asia/Manila Claude Code→Codex/Claude Code (next session)
Branch: feat/pr1-scaffold @ 4693ad4 (+ this docs commit)
Done: All of PR1 scope in `docs/plans/pr1.md`. Gradle 9.8.1 wrapper, version catalog, 5 modules, Compose theme + empty NavHost, hardened manifest + no-backup rules, R8 + Log stripping, detekt + lint config, `scripts/check-permissions.sh`, `scripts/canary-internet.sh`, CI workflow. Verified locally: lint/detekt/tests/assemble green, gate passes, canary caught (see STATUS.md log).
In progress: nothing. Next is the PR1 review (Kiln), then Jan's OK. After plan approval, push the branch and open the PR, pasting the STATUS.md verification log as evidence. PR2 starts at `docs/plans/pr2.md` on `feat/pr2-crypto` cut from main.
Failing tests: none.
Decisions made (with reason):
- compileSdk/targetSdk 37: `android-37.0` is a final (non-preview) platform and AGP 9.4.1 builds against it cleanly.
- Gradle 9.8.1, not the local 9.3.1: AGP 9.4.1 won't load on 9.3.1.
- AGP 9 built-in Kotlin. KGP is pinned to 2.4.21 by a root `apply false` of `kotlin-android`. Modules must NOT apply `org.jetbrains.kotlin.android`.
- detekt 1.23.8 (stable), applied to every subproject from the root build. 2.0 is alpha.
- All core/feature modules are Android libraries. They will need Keystore, SAF, Media3 and WorkManager.
- `:feature:import` package is `.importer`, because `import` is a Kotlin keyword. This matches plan §2.
- Canary lives in a script so CI and local runs are identical. It always restores the manifest via `trap`. In CI it runs after the APK upload, so the artifact is never the canary build.
- Release APK is unsigned and no keystore is in the repo. Jan sideloads the debug APK artifact.
Do not touch: the `tools:node="remove"` permission lines (the canary's sed matches their exact text), `app/proguard-rules.pro` Log rule, and `local.properties` (gitignored, machine-specific).
Open questions for Jan/Kiln:
- Planning-pack §9 questions 1–5 are still open (target phone API level and SD filesystem, applicationId OK, PIN length, SD card not adopted, change-PIN in PR7).
- CI not yet run on GitHub. Confirm on the first push that `sdkmanager` installs `platforms;android-37.0` on ubuntu-latest.
