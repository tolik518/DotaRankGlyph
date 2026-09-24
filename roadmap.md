# Roadmap

Decisions from the review on 2026-09-25. Items are grouped by kind; the suggested build order is at the end.

## Fixes

### 1. OpenDota rate-limit guard (done)
Stop sending requests after a 429 until OpenDota's counter resets; today the toy retries about once a minute while the cached rank is stale.

What OpenDota does (from its server code, `odota/core` `config.ts` / `svc/web.ts`, and live headers):
- Without an API key: **60 requests/minute** and **3000/day**, counted **per IP address**. Rejected requests count too.
- The minute window resets at the start of each UTC minute, the daily one at **00:00 UTC**.
- 429 bodies: `"minute rate limit exceeded"` or `"daily api limit exceeded"`. No `Retry-After` header.
- Every response carries `X-Rate-Limit-Remaining-Minute` and `X-Rate-Limit-Remaining-Day`.
- No permanent ban in their code, only 429 until the reset.

Plan:
- One guard shared by the app and the toy, persisted in `SharedPreferences` so it survives restarts.
- Minute limit hit → no requests until the next minute + a few seconds.
- Daily limit hit → no requests until 00:00 UTC; the app shows "OpenDota daily limit reached, retrying at HH:MM" (local time).
- `X-Rate-Limit-Remaining-Day` low (below ~50) → automatic refreshes pause until the reset; manual checks still allowed.
- Network errors / 5xx → automatic retries back off (1, 2, 4, 8 … min, capped at the refresh interval).
- Manual actions (button, long-press) also respect an active 429 block.
- Tests with `MockHttpServer`: both 429 bodies, the remaining-day header, backoff timing (with an injectable clock).

