# T-Bone Beta: What We Fixed and What We Found (November 8, 2026)

## Part 1: The Two UI Fixes (Already Done)

### 1. The Green Outline on Feed Tabs Was Getting Cut Off
**What was happening:**
- When you selected the "FOLLOWS" tab (or CLOCK, SUGGESTED, GLOBAL), the green rounded border around it was being clipped at the left edge of the screen.
- At the bottom, the thin line divider was drawing *on top* of the green border, hiding part of it.

**What we fixed:**
- Added a small margin around the tab strip (8 pixels on the sides, 4 pixels on top and bottom).
- Moved the bottom divider down by 4 pixels so it sits below the border instead of on top of it.
- Applied the same fix to the list screens (FollowListsScreen).

**Result:** The green bubble now fits perfectly inside the screen edges and isn't covered by the divider line.

### 2. The Profile Buttons Were Too Big and Bloated
**What was happening:**
- The buttons on the profile screen (UNFOLLOW/FOLLOW, BLOCK, SHARE) had oversized pill shapes.
- They were using default padding (22 pixels wide, 14 pixels tall) and a tall height (40 pixels), making them look bloated.

**What we fixed:**
- Reduced the padding inside the buttons to 10 pixels wide and 6 pixels tall.
- Lowered the minimum height to 28 pixels.
- Made the rounded corners smaller and tighter.
- Updated the onboarding screen and all profile button calls to use these smaller sizes.

**Result:** The profile buttons now look compact and proportional, matching the intended design.

**Build Status:**
- The code compiles, though the onboarding screen had a small mismatch with the button library (the library's text-button version didn't accept the new size parameters directly). We adjusted the code to match the library's rules.
- The privacy check script passed: no forbidden tracking or location permissions were found.

---

## Part 2: Why Feeds Don't Load on Android 8 (API 26) — The Audit
*(No code changes were made yet. Here is what we found and the options for fixing it.)*

### The Three Main Problems

**Problem 1: Old Security Certificates and Missing Modern Connections**
- **Where:** The connection code (`RelayConnection.kt`)
- **Why:** Android 8 uses an old list of trusted websites (CA bundle) and doesn't support the newest secure connection method (TLS 1.3). Modern websites (like Let's Encrypt) aren't trusted by Android 8 by default.
- **What happens:** The app tries to connect to the relay server, fails the security check, and keeps retrying every 5 to 60 seconds.

**Problem 2: The Tor Guard Starts Too Early**
- **Where:** The settings and network code (`AppSettings.kt`, `NetworkModule.kt`)
- **Why:** The app tries to set up the Tor proxy (127.0.0.1:9050) *before* it finishes reading the user's settings from the phone's storage. On older, slower phones (Android 8), this timing gap is bigger.
- **What happens:** The connection tries to use Tor before it knows if Tor is actually turned on, causing the feed to fail.

**Problem 3: Silent Errors and New Android Features**
- **Where:** Event verification (`Event.kt`) and media recording (`NoteEditorScreen.kt`)
- **Why:** The app hides errors when verifying events (it just says "false" instead of logging the error). Also, some code uses features from Android 11 (API 31) that Android 8 doesn't understand at all.
- **What happens:** Events drop silently without telling the user, and the app might crash on newer features if they are ever called on an old phone.

---

### How We Could Fix It (Options Only — No Changes Yet)

| Option | What We Would Do | Would It Break Anything? | Is It Safe? |
| :--- | :--- | :--- | :--- |
| **1. Add Modern Security Keys** | Bundle a small list of modern security certificates inside the app and force the app to use the older (but safe) TLS 1.2 connection on Android 8. | **Low risk:** Might conflict with very old phone-specific certificates, but fixes the connection. | **Yes, safer:** Ensures the app trusts the right websites on old phones. |
| **2. Add "Backwards Compatibility" Tools** | Turn on a tool (desugaring) that lets Android 8 understand newer code, and hide the Android 11 features from Android 8 phones. | **None:** This is standard practice and won't break anything. | **Yes, safer:** Prevents crashes. |
| **3. Wait for Settings Before Starting Tor** | Change the code so the app waits for the settings to finish loading *before* starting the Tor proxy. | **Medium risk:** Might make the app start up a tiny bit slower on all phones. | **Yes, safer:** Ensures the Tor setting is actually correct before connecting. |
| **4. Show Errors Instead of Hiding Them** | Change the event code to log errors instead of hiding them, and fix the BouncyCastle library conflict on old phones. | **Low risk:** Adds a little logging; the fix must be careful not to break newer phones. | **Yes, safer:** Helps us see what's going wrong instead of failing silently. |
| **5. Update the Security Config File** | Add a specific configuration file (`network_security_config.xml`) that tells Android 8 to trust modern websites. | **None:** This is the standard way to fix old Android versions. | **Yes, safer:** Maintains security while fixing the connection. |

### What We Recommend Doing Next (If You Want to Fix Task B)
1. **First:** Do **Option 1** (Security Keys) and **Option 5** (Config File) to fix the main connection problem.
2. **Second:** Do **Option 3** (Wait for Settings) to fix the Tor timing problem.
3. **Third:** Do **Option 2** (Compatibility Tools) and **Option 4** (Show Errors) to stop silent failures and crashes.

**Security Note:** None of these options would make the app less secure. In fact, Options 1 and 5 would make Android 8 *more* secure by ensuring it trusts the right websites.

---

## Summary
- **Task A (UI Fixes):** Completed. The green tab outline and profile buttons are now fixed.
- **Task B (Android 8 Audit):** Completed. We know exactly why feeds fail and have 5 safe options to fix it (none implemented yet).

**Files Changed:**
- `FeedScreen.kt` (Tab strip padding fixed)
- `FollowListsScreen.kt` (Tab strip padding fixed)
- `ProfileScreen.kt` (Button sizes fixed)
- `OnboardingScreen.kt` (Button sizes fixed, library match adjusted)
- `YouniversalButtons.kt` (Default button padding reduced)

**Full Report File:**
`/home/user/t-bone-beta-spec/TASK_A_B_REPORT.md` (Updated with this plain-language version)
