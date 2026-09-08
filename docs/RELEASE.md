# Release process — this fork

Every release is built by **GitHub Actions** and published as a **GitHub
Release with APKs attached**. Nothing is uploaded anywhere else.

## One-time setup (repo owner, ~10 minutes)

### 1. Create the new GitHub repo

1. On GitHub: **New repository** → name it e.g. `T-Bone-Fork` (public, no
   README/license/gitignore — this project already has them).
2. In this folder:
   ```bash
   ./scripts/setup-remote.sh git@github.com:YOURUSER/T-Bone-Fork.git
   # or: ./scripts/setup-remote.sh https://github.com/YOURUSER/T-Bone-Fork.git
   git push -u origin main
   ```

### 2. (Recommended) Add a release signing key

Without this step, release APKs are signed with the debug key (fine for
sideloading/tests, clearly labeled in the release notes). For stable
install-over-update identity, add your own key:

```bash
# 1. Generate once (keep the .jks + passwords in a password manager!)
keytool -genkeypair -alias tbone-fork -keyalg RSA -keysize 4096 \
  -validity 9125 -keystore fork-release.jks

# 2. Base64 it for GitHub Secrets
base64 -w0 fork-release.jks > fork-release.jks.b64   # Linux
# base64 -i fork-release.jks | tr -d '\n' > fork-release.jks.b64  # macOS
```

Then in **GitHub → repo → Settings → Secrets and variables → Actions**,
add four repository secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `fork-release.jks.b64` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | the alias (e.g. `tbone-fork`) |
| `KEY_PASSWORD` | the key password |

Local builds use `local.properties` instead (never committed):

```properties
KEYSTORE_PATH=/abs/path/to/fork-release.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=tbone-fork
KEY_PASSWORD=...
```

> **Back up `fork-release.jks` + passwords.** Lose them and you can never
> publish an update that installs over the old one — users would have to
> uninstall/reinstall (and both apps use different package IDs, so the
> original T-Bone is never touched either way).

## Making a release (every time)

```bash
# 1. Bump version (updates versionCode + versionName + CHANGELOG stub)
./scripts/make-release.sh 0.3.37

# 2. Edit CHANGELOG.md release notes, commit
git add -A && git commit -m "Release 0.3.37"

# 3. Tag + push — CI does the rest
git tag v0.3.37
git push origin main --tags
```

Within a few minutes, **Releases** on GitHub will contain:

- `T-Bone-fork-<version>-release.apk` (signed; your key if configured,
  debug key otherwise — the notes say which)
- `T-Bone-fork-<version>-debug.apk` (always debug-signed, for testers)
- SHA-256 checksums for both
- Auto-generated notes + the CHANGELOG entry

### What CI does on a tag push (`v*`)

1. Checks out the tag.
2. Runs `scripts/privacy-check.sh` — **release aborts if it fails**.
3. Builds `:app:assembleRelease` and `:app:assembleDebug`.
4. If `KEYSTORE_BASE64` secret exists, decodes it and signs release with your
   key; otherwise uses the debug key and labels the APK accordingly.
5. Creates/updates the GitHub Release for the tag and uploads both APKs +
   `.sha256` files.

## Rules

- **Tags are the releases.** Only tags matching `v*` (e.g. `v0.3.37`) trigger
  the release workflow. Branch pushes only run the privacy check.
- **Never commit** `local.properties`, `*.jks`, `*.keystore`, or
  `*.b64` key material — `.gitignore` already excludes them.
- **Versioning:** `versionName` = user-visible (`0.3.37`), `versionCode` =
  integer that always increases (Play-installer ordering + update checks).
  `make-release.sh` bumps `versionCode` by 1 automatically.
- If the privacy check ever fails on a release tag: delete the tag, fix the
  violation, re-tag. Do not bypass CI.
