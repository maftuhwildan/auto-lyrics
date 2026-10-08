# Auto Lyrics — Release Guide

This is the release checklist for maintainers. The GitHub Actions workflow can publish a GitHub Release from either a dedicated `Release vX.Y.Z` commit on `main` or a matching `vX.Y.Z` tag push.

The **recommended path** is the atomic release commit. It updates the Android version and triggers the release in one push, which avoids a separate manual tag step.

## Release invariant

The following values must describe the same release:

```text
app/build.gradle.kts: versionName = "X.Y.Z"
Git tag:              vX.Y.Z
Generated APK:        auto-lyrics-X.Y.Z.apk
```

`versionCode` must also increase for every new Android release.

The workflow derives the tag name and APK filename from `versionName`. Keep the release commit message, `versionName`, generated tag, and APK version aligned.

## Release triggers

`.github/workflows/build.yml` treats either of these as a release build:

```text
1. push a tag matching v*
2. push to main with a head commit message starting with "Release v"
```

Examples:

```text
Release v1.12.0     → release build
Bump version to 1.12.0 → ordinary main CI build
```

For the main-commit path, `softprops/action-gh-release` creates the unused tag derived from `versionName` and targets the release commit SHA. The workflow rejects the release before publishing if that tag already exists remotely.

For the tag path, the workflow validates that the pushed tag exactly matches `v${versionName}`.

## Optional GitHub Actions secrets

PetitLyrics is optional for release builds. Configure all four repository secrets to enable it:

```text
PETITLYRICS_USER_ID
PETITLYRICS_APP_NAME
PETITLYRICS_PKG_NAME
PETITLYRICS_CLIENT_APP_ID
```

When all four values are present, the workflow includes them in the release APK and reports that PetitLyrics is enabled in the job summary. If any value is missing, the workflow clears the entire set, reports that PetitLyrics is disabled in the job summary and workflow notice, then continues building and publishing the release without that provider. It does not print secret values.

PR and ordinary `main` builds intentionally do not receive these values. Those CI artifacts build with PetitLyrics disabled unless another non-secret fallback is explicitly present in the checkout.

## Recommended atomic release procedure

Assume the next release is `X.Y.Z`.

### 1. Update local main

```powershell
git switch main
git pull origin main
```

### 2. Bump Android version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = <previous + 1>
versionName = "X.Y.Z"
```

Check the diff:

```powershell
git diff -- app/build.gradle.kts
```

### 3. Commit with the release trigger message

The commit message must start with `Release v`:

```powershell
git add app/build.gradle.kts
git commit -m "Release vX.Y.Z"
git push origin main
```

That single push starts the release workflow. Do **not** create the tag manually when using this path.

Before pushing, verify that the derived tag is unused:

```powershell
git ls-remote --tags origin refs/tags/vX.Y.Z
```

The command should print nothing. Release CI performs the same remote-tag check and fails before building/publishing if `vX.Y.Z` already exists.

### 4. Verify the workflow

The release job should:

1. read `versionName`,
2. validate tag/version consistency and reject tag reuse,
3. enable PetitLyrics only when all four optional secrets are configured (otherwise report that it is disabled and continue),
4. run unit tests,
5. run lint,
6. build the minified release APK,
7. copy it to `auto-lyrics-X.Y.Z.apk`,
8. upload the workflow artifact,
9. create tag `vX.Y.Z`,
10. publish GitHub Release `vX.Y.Z`,
11. attach the APK and generate release notes.

This is the path used for `v1.12.0`.

## Alternative tag-driven release procedure

A traditional tag push remains supported.

First commit the version bump to `main` with a normal non-release message and allow ordinary CI to pass:

```powershell
git add app/build.gradle.kts
git commit -m "Bump version to X.Y.Z"
git push origin main
```

Verify:

```powershell
Select-String -Path .\app\build.gradle.kts -Pattern 'versionCode|versionName'
```

Then create the **exact matching tag**:

```powershell
git tag vX.Y.Z
git push origin vX.Y.Z
```

For this path, a mismatch between the pushed tag and `versionName` fails `Validate release version`.

Do not reuse an existing version tag.

## What release CI runs

For either release trigger, the Linux `build` job performs:

1. checkout,
2. JDK 17 / Gradle setup,
3. read `versionName`,
4. validate tag/version consistency and reject tag reuse for the main-commit path,
5. configure the optional PetitLyrics provider when all four secrets are present, otherwise report it disabled and continue,
6. `testDebugUnitTest`,
7. `lintDebug`,
8. `assembleRelease`,
9. copy the APK to:

   ```text
   app/build/outputs/distribution/auto-lyrics-X.Y.Z.apk
   ```

10. upload the APK as a workflow artifact,
11. create/update GitHub Release `vX.Y.Z`,
12. attach the APK and generate release notes.

The release APK is minified and signed using the repository's configured signing setup.

The Windows regression job does **not** run on release pushes. It runs only for pull requests and manual `workflow_dispatch`. Release candidates should therefore already have passed Windows regression through the PR that introduced the code, or via a manual workflow run when needed.

## Post-release verification

Open the new GitHub Release and verify all of the following:

- release tag is `vX.Y.Z`,
- release points to the intended commit,
- attached file is `auto-lyrics-X.Y.Z.apk`,
- release is not draft/prerelease unless intentionally requested,
- Actions run completed successfully,
- the job summary confirms whether PetitLyrics was enabled or disabled as intended,
- unit tests passed,
- lint passed,
- release APK build passed,
- release notes cover the intended PR range.

For a functional smoke test, install the release APK and verify:

1. With Android Auto disconnected and Auto Lyrics closed, changing tracks in another media app does not start new provider resolution.
2. Opening Auto Lyrics on the phone resolves the current media session.
3. Connecting Android Auto activates lyric resolution without keeping the phone UI open.
4. LRCLIB lookup works on a known synchronized track.
5. Musixmatch lookup works on a known supported track.
6. PetitLyrics works on a known supported track when release credentials are present.
7. Karaoke mode can select a real WORD_SYNC candidate when it is near-equivalent to the standard winner.
8. SyncLRC can contribute Enhanced-LRC timing on a known available track, while synced/plain-only responses remain ignored by that provider.
9. Android Auto shows `Lyrics | Sync | More`.
10. Current lyrics display the `▶` marker.
11. Lyrics tab row count is 5 without translation / 3 with translation.
12. Sync remains a fixed 3-row lyric preview.
13. ±50 ms AA offset controls still update the view.

## Local release-like build

A local `.env` can be used for provider configuration, but it does not reproduce GitHub's repository-secret injection exactly.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleRelease
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

## If a release was created with the wrong version

Prefer creating a new corrected release version rather than rewriting an already published release/tag.

For example:

1. bump `versionCode` and `versionName` correctly,
2. commit the corrected version,
3. publish the next unused release version,
4. verify the generated tag and APK filename before considering the release complete.

Avoid force-moving published release tags unless there is a specific reason and all consumers of that tag are understood.

## Related documentation

- [README.md](README.md) — installation, user-facing features, local build basics.
- [DEVELOPMENT.md](DEVELOPMENT.md) — architecture, provider demand, resolver, cache, provider formats, Android Auto implementation, regression checklist.
