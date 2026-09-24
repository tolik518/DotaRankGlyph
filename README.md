# Dota Rank Glyph

A Glyph Toy for the **Nothing Phone (3)** that shows your Dota 2 rank on the Glyph Matrix, using the OpenDota API.

![Bundled Dota 2 medals on the Glyph Matrix](docs/rank-states.png)

## Medal display

- The eight grayscale Dota 2 medal icons are bundled in the app and used by default.
- Earned stars appear as small bright LEDs along the top of Herald through Divine medals.
- **Show exact rank for Immortals** (on by default): the Immortal medal shows the leaderboard place (e.g. `2488`)
  on a dark plate in its lower part, when OpenDota returns one. Up to 5 digits fit.
- **Status:** `?` uncalibrated, `ID` no account set, spinner = loading the first rank.
  Reloading a known rank shakes the medal instead (always at least one full shake).
- **Errors** are only shown in the app. The Glyph (and the app preview) keep showing the last known medal.

`rank_tier` decoding: tens digit = medal, ones digit = stars. Example: `24` → Guardian, 4 stars.

## Optional custom icon packs

Bundled Dota 2 medals work without importing anything. To use other artwork, import a custom pack on the settings screen and enable **Override with imported icon pack**.

- **Zip contents:** `herald.png` … `immortal.png` (any name containing the medal name works, folders are fine)
  and/or a `bitmaps.json` of the form `{"ranks": {"herald": [[25 rows × 25 values 0–255]], …}}`.
  JSON wins where both exist; other files (README, preview images) are ignored.
- **Images:** square, ideally 25×25, black = off, brighter = brighter LED. Other sizes are scaled down; transparency counts as off.
- **Stars** are drawn as bright LEDs on a dark band cut into the top edge of the icon.
- **Partial packs** are fine: missing medals use the bundled Dota 2 icons. Status screens (`ID`, `?`, spinner) use the built-in status style.
- The pack is stored in the app's private storage (`files/icon_pack.json`) and never becomes part of the project or APK.
  If your icons are someone else's artwork, keep them out of anything you publish.

## Setup

1. **Get the SDK.** Download `glyph-matrix-sdk-2.0.aar` from
   [GlyphMatrix-Developer-Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit)
   into `app/libs/`. It's not bundled because Nothing's licence forbids redistribution.
2. **Open in Android Studio**, let Gradle sync, connect your Phone (3) with USB debugging, press Run.
3. **In the app:** enter your friend ID (e.g. `40453096`), tap *Save & check rank*. The preview shows the Dota medal and stars that will appear on the matrix.
4. **Activate the toy:** tap *Add to Glyph Toys* (or Settings → Glyph Interface → Glyph Toys) and drag *Dota Rank* to Active.
5. **Use it:** short-press the Glyph Button until the toy appears; long-press to refresh.
   Optionally pick it as the Always-on Glyph Toy.

In Dota 2, *Expose Public Match Data* must be enabled, otherwise OpenDota has no rank for you.

### Accepted input

Friend ID (`40453096`), SteamID64 (`76561198000718824`), `STEAM_0:0:20226548`, `[U:1:40453096]`,
`steamcommunity.com/profiles/<id64>`, and `opendota.com` / `dotabuff.com` / `stratz.com` `/players/<id>` links.
Custom URLs (`steamcommunity.com/id/<name>`) are detected but not resolved yet.

## Behaviour

- Cached rank is shown instantly; background refresh when it is older than the **Auto refresh** setting
  (every 5 min … once a day, default 30 min).
- Long-press forces a refresh (min. 5 s between requests); the medal shakes while it loads. If it fails, the
  last known medal simply stays.
- Reloads started on the Glyph (long-press) or in the app (*Save & check rank*) shake the medal on both
  the Glyph and the open settings screen, in step.
- *Save & check rank* has a 5 s cooldown (and stays disabled while a lookup runs).
- As the AOD toy it redraws on the system's per-minute `EVENT_AOD` tick, but only calls OpenDota when the
  cached rank is older than the Auto refresh setting.
- Changing the ID in the app updates a visible toy immediately.
- OpenDota free tier: about 60 requests/min and 3000/day, far more than this uses.

## Things worth knowing about SDK 2.0

- **Raw frame values go up to 4095**, not 255: the SDK's own bitmap converter scales to 0–4095.
  Nothing's example project writes 255, which is very dim.
- The matrix has **489 LEDs** in a circle inside the 25×25 array (row widths from Nothing's LED allocation diagram).
- `GlyphMatrixManager.setGlyphMatrixTimeout(boolean)` exists but is undocumented. It may relate to the matrix
  switching off after a few minutes. It's behind `DISABLE_SYSTEM_TIMEOUT` (off) in `DotaRankToyService`.

## Project layout

| Path | What |
|---|---|
| `rank/Rank.kt` | `rank_tier` → `RankState` (medal + stars / Immortal / uncalibrated) |
| `rank/PlayerInput.kt` | Parses IDs and profile URLs into an account ID |
| `data/OpenDotaClient.kt` | `GET /api/players/{id}` + JSON parsing |
| `data/RankStore.kt` | Account ID, cached rank and settings (SharedPreferences) |
| `data/RefreshInterval.kt` | Auto refresh interval limits (5 min … once a day) |
| `glyph/` | Matrix geometry, 3×5 font, medal renderer, icon pack parser |
| `app/src/main/assets/dota_rank_medals.json` | Bundled grayscale medal images used by default |
| `data/IconPackStore.kt` | Loads bundled medals and optional imported overrides |
| `toy/GlyphMatrixService.kt` | Toy base class, adapted from Nothing's MIT example |
| `toy/DotaRankToyService.kt` | The toy |
| `toy/ReloadShake.kt` | The reload shake shared by the Glyph toy and the settings screen |
| `ui/` | Settings screen + on-screen matrix preview |

No AndroidX or Compose: the only dependency is the Glyph SDK, so the build stays small.

## Tests

`./gradlew test` runs 62 unit tests: rank decoding, ID parsing, OpenDota parsing (using a real captured response),
the OpenDota client against a local mock server (captured player responses in `app/src/test/resources/opendota/`;
404, 429, 5xx, HTML/truncated bodies, timeouts, no connection, non-Latin names), auto refresh interval limits,
matrix geometry, renderer checks (lit arcs = tier, one cross per star, nothing drawn outside the LED circle),
and icon packs (zip/JSON parsing, size limits, masking, storage round trip, one clean star pip per star,
including on the bundled medal art).

## Next steps: Steam URL / login

- **Custom Steam URL:** resolve `/id/<name>` to a SteamID64 with Steam's
  `ISteamUser/ResolveVanityURL` Web API (needs a free Steam Web API key), then subtract
  `76561197960265728` to get the account ID. The parser already returns `PlayerInput.SteamVanity` for this.
- **Steam login:** Steam's OpenID 2.0 sign-in returns `https://steamcommunity.com/openid/id/<SteamID64>`;
  feed that ID into `PlayerInput.parse`. Needs a browser redirect back into the app.

## Credits

Rank data: [OpenDota](https://www.opendota.com). Dota 2 is a trademark of Valve Corporation; this project is not affiliated with Valve or Nothing.
The bundled grayscale icons are adapted from the reference screenshots supplied for this project; they are not official Valve image files.
