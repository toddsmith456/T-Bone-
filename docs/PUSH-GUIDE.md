# Push guide (beginner-friendly) — getting this fork onto your GitHub

This guide explains **where** commands run and walks through every step in
detail. No steps are skipped.

## Where do commands run?

Commands run in a **terminal** (a text window where you type commands) on a
computer that has:

1. The `T-Bone-Fork` folder, and
2. **Git** installed.

You have **two options**:

### Option A — I (the AI) do it for you (easiest)

Stay in this chat. You only do Step 1 below (create the empty repo on
github.com), then paste the repo URL here. If the repo needs authentication
(it will), also paste a GitHub **Personal Access Token** (Step 2 explains how
to make one — you can delete it right after). I run everything in this
workspace and push for you.

### Option B — you do it on your own computer

1. **Download** the `T-Bone-Fork` folder from this workspace to your computer.
2. **Install Git** if you don't have it:
   - Windows: install **Git for Windows** from <https://git-scm.com> (this also
     gives you "Git Bash", a terminal that can run the `.sh` scripts).
   - macOS: open the **Terminal** app and run `git --version` — macOS will
     offer to install the developer tools. Or `brew install git`.
   - Linux (Debian/Ubuntu): `sudo apt install git`.
3. **Open a terminal in the folder:**
   - Windows: open the `T-Bone-Fork` folder in Explorer, right-click empty
     space → **"Open Git Bash Here"** (use Git Bash, not PowerShell, for the
     `.sh` scripts).
   - macOS: right-click the folder in Finder → **"New Terminal at Folder"**
     (or drag the folder onto the Terminal window after typing `cd `).
   - Linux: right-click → **"Open in Terminal"**, or `cd ~/path/to/T-Bone-Fork`.

Then follow Steps 1–4 below.

---

## Step 1 — Create the empty repo on GitHub (~2 minutes)

This happens **in your web browser**, not the terminal.

1. Go to <https://github.com> and sign in.
2. Click the **"+"** in the top-right corner → **"New repository"**.
3. **Owner:** your account (e.g. `5ToddSmith5`).
4. **Repository name:** `T-Bone-Fork` (or any name you like).
5. **Description** (optional but nice):
   `Privacy fork of the T-Bone Nostr client — side-by-side install, no Google services, no tracking.`
6. **Public or Private?**
   - **Public** (recommended): matches the open-source MIT license, and GitHub
     Actions build minutes are free/unlimited.
   - **Private** also works: Actions has a free monthly minutes quota, which is
     plenty for occasional APK builds.
7. ⚠️ **IMPORTANT — leave all three boxes UNCHECKED:**
   - [ ] Add a README file
   - [ ] Add .gitignore
   - [ ] Choose a license

   The repo must be **completely empty**, because your `T-Bone-Fork` folder
   already contains a README, .gitignore, and LICENSE. If GitHub creates any
   of these, your first push will conflict.
8. Click **"Create repository"**.
9. GitHub shows a page titled "Quick setup". Under **HTTPS**, copy the URL.
   It looks like: `https://github.com/YOURNAME/T-Bone-Fork.git`
   (replace `YOURNAME` with your GitHub username). **Keep this URL — you need
   it in Step 2.**

---

## Step 2 — Connect the folder and push it (~3 minutes)

This happens **in the terminal**, inside the `T-Bone-Fork` folder.

1. Make sure your terminal is "in" the folder. Type `ls` (macOS/Linux/Git Bash)
   and you should see `FORK.md`, `README.md`, `app`, `scripts`, etc.
   If not, `cd` into it, e.g.:
   ```bash
   cd ~/Downloads/T-Bone-Fork
   ```
2. Run the setup script with **your** URL from Step 1:
   ```bash
   ./scripts/setup-remote.sh https://github.com/YOURNAME/T-Bone-Fork.git
   ```
   (On Windows, run this in **Git Bash**, not PowerShell.)
3. **What the script does** (so it's not magic):
   - `git remote add origin <your-url>` — saves your GitHub repo's address
     under the nickname `origin`.
   - `git branch -M main` — names the current branch `main`.
   - `git push -u origin main` — uploads all ~222 files to GitHub.
4. **If you'd rather type the commands yourself** (identical result):
   ```bash
   git remote add origin https://github.com/YOURNAME/T-Bone-Fork.git
   git branch -M main
   git push -u origin main
   ```
5. **Authentication:** GitHub will ask for a username and password.
   - Username = your GitHub username.
   - Password = **NOT your GitHub password** (GitHub rejects real passwords
     here). It must be a **Personal Access Token**:
     1. GitHub → your avatar (top-right) → **Settings** → (left sidebar)
        **Developer settings** → **Personal access tokens** →
        **Tokens (classic)** → **Generate new token (classic)**.
     2. Note: `push T-Bone fork`. Expiration: 7 days is fine (you can delete
        it after pushing).
     3. Check the box **`repo`** (full control of private repositories — also
        covers public).
     4. Click **Generate token**, then **copy it immediately** (it starts with
        `ghp_...` — GitHub never shows it again).
     5. Paste it as the password in the terminal. (Typing is invisible — that's
        normal. Press Enter.)
   - Alternative: use **SSH** instead (`git@github.com:YOURNAME/T-Bone-Fork.git`)
     if you already have SSH keys on GitHub — no token needed then.
6. **Verify:** refresh your repo page on github.com — you should now see all the
   files (`README.md` preview, `FORK.md`, `app/`, `docs/`, `scripts/`…).

**Common problems:**

| Error | Fix |
|---|---|
| `remote origin already exists` | Run `git remote set-url origin <your-url>` then `git push -u origin main` |
| `failed to push… fetch first` / `non-fast-forward` | Your GitHub repo wasn't empty (README/license created). Easiest: delete the repo on GitHub and redo Step 1 empty. |
| `Authentication failed` | You used your real password. Use a Personal Access Token (see above). |
| `./scripts/setup-remote.sh: Permission denied` | Run `chmod +x scripts/*.sh` first, then retry. |

---

## Step 3 — Test the automatic release with a throwaway tag (~10 minutes, mostly waiting)

This happens **in the terminal** (to create the tag) and then **in the browser**
(to watch the build).

**What you're doing:** a "tag" is a named bookmark on your code (e.g.
`v0.3.36-fork1`). Our automation is configured so that **any tag starting with
`v` triggers a cloud build** that compiles both APKs and attaches them to a
GitHub Release. This test proves the pipeline works before you cut real
releases.

