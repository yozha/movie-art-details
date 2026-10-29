# Movie Art Details

A wallpaper plugin for the [Projectivy Launcher](https://play.google.com/store/apps/details?id=com.spocky.projengmenu)
on Android TV. When a movie or series card is focused, the launcher background becomes the title's
[TMDB](https://www.themoviedb.org/) backdrop with a short info block drawn into the image above the card row.

> **Status: early MVP.** Unreleased and not yet verified against live TMDB data.
> Tested only with Projectivy 4.72. Expect rough edges.

## Relation to Movie Art for Projectivy

This is an independent fork of [Movie Art for Projectivy](https://github.com/Dak0r/Movie-Art-For-Projectivy-Plugin)
by Daniel Korgel (MIT), based on commit `4eab0d3`. Thanks to Daniel for the original plugin.

- Most of the original source has been replaced; the fork keeps the project layout, the Gradle setup, and
  the MIT license of the original.
- It uses its own application ID (`io.github.yozha.movieartdetails`) and its own Projectivy provider UUID, so it
  installs **alongside** Movie Art rather than replacing it, and does not touch Movie Art's data.
- This project is not affiliated with or endorsed by the author of Movie Art. Please report problems with
  this fork here, not upstream.

## Features

- **Backdrop and info block** for focused movie and series cards: logo (or the title as text if there is no
  usable logo), year, type (movie or series), runtime, genres, TMDB rating out of 10, and a 3–5 line
  overview. Each field can be turned off.
- **Neutral background** (a solid color) for app cards, YouTube videos, and cards that can't be recognized,
  so the previous title's backdrop never lingers. The color can optionally be tinted to match the app card.
- **Title matching** based on card titles, including release-style file names (for example, from VLC).
  Bracketed ID tags such as `[imdbid-tt0499549]` or `[tmdbid-19995]` are used directly when present.
  Low-confidence matches are rejected rather than guessed.
- **Manual corrections**: pick the right title from recently seen cards, enter a TMDB or IMDb ID, or mark a
  title as "Don't recognize."
- **Appearance settings**: text size, block position and width, overview lines, scrim strength,
  neutral color, minimum vote count for showing the rating, and a live preview.
- **Metadata language** for titles, overviews, and genres: English, Russian, or Ukrainian, or the system
  language when it is one of these. Missing translations fall back to English.
- **Offline-friendly cache** with an adjustable size limit (100 MB to 1 GB) and a clear-cache option.
- **Optional diagnostic log** (last 50 events, never the key or request URLs; card titles only if you opt in).

## How it works

The plugin implements the Projectivy wallpaper provider API with `updateMode = 13`
(`TIME_ELAPSED | CARD_FOCUSED | PROGRAM_CARD_FOCUSED`).

1. When a program card is focused, the plugin parses the card title, uses the card type and image host to
   decide whether to look it up (apps and YouTube are skipped), searches TMDB, and loads details and images.
2. It renders a 1920×1080 image (backdrop plus info block) and hands it to Projectivy through a
   `FileProvider` URI. Finished images are cached, so returning to a card is instant.
3. If the image isn't ready within about 300 ms, the plugin returns the neutral background right away and
   keeps working in the background. Once the image is ready, and if the card is still focused, it sends the
   `WALLPAPER_PROVIDER_UPDATED` broadcast so Projectivy requests the wallpaper again and shows the result.

## Requirements

- Android TV device running Android 8.0 (API 26) or newer
- [Projectivy Launcher](https://play.google.com/store/apps/details?id=com.spocky.projengmenu) (tested with 4.72)
- A free TMDB **v3 API key** (32 hex characters). An "API Read Access Token" (v4) is not supported.

## Setup

1. Install the APK, then in Projectivy choose it under **Settings → Appearance → Wallpaper**.
2. Open the plugin settings, enter your TMDB API key with the remote, and select **Check key**.

No key is bundled with the app. Your key is stored encrypted on the device (Android Keystore), is excluded
from backups, and is never logged.

### Getting a TMDB API key

Create a free account at [themoviedb.org](https://www.themoviedb.org/), then go to
**Settings → API** ([direct link](https://www.themoviedb.org/settings/api)) and request a developer key.
Use the value labeled **API Key** (v3).

## Building

Requirements: JDK 21 and the Android SDK with platform 36 (`ANDROID_HOME` or `local.properties` with `sdk.dir`).
The Gradle daemon runs on JetBrains Runtime 21 (see `gradle/gradle-daemon-jvm.properties`); if it isn't
installed, Gradle downloads it automatically on the first build.

```sh
./gradlew assembleDebug                              # debug APK
./gradlew :movie-art-plugin:testDebugUnitTest        # unit tests
./gradlew assembleRelease                            # release APK (minified with R8)
```

### Optional `keystore.properties`

Local build settings are read from `keystore.properties` in the repository root. The file is git-ignored;
never commit it. All entries are optional.

```properties
# Signing for release builds. Without storeFile the release APK is built unsigned.
storeFile=path/to/your-release.jks
storePassword=CHANGE_ME
keyAlias=CHANGE_ME
keyPassword=CHANGE_ME

# Developer TMDB key for debug builds only.
devTmdbKey=0123456789abcdef0123456789abcdef
```

`devTmdbKey` is compiled into the debug build's `BuildConfig.DEV_TMDB_KEY` and is used only when no key has
been entered in the settings. Release builds always get an empty value. Since the key ends up inside the
debug APK, don't share debug builds made with your personal key.

## Privacy

- **Sent to TMDB** (`api.themoviedb.org`, over HTTPS): search queries derived from the titles of focused
  movie/series cards (cleaned title, optionally the year, or an IMDb ID tag), searches and IDs from the
  corrections screen, the TMDB IDs of matched titles, the language code, and your API key. Images are
  downloaded from `image.tmdb.org`. Nothing is sent for app cards, YouTube, or skipped cards.
- **Stored on the device only**: the API key (encrypted), TMDB responses and images, rendered backgrounds,
  manual corrections, the recent titles list (up to 50 titles, kept for at most 30 days), and the optional
  diagnostic log. The key, corrections, title history and log are excluded from Android backup.
- No analytics, ads, crash reporting or self-updates. The app's only permission is `INTERNET`.

## License and attribution

- The code is licensed under the MIT License; see [LICENSE](LICENSE)
  (Copyright (c) 2026 Daniel Korgel, original Movie Art for Projectivy).
- The sources in `api/src/` are taken unmodified from
  [spocky/projectivy-plugin-wallpaper-provider](https://github.com/spocky/projectivy-plugin-wallpaper-provider)
  at commit `60ec7b4` (Apache License 2.0); `api/build.gradle.kts` has formatting-only changes.
- Parts of the background rendering are adapted from
  [rfmarcos/projectivy-plugin-sofadrop](https://github.com/rfmarcos/projectivy-plugin-sofadrop) (Apache License 2.0).
- Bundled font: Roboto 2.138 (Apache License 2.0); see [licenses/fonts](licenses/fonts/README.md).
- Libraries: AndroidX, OkHttp, kotlinx.serialization (Apache License 2.0).

See [NOTICE](NOTICE) for details and [licenses/](licenses/) for the full license texts.

### TMDB

<a href="https://www.themoviedb.org/"><img src="https://www.themoviedb.org/assets/v4/logos/v2/blue_square_1-5bdc75aaebeb75dc7ae79426ddd9be3b2be1e342510f8202baf6bffa71d7f5c4.svg" alt="TMDB logo" height="40"></a>

This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.

Movie and series data and images are provided by [TMDB](https://www.themoviedb.org/). The TMDB logo is a
trademark of TMDB and is not covered by this repository's license; see [licenses/TMDB-LOGO.md](licenses/TMDB-LOGO.md).