### 2. `RankRepository` (done)
One process-wide owner of fetching, instead of separate code in `MainActivity` and `DotaRankToyService`.
- Owns `OpenDotaClient`, `RankStore`, the rate-limit guard (fix 1), and `ReloadShake` start/finish.
- One in-flight request per account (a second caller joins it instead of sending another request).
- One shared minimum gap between requests (replaces the toy's 5 s gap; the app button keeps its 5 s cooldown as UI).
- Listeners get notified when the rank or the last error changes, so the app updates after background refreshes.
- Fixes today's issues: saving a new account sends two requests (app + toy); the app doesn't update after the toy refreshes; the toy's gap resets whenever the Glyph reconnects.
- The refresh decisions (stale / forced / too soon / blocked) move into a plain class so they can be unit-tested.
- Move `ReloadShake` out of the `toy` package while at it (the app uses it too).

### 3. Glyph service reconnects (done, not provoked on the device)
- If Nothing's Glyph service reconnects, `onMatrixConnected` runs again and creates a second executor without closing the first.
- `onServiceDisconnected` is only logged, so the toy keeps sending frames to a dead connection.
- Fix: make connect idempotent (reuse or close the executor), and handle disconnect like an unbind (stop animations, release the shake).
- Also give each reload an ID in `ReloadShake`, so a late `finish` after the 30 s safety stop can't end a newer shake.

### 4. Device checks
- **Always-on (AOD) toy: not tested yet, not a priority.** When tested: pick Dota Rank as the Always-on Glyph Toy, lock the phone for ~2 min, look for `DotaRankToy: event aod` in logcat, and check that it redraws without fetching unless the rank is stale.
- Glyph long-press after the shared-shake refactor: works (confirmed).
- LED brightness: keep the current values (confirmed fine on the device).

## Quality of life

### 1. "Updated" time and last error in the app (done)
- Status line such as "Updated 12 min ago", plus the last error if the latest refresh failed ("OpenDota unreachable, showing the rank from 14:02").
- The Glyph never shows errors, so this is the only place to see that the rank is stale.
- Store the last error and its time with the cached rank; clear it on the next success. Comes naturally from the repository's listeners (fix 2).

### 2. Share into the app, with a setting to turn it off (done)
- "Share" a Steam profile from the Steam app or a browser → pick Dota Rank Glyph → the account is filled in (and checked).
- Implementation: an `ACTION_SEND` (`text/plain`) intent filter on an `activity-alias`; the text goes through `PlayerInput.parse`, so every accepted format works.
- Setting "Show in the share menu" (on by default): turning it off disables the alias with `PackageManager.setComponentEnabledSetting`, which removes the app from the share sheet.

### 3. Paste button (done)
- Next to the input field; reads the clipboard only when tapped (no background clipboard access, so Android shows no warning).

### 4. Suggest adding the Glyph Toy (done)
- After the first successful check, show a one-time prompt with a button that opens Nothing's Glyph Toys manager (`com.nothing.thirdparty/.matrix.toys.manager.ToysManagerActivity`, the intent Nothing recommends).
- Don't show it again once it was used or dismissed. The existing "Add to Glyph Toys" button stays.
- Also not shown once the toy has been on the Glyph (it must have been added then).

### 5. Recent accounts (done)
- Keep the last ~5 accounts with their persona names; tap one to switch (no re-entering IDs).
- Switching uses the normal save & check path (cooldown, guard). Long-press removes an entry.

### 6. App icon, optionally the current medal (done)
- A proper launcher icon (adaptive icon, with a themed-icon layer) instead of the toy preview drawing: the Immortal
  medal as Glyph Matrix dots, generated from the medal art by `tools/make_icons.py`.
- Setting "App icon shows my medal" (off by default): the launcher icon changes to the current medal after each refresh.
- Android can't set arbitrary launcher bitmaps. The usual approach is one `activity-alias` per icon (8 medals + uncalibrated), enabling one and disabling the others with `PackageManager.setComponentEnabledSetting`. Stars can't be shown (that would need 40 aliases).
  Immortal and uncalibrated use the default icon, so there are 8 aliases (default + Herald … Divine).
- To check on the Nothing launcher: whether it refreshes the icon promptly, and whether switching aliases removes home-screen shortcuts or restarts the app. Only switch when the medal actually changes.

## Features

### 1. Rank-change animation on the Glyph (done)
- When a refresh brings a different rank, play an animation instead of just swapping the frame:
  - **New star:** the new pip fades or blinks in.
  - **New medal (tier up):** a flash or sparkle over the new medal.
  - **Rank down:** a quieter version (e.g. the lost pip fades out).
  - **Immortal leaderboard place changed:** the number rolls to the new value, then blinks twice.
  - Anything else (lower medal, back to uncalibrated): cross-fade.
- Compare the previous cached rank with the new one inside the repository (fix 2), which notifies with old and new state.
- If the toy isn't showing at that moment, store a "pending celebration" and play it the next time the toy is selected.
- The app preview plays the same animation (like the shared reload shake).
- Unit tests for the old/new state comparison and the animation frames (all frames inside the LED circle).

## Parked ideas (keep in mind, not planned)
- **Last match W/L:** OpenDota's recent-matches endpoint; could be an alternative display (short `W` / `L` or a win streak).
- **Friends rotation:** the toy cycles through saved players automatically (long-press is already used for refresh).
- **Rank-change notification:** optional Android notification when the rank changes.
- **Sign in with Steam** (OpenID 2.0): fills in the ID without pasting. The sign-in returns
  `https://steamcommunity.com/openid/id/<SteamID64>`, which `PlayerInput.parse` already understands as a SteamID64;
  needs a browser redirect back into the app. Later.

## Other cleanup (not decided yet)
- Move user-facing texts from code into `strings.xml` (translations; the phone is set to German).
- Split `MainActivity` (layout code, settings sections) once the repository takes over fetching.
- Remove leftovers: `DISABLE_SYSTEM_TIMEOUT`, the unused `!` glyph in `PixelFont`, and Glyph event logging in release builds.

## Suggested build order
1. Fixes 2 + 1 + 3 together (`RankRepository` with the guard; reconnect handling). They touch the same code and the rest builds on them.
2. QoL 1 ("updated" / last error), which uses the repository's listeners.
3. QoL 3, 5, 2, 4 (paste, recent accounts, share, toy suggestion): small and independent.
4. Feature 1 (rank-change animation).
5. QoL 6 (app icon): needs checking on the Nothing launcher.
