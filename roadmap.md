# Roadmap

Open items only. Everything decided in the 2026-09-25 review is built; see the git history and README.

## Device checks

- **Always-on (AOD) toy: not tested yet, not a priority.** When tested: pick Dota Rank as the Always-on Glyph Toy,
  lock the phone for ~2 min, look for `DotaRankToy: event aod` in logcat, and check that it redraws without fetching
  unless the rank is stale.
- **Glyph service reconnect:** handled in `GlyphMatrixService` (one disconnect per connect, no frames to a dead
  connection), but never provoked on the device.
- **"App icon shows my medal" on the Nothing launcher:** the alias switch works (checked with `adb`); still to see
  whether the launcher refreshes the icon promptly and whether a home-screen shortcut survives the switch.
- LED brightness: keep the current values (confirmed fine on the device).

## Parked ideas (keep in mind, not planned)

- **Last match W/L:** OpenDota's recent-matches endpoint; could be an alternative display (short `W` / `L` or a win streak).
- **Friends rotation:** the toy cycles through saved players automatically (long-press is already used for refresh).
- **Rank-change notification:** optional Android notification when the rank changes.
- **Sign in with Steam** (OpenID 2.0): fills in the ID without pasting. The sign-in returns
  `https://steamcommunity.com/openid/id/<SteamID64>`, which `PlayerInput.parse` already understands as a SteamID64;
  needs a browser redirect back into the app. Later.

## Other cleanup (not decided yet)

- Move user-facing texts from code into `strings.xml` (translations; the phone is set to German).
- Split `MainActivity` (layout code, settings sections); it has grown a lot.
- Remove leftovers: `DISABLE_SYSTEM_TIMEOUT`, the unused `!` glyph in `PixelFont`, and Glyph event logging in release builds.