1. In the terminal (inside `T-Bone-Fork`):
   ```bash
   git tag v0.3.36-fork1
   git push origin v0.3.36-fork1
   ```
2. In the browser, open your repo → click the **Actions** tab (top bar).
   You'll see a run called **"Release APKs"** with a yellow spinner.
3. Click it to watch the steps live: privacy gate → JDK setup → build →
   rename/checksums → publish release. First builds take **~5–15 minutes**
   (Gradle downloads dependencies; later builds are faster).
4. When it turns **green ✓**, click your repo's **Releases** link (right
   sidebar of the main repo page, or add `/releases` to the URL). You'll see
   release `v0.3.36-fork1` containing:
   - `T-Bone-fork-v0.3.36-fork1-release.apk` (main APK)
   - `T-Bone-fork-v0.3.36-fork1-debug.apk` (tester build)
   - `SHA256SUMS.txt` (checksums — verify with `sha256sum -c SHA256SUMS.txt`)
5. **Try it on your phone:** download the `-release.apk`, sideload it — it
   installs **alongside** the original T-Bone without replacing it.
6. **Clean up the test** (optional): on the Releases page → delete the test
   release, then delete the tag:
   ```bash
   git tag -d v0.3.36-fork1
   git push origin :refs/tags/v0.3.36-fork1
   ```
   Real releases later use `./scripts/make-release.sh 0.3.37` (see
   `docs/RELEASE.md`).

**If the run turns red ✗:** click the failed job and read the log.
- Privacy-gate failure: something tracking-related was added — remove it
  (won't happen on the clean fork).
- Flaky network/Gradle error: **Re-run jobs** button (top-right) usually fixes it.

---

## Step 4 — Add your signing key so updates install cleanly (one-time, ~10 minutes)

This happens **in the terminal** (to create the key) and **in the browser**
(to store it as secrets).

**Why:** Android decides whether an update belongs to an installed app by
checking its **signature**. Without your own key, CI signs each build with a
fresh debug key — meaning every update would force users to **uninstall first
(losing data)**. With your key stored in GitHub Secrets, every release has one
stable identity and updates install cleanly over previous versions.

1. **Generate the key once** (terminal — `keytool` ships with Java/Android
   Studio; on Windows run it from Android Studio's terminal or
   `"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"`):
   ```bash
   keytool -genkeypair -alias tbone-fork -keyalg RSA -keysize 4096 \
     -validity 9125 -keystore fork-release.jks
   ```
   Answer the prompts (passwords + name/organization — anything is fine).
   `-validity 9125` = ~25 years, so updates work for decades.
2. **Base64-encode it** for GitHub Secrets (terminal):
   - Linux / Git Bash: `base64 -w0 fork-release.jks > fork-release.jks.b64`
   - macOS: `base64 -i fork-release.jks | tr -d '\n' > fork-release.jks.b64`
   - Windows PowerShell:
     ```powershell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("fork-release.jks")) | Out-File fork-release.jks.b64 -NoNewline -Encoding ascii
     ```
3. **Add the 4 secrets** (browser): repo page → **Settings** → (left sidebar)
   **Secrets and variables** → **Actions** → **New repository secret** (repeat
   4 times, names must match **exactly**):

   | Secret name | Value |
   |---|---|
   | `KEYSTORE_BASE64` | entire contents of `fork-release.jks.b64` (open it in a text editor, copy all) |
   | `KEYSTORE_PASSWORD` | the keystore password you typed in step 1 |
   | `KEY_ALIAS` | `tbone-fork` |
   | `KEY_PASSWORD` | the key password you typed in step 1 |

4. ⚠️ **Back up `fork-release.jks` + both passwords** in a password manager
   and/or a USB stick. If you lose them, you can **never** publish an update
   that installs over the old ones — every user would have to reinstall.
   (The original T-Bone app is unaffected regardless — different package ID.)
   Never commit these files — `.gitignore` already blocks `*.jks`/`*.b64`.
5. **Verify:** push any new `v*` tag — the release notes will now say
   *"Signed with the repo owner's **release key**"* instead of "debug key".

---

## Quick-reference (all commands in one place)

```bash
cd path/to/T-Bone-Fork                                  # terminal in the folder
./scripts/setup-remote.sh https://github.com/YOURNAME/T-Bone-Fork.git
git tag v0.3.36-fork1 && git push origin v0.3.36-fork1   # test auto-release
# ...later, real releases:
./scripts/make-release.sh 0.3.37
# edit CHANGELOG.md, then:
git add -A && git commit -m "Release 0.3.37"
git tag v0.3.37 && git push origin main --tags
```
