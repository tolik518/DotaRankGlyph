# Roadmap

Open items. Decisions from the second review on 2026-09-25; the suggested build order is at the end.

## Quality of life

### 1. Replay the rank-change animation (debug builds only)

- Long-press the app preview to play the animations with sample ranks (star up, star down, tier up, tier down,
  Immortal place roll), on the preview and the Glyph if the toy is showing.
- Only in debug builds (`BuildConfig.DEBUG`, needs `buildFeatures.buildConfig = true`); not for end users.

## Features

### 1. Home-screen widget (must)

- The current medal (with stars / Immortal place) in the Glyph Matrix dot style, plus "Updated X ago".
- Tap opens the app. Resizable, at least 1×1 (medal only) and 2×2 (medal + name + rank + age).
- Plain `AppWidgetProvider` + `RemoteViews` with the medal drawn into a bitmap, so no extra libraries.
- Updated by `RankRepository` whenever it saves a rank (from the app or the toy).
- Refreshing on its own while widgets exist: a `JobScheduler` job at the Auto refresh interval (min. 15 min), with
  network required, going through `RankRepository` so the rate-limit rules apply. The toy and the widget share the
  same cache, so they never both fetch.
- No animations on the widget; the Glyph and the app preview keep those.

## Device checks

- **Always-on (AOD) toy: not tested yet, not a priority.** When tested: pick Dota Rank as the Always-on Glyph Toy,
  lock the phone for ~2 min, look for `DotaRankToy: event aod` in logcat, and check that it redraws without fetching
  unless the rank is stale.
- **Glyph service reconnect:** handled in `GlyphMatrixService` (one disconnect per connect, no frames to a dead
  connection), but never provoked on the device.
- **"App icon shows my medal" on the Nothing launcher:** the alias switch works (checked with `adb`); still to see
  whether the launcher refreshes the icon promptly and whether a home-screen shortcut survives the switch.
- **Sharing reuses the open screen** (`singleTask`; checked with `adb`: a share goes to the open screen via
  `onNewIntent`). Still to try with a real share from the Steam app or Chrome: Back should return to that app.
- **LED brightness is managed centrally** (verified in the Glyph service log): frame values reach the service
  unchanged (0–4095), and the service applies the system Glyph brightness on top (`setLightFrame … brightness:200`,
  from the global setting `led_brightness_value`). Our values are relative levels; no brightness setting in the app.

## Parked ideas (keep in mind, not planned)

- **Release build:** signing config, version number, debug logs off. For later.
- **Last match W/L:** OpenDota's recent-matches endpoint; could be an alternative display (short `W` / `L` or a win streak).
- **Friends rotation:** the toy cycles through saved players automatically (long-press is already used for refresh).
- **Rank-change notification:** optional Android notification when the rank changes.
- **Sign in with Steam** (OpenID 2.0): fills in the ID without pasting. The sign-in returns
  `https://steamcommunity.com/openid/id/<SteamID64>`, which `PlayerInput.parse` already understands as a SteamID64;
  needs a browser redirect back into the app. Later.
- **Profile links** (OpenDota / Dotabuff / Stratz) and **rank history**: not yet, no good design found.

Decided against: an in-app Glyph brightness setting (the system manages it), a Quick Settings tile.

## Other cleanup (not decided yet)

- Move user-facing texts from code into `strings.xml` (translations; the phone is set to German).
- Split `MainActivity` (layout code, settings sections); it has grown a lot.
- Remove leftovers: `DISABLE_SYSTEM_TIMEOUT`, the unused `!` glyph in `PixelFont`, and Glyph event logging in release builds.

## Suggested build order

1. Feature 1 (home-screen widget).
2. QoL 1 (debug replay), whenever it helps with testing the widget or animations.
