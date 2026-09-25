# Contributing

Thanks for helping with Dota Rank Glyph. This file has everything you need to build, test and change the app.
What's planned (and what was decided against) is in [roadmap.md](roadmap.md).

## Build and install

You need:

- A **Nothing Phone (3)** with USB debugging on (the Glyph Matrix exists only there; the app builds without it).
- **JDK 17 or newer** (built with 21) and the **Android SDK** with platform 35 (Android Studio installs both).
- Nothing's **Glyph Matrix SDK**: download `glyph-matrix-sdk-2.0.aar` from the
  [GlyphMatrix-Developer-Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit) and put it
  into `app/libs/`. It is not in this repository because its licence doesn't allow redistribution, and it is
  git-ignored so it can't be committed by accident.

Then either open the project in Android Studio and press Run, or:

```sh
./gradlew assembleDebug                      # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                       # builds and installs it on the connected phone
./gradlew testDebugUnitTest                  # runs the unit tests
```

Versions: AGP 8.10.1, Kotlin 2.0, Gradle 8.11 (the same as Nothing's example project), `compileSdk`/`targetSdk` 35,
`minSdk` 34.

## How it works

### Overview

```
 MainActivity (settings screen) ─┐                  ┌─ OpenDotaClient ── api.opendota.com
 DotaRankToyService (Glyph Toy) ──┼── RankRepository ─┼─ RefreshPolicy (rate limits, backoff)
 RankRefreshJob (widget refresh) ─┘   (one process-   └─ RankStore (SharedPreferences)
                                        wide object)
          ▲ listeners: rank saved / state changed
          │
 RankWidget, launcher icon, ReloadShake (reload shake), RankCelebration (rank-change animation)
```

- **`RankRepository`** is the only place that fetches ranks. The settings screen, the toy and the widget job all
  call `refresh(manual)`. It asks `RefreshPolicy` whether a request may go out, runs at most one request per
  account (a second caller joins it), saves the result or error in `RankStore`, and tells listeners.
  Main thread only; the request itself runs on a background executor.
- **`RefreshPolicy`** holds the rules as pure functions (tested with an injected clock):
  - OpenDota's free tier allows **60 requests/minute and 3000/day per IP**, reset at the start of each UTC minute
    and at 00:00 UTC; rejected requests count too. Every response has `X-Rate-Limit-Remaining-Minute/-Day`,
    a 429 says `minute rate limit exceeded` or `daily api limit exceeded`, and there is no `Retry-After`.
  - After a 429, or when a remaining-header reaches 0, **nothing** is sent (manual or automatic) until the reset.
  - Fewer than 50 requests left today: automatic refreshes pause until 00:00 UTC; manual checks still work.
  - After failures, automatic refreshes back off 1, 2, 4, 8 … minutes, capped at the refresh interval.
  - At most one request per account every 5 s.
  - The state (`GuardState`) is persisted, so it survives restarts and Glyph reconnects.
- **`RankStore`** keeps the settings, the cached rank **per account** (`RankCache`, JSON), the recent accounts,
  the last error, the guard state and a pending rank-change animation.
- **`DotaRankToyService`** is the Glyph Toy. The system binds it when the toy is selected with the Glyph Button.
  It shows the cached medal, refreshes when stale, refreshes on long-press, and never shows errors.
  `GlyphMatrixService` (adapted from Nothing's MIT example) handles binding, Glyph Button events and
  reconnects of Nothing's Glyph service.
- **`ReloadShake`** and **`RankCelebration`** are process-wide players, so the toy and the open settings screen
  shake / animate in step. A manual reload shakes the last known medal until the request is done (always whole
  cycles); a changed rank then plays `RankAnimation`. If the toy isn't on the Glyph at that moment, the change is
  kept in `RankStore` and played the next time the toy is selected.
- **Background network**: on Android 15 the app's network is blocked while its process is in the background
  (`dumpsys netpolicy` shows `blocked=APP_BACKGROUND` for its UID). While the Glyph toy is on the matrix the
  process counts as "important foreground" and may use the network, and jobs may too. As a safety net for
  everything else, `RankRepository` checks `ConnectivityManager.getActiveNetwork()` (null when blocked or
  offline) and, without a network, queues the request for an expedited one-off job
  (`RankRefreshJob.scheduleFetchNow`). After 20 s without the job the attempt counts as "No internet connection".
- **Slow OpenDota**: under load the player endpoint can take 10–40 s to answer (while `/health` answers in under
  a second), so the client waits up to 45 s for an answer (10 s to connect), and the reload shake stops after
  about 10 s; the result still shows (and animates) when it arrives.
- **`RankWidget`** redraws from the cache after every save. **`RankRefreshJob`** (JobScheduler, network required)
  refreshes at the Auto refresh interval while a widget exists. A force-stop cancels jobs, so the app and the toy
  re-schedule it when they start.
- **`LauncherIcon`**: Android can't set arbitrary launcher icons, so the manifest has one launcher
  `activity-alias` per medal (Herald … Divine, plus the default) and exactly one is enabled.

### Account input

`PlayerInput.parse` decodes everything offline: friend ID, SteamID64, `STEAM_0:x:y`, `[U:1:x]`,
`steamcommunity.com/profiles/<id64>`, OpenDota/Dotabuff/Stratz `/players/<id>` links and Steam friend-code links
(`s.team/p/djn-gfvm`, `steamcommunity.com/user/djn-gfvm`; the code is the account ID in hex written with the letters
`bcdfghjkmnpqrtvw`). Custom URLs (`steamcommunity.com/id/<name>`) are resolved once by `SteamProfileResolver`
through the public XML view (`/id/<name>/?xml=1`, falling back to the profile page), without a Steam Web API key.
Steam doesn't document its limits for these pages; on a 429 the app reports it and doesn't retry.

### Glyph Matrix and SDK 2.0 notes

- Frames are 25×25 `IntArray`s, but only **489 positions have an LED** (a circle; row widths from Nothing's LED
  allocation diagram, see `MatrixLayout`).
- **Frame values go up to 4095**, not 255 (the SDK's own bitmap converter scales to 0–4095; Nothing's example
  writes 255, which is very dim).
- **Brightness is managed centrally**: the Glyph service passes frame values through unchanged and applies the
  system Glyph brightness on top (`setLightFrame … brightness:200` in logcat, from the global setting
  `led_brightness_value`). Frame values are relative levels.
- Toy events arrive as `change` (long-press), `action_down` / `action_up` and `aod` (about once a minute for the
  Always-on toy).
- `GlyphMatrixManager.setGlyphMatrixTimeout(boolean)` exists but is undocumented; it sits behind
  `DISABLE_SYSTEM_TIMEOUT` (off) in `DotaRankToyService`.
- Medals: `assets/dota_rank_medals.json` holds a 25×25 greyscale image (0–255) per medal. `RankRenderer` masks it
  to the LEDs, cuts a dark band into the top edge for the star pips and a plate for the Immortal place, and
  removes art fragments the cuts leave behind (a lone LED would look like an extra star).

### Project layout

| Path (under `app/src/main/java/com/glyphrank/dota/`) | What |
| --- | --- |
| `rank/` | `rank_tier` decoding (`Rank.kt`), account input parsing (`PlayerInput.kt`) |
| `data/RankRepository.kt`, `RefreshPolicy.kt` | Fetching and the rules for when to fetch |
| `data/RankStore.kt`, `RankCache.kt`, `RecentAccounts.kt` | Everything stored in SharedPreferences |
| `data/OpenDotaClient.kt`, `SteamProfileResolver.kt` | The two network clients |
| `data/RefreshInterval.kt`, `LauncherIcon.kt`, `BundledMedals.kt` | Interval choices, launcher alias switch, medal art loader |
| `glyph/` | Matrix geometry, 3×5 font, medal renderer, medal art, reload shake, rank-change animation |
| `toy/` | The Glyph Toy and its base class |
| `ui/` | Settings screen, matrix preview, recent-accounts dropdown |
| `widget/` | Home-screen widget and its refresh job |
| `util/MainThread.kt` | Main-thread scheduler, replaced by a fake in tests |
| `app/src/main/assets/dota_rank_medals.json` | Bundled medal art |
| `tools/make_icons.py` | Generates the launcher icons from the medal art |
| `docs/` | README images (see below) |

## Tests

`./gradlew testDebugUnitTest` runs the JVM unit tests (no phone needed). They cover rank decoding, account input
parsing, the OpenDota client and the Steam lookup against a local `MockHttpServer` (captured responses, 404,
minute/daily 429, rate-limit headers, 5xx, broken bodies, timeouts, no connection), the refresh policy,
`RankRepository` end to end (with `FakeSharedPreferences` and a `FakeScheduler` in virtual time), the per-account
cache and its migration, the recent-accounts list, the renderer (star pips, Immortal plate, nothing outside the
LED circle), the rank-change animations, the medal art and the launcher aliases.

Rules for test data:

- Captured OpenDota responses live in `app/src/test/resources/opendota/`. **Anonymise player names** before adding
  one (use `Player 1`, `Player 2`, …); no real names except the ones already there.
- Never commit API keys, credentials, private device identifiers or raw personal data.

### Testing on the phone

Some things only show on the device (the Glyph itself, the launcher, widgets). Useful commands:

```sh
adb logcat -s GlyphMatrixService DotaRankToy RankRepository RankRefreshJob LauncherIcon
adb logcat | grep setLightFrame          # the frame the Glyph service actually lights up
adb exec-out run-as com.glyphrank.dota cat shared_prefs/dota_rank.xml   # stored state
adb exec-out screencap -p > screen.png
```

In debug builds, **long-press the app preview** to play sample rank-change animations (on the Glyph too, if the
toy is showing). To see a real one, stop the app, lower `tier` for your account in the `rank_cache` entry of
`dota_rank.xml`, and check again.

## Generated files

- **Launcher icons**: `python3 tools/make_icons.py` regenerates `res/drawable/ic_medal_*.xml` and
  `res/mipmap-anydpi/ic_launcher*.xml` from the medal art.
- **README images**: `docs/glyph-medals.png` and `docs/rank-change.gif` come from the real renderer:
  ```sh
  DOCS_OUT=$PWD/docs ./gradlew testDebugUnitTest --tests '*DocsImages*'
  ```
  The app screenshots (`docs/app-*.png`) are phone screenshots (1260×1920 crop below the status bar, scaled to
  540 wide). Only show your own or anonymised accounts in them.

## Code style

- **No AndroidX or Compose**: plain Android Views and framework APIs, so the only dependency is the Glyph SDK.
  Please keep it that way unless there is a strong reason.
- Kotlin, 4 spaces, trailing commas, comments that explain *why*. Match the style of the surrounding code.
- Keep the UI text short and plain. The Glyph never shows errors; the app does.
- Put logic that can be tested into plain classes or functions (like `RefreshPolicy`, `RankAnimation`) and add tests.

## Pull requests

1. Open an issue first for bigger changes, so we can agree on the approach.
2. Keep pull requests focused; one topic per PR.
3. Make sure `./gradlew testDebugUnitTest assembleDebug` passes, and say what you checked on a phone.
4. Update the README (user-facing changes), this file (developer-facing changes) or the roadmap if needed.

By contributing you agree that your contributions are licensed under the project's [MIT License](LICENSE).
