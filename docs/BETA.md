# Beta builds

The `beta` branch is a testing integration branch. Its beta build has Android
application ID `social.tbone.fork.beta` and label `T-bone Beta`; production
remains `social.tbone.fork`. They install side by side with separate app data.
Beta's built-in updater is disabled; beta APKs are Actions artifacts, not
GitHub Releases, so the production GitHub release feed is unchanged. Do not
point Obtanium's production source at beta artifacts or publish beta APKs as
normal GitHub Releases. Obtanium settings and other update sources are managed
by the user, and their behavior can vary.

## Signing (required)

The beta workflow uses the existing `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, and `KEY_PASSWORD` repository Actions secrets described in
[RELEASE.md](RELEASE.md). It fails rather than silently using an ephemeral
debug key. Keep the keystore and passwords securely backed up: every beta
update must use the same signing key to install over an existing beta. Signing
both apps with the same key does not make their different package IDs update
each other. Never commit keys or passwords. Use the existing production key if
production is already installed; replacing it breaks production updates.

## Update beta from main and request a build

```bash
git fetch origin
git switch beta
git pull --ff-only origin beta
git merge origin/main       # resolve conflicts if needed
git push origin beta
# Request a build of the resulting branch, even if no source changed:
git commit --allow-empty -m 'Build beta [build beta]'
git push origin beta
```

Alternatively include `[build beta]` in the final commit message of a normal
beta push. Download the zip from the **Beta APK** run under GitHub Actions,
unzip it, verify `sha256sum -c SHA256SUMS.txt`, and install the APK. Actions
artifacts expire after 90 days; download and retain anything you need. A beta
with a lower versionCode than the installed beta cannot normally be installed
as an update: bump versionCode before building, or uninstall beta (losing its
data). The beta data is separate from production data.

GitHub only exposes **Run workflow** when the workflow file exists on the
default branch. After these infrastructure changes are merged to main, you can
also use Actions → Beta APK → Run workflow and select branch `beta`. The job
refuses to build other branches. Until then use the push method above.

## Promote beta to main

Review and test beta, then merge it to main using a pull request or:

```bash
git fetch origin
git switch main
git pull --ff-only origin main
git merge origin/beta
git push origin main
```

The beta variant and workflow can safely live on main: ordinary main release
builds still use `assembleRelease`, the production package ID, and the normal
updater. Promotion alone does not release an APK. Follow [RELEASE.md](RELEASE.md)
to bump the version, commit and push a matching `v*` tag. Review signing
configuration first: the existing production workflow permits a debug-key
fallback if secrets are missing, which cannot update an existing installation
signed by a different key. Beta artifacts are not published as releases.
