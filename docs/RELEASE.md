# Releases

A pushed version tag (`v*`) triggers GitHub Actions to run the privacy check and tests, build the Android **release variant**, and publish one release APK with its SHA-256 checksum. Debug APKs are not built or attached to releases. Debug builds remain available locally for development.

## Version identity (single source of truth)

`version.properties` at the repository root holds the only release literals:

```properties
versionCode=68
versionName=0.5.2
```

`app/build.gradle.kts` reads those values into `versionCode`/`versionName`; Android generates `BuildConfig.VERSION_CODE` and `BuildConfig.VERSION_NAME` from them. The Settings screen, Update Center, update comparisons, and downloaded-APK verification all use `BuildConfig`, so no in-app screen hardcodes a separate release string. `scripts/verify-release-version.sh` runs on PRs and tagged builds; on `v*` tags it also requires `v<versionName>` to match exactly. This prevents the 0.5.1 mistake, where the tag said 0.5.1 but the APK still reported 0.5.0 / versionCode 67.

Use the helper for every release — it increments both values together:

```bash
./scripts/make-release.sh 0.5.3
```

## One-time setup: release signing key

Configure a stable signing key so users can install updates over an existing release. Without it, CI can still build the release variant, but it falls back to the runner's debug signing key; that is not a debug APK, but it is not a stable release identity.

Generate a key and encode it for GitHub Actions:

```bash
keytool -genkeypair -alias tbone-fork -keyalg RSA -keysize 4096 \
  -validity 9125 -keystore fork-release.jks
base64 -w0 fork-release.jks > fork-release.jks.b64   # Linux
# macOS: base64 -i fork-release.jks | tr -d '\n' > fork-release.jks.b64
```

In **GitHub → repository → Settings → Secrets and variables → Actions**, add:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | Contents of `fork-release.jks.b64` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias, such as `tbone-fork` |
| `KEY_PASSWORD` | Key password |

Keep the keystore and passwords backed up in a secure place. Never commit them. Local builds can use the same values in `local.properties` (which is git-ignored).

## Publish a release

1. Bump `version.properties` and stage release notes (replace with the new version):
   ```bash
   ./scripts/make-release.sh 0.5.2
   ./scripts/verify-release-version.sh
   ```
2. Edit `CHANGELOG.md`, then commit `version.properties`, Gradle wiring, and release notes.
3. Create the matching annotated tag and push:
   ```bash
   git tag -a v0.5.2 -m "Release v0.5.2"
   git push origin main --tags
   ```
4. Check the **Release APK** workflow in GitHub Actions. When it succeeds, the GitHub Release contains:
   - `T-Bone-fork-<version>-release.apk`
   - `SHA256SUMS.txt`

Verify the APK after downloading it:

```bash
sha256sum -c SHA256SUMS.txt
```

## What the release workflow does

1. Checks out the tagged commit, runs `scripts/privacy-check.sh`, and verifies that `v<versionName>`, `version.properties`, Gradle wiring, and `CHANGELOG.md` all agree.
2. Sets up JDK 17 and the Android SDK.
3. Builds `:app:assembleRelease` only, then runs the unit tests as a blocking gate.
4. Packages the release APK and its checksum, then publishes those files to the GitHub Release.

Only tags matching `v*` trigger this workflow. The privacy check also runs in CI for pull requests. If a release build cannot find a signing key, its notes identify that the release variant was signed with the fallback debug key; configure a repository release key for stable install-over-update signing.
