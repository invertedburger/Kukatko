# Kukátko

**A small, fast Android TV app for Oneplay (the former O2 TV), made to be easy for an elderly viewer.**

Opens straight into the channel you watched last. Big buttons, no menus to get lost in. Up and
down change the channel, OK shows the list, and the programmes you watch most turn up on top by
themselves.

> **Alpha.** It runs daily on a Google TV, the API is unofficial and can change
> without notice. Expect rough edges. Feedback and issues are welcome.

*Česky v kostce: odlehčená aplikace pro Android TV, která přehrává Oneplay (dřív O2 TV) z vašeho
vlastního předplatného. Startuje rovnou na posledním kanálu, ovládá se šipkami, OK a CH+/CH−,
umí archiv 7 dní, tlačítko „Od začátku“, hledání pořadu bez klávesnice a sama si pamatuje
oblíbené kanály a pořady. Je určená pro starší diváky. Není oficiální, s Oneplay ani O2 nemá nic společného.*

## What it does

- **Instant start** on the last channel, quick zapping with Up / Down or CH+ / CH−.
- **Channel list** with what is on now and the channel logos.
- **Od začátku**: play the programme that is on now from its beginning.
- **Zmeškané pořady**: the guide of a channel for the last 7 days, OK plays a past programme.
- **Hledat pořad**: find a show in the archive **without a keyboard**. Big letters A to Z picked
  with the arrows, results from 2 letters on, newest first, with the date in its own column.
  Oneplay's search copes without diacritics (ULI finds Ulice). Recent searches stay as one press rows.
- **Automatic favourites.** Every minute watched counts for the channel and for the programme
  title (fading by half every 30 days). Channels with 15+ minutes become **Moje kanály**: green, on
  top, and the only ones CH+ / CH− walk through once there are three or more. A title watched 10+
  minutes on two different days is a favourite show: green in the guide, a star in the channel
  list. Nothing to set up.
- **Back never throws you out** of live TV; it only shows the info banner. Home leaves the app.
- Widevine DRM channels play through Oneplay's own licence server.

### Remote

Arrows, OK, Back and CH+ / CH− are enough. Colour keys are not needed.

| Key | Action |
| --- | --- |
| CH+ / CH− | next / previous channel from anywhere; closes lists, leaves a recording, always lands on live TV |
| Up / Down | next / previous channel while watching |
| OK | channel list (Back closes it) |
| Left / Right, Info | show the info banner |
| Back | closes the list or leaves a recording; on live TV only shows the banner |
| 0 to 9 | jump to a channel number |

In the channel list, Right on a row reaches its buttons *Od začátku* and *Zmeškané pořady*.
While playing from the archive: Left / Right skip 30 s, Rewind / Fast-forward 5 min, Play / Pause
pauses, Back returns to live TV.

## Install

1. Get the APK from the [Releases](../../releases) page (pre-releases are alphas).
2. Put it on the TV: enable Developer options and network debugging, then
   `adb connect <tv-ip>:5555` and `adb install -r kukatko-<version>.apk`.
   Google TV may show a Play Protect warning for an unknown developer: allow it.
3. Open Kukátko and log in with your Oneplay account (use `tools/login.ps1` if typing with a
   remote is a pain, see below).

Needs a **valid Oneplay subscription**. Android 7.0 (API 24) or newer.

## Privacy and honesty

- The app talks only to Oneplay's servers, the same ones the official app uses. No analytics,
  no third party services.
- Your login is kept in the app's private storage on the device (plain SharedPreferences,
  protected by Android's app sandbox, not encrypted). Uninstall the app or clear its data to remove it.
- **Unofficial.** Not affiliated with, endorsed by or supported by Oneplay, O2 or CME. The API is
  not documented and can change at any time; the app may then stop working until it is updated.
  No DRM is bypassed: protected streams use the licence Oneplay hands out for your account.
  Each login registers a device on your account (named "Kukátko (model)"); the app removes its
  own older entries. Use it with your own subscription and according to Oneplay's terms.

## Build

Kotlin, Jetpack Compose for TV, Media3 ExoPlayer (DASH + Widevine), OkHttp,
kotlinx.serialization. JDK 17 and Android SDK 35.

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug
./gradlew assembleRelease    # optimised (R8), signed with the debug key unless KUKATKO_KEYSTORE* is set
```

GitHub Actions builds every push. A tag such as `v0.1.0-alpha.1` builds the release APK and
publishes it as a pre-release (see [.github/workflows/build.yml](.github/workflows/build.yml) for
the signing secrets).

### Tools (Windows, PowerShell / cmd)

They expect JDK 17 and the SDK in `%USERPROFILE%\android-dev` (`jdk17`, `sdk`). The Oneplay login
for the tools lives in `%USERPROFILE%\.kukatko.env`:

```text
O2_USER=you@example.com
O2_PASS=...
```

- `run.bat [release] [login] [tv <ip>]` builds, starts the `KukatkoTV` emulator (or goes to the
  TV with `tv <ip>` / `KUKATKO_TV`), installs, launches and follows the log, which also shows
  every key the remote sends.
- `tools/deploy-tv.ps1 [-Login] [-Tv <ip>]` puts the release build on the TV while it is in
  standby and refuses when the screen is on. `KUKATKO_ADB_KEY` can point to an adb key the TV
  already trusts, so no "Allow debugging?" prompt is needed. For `-Login` switch the TV on first.
- `tools/login.ps1` logs the app in over adb, `tools/type-clipboard.ps1` types the clipboard into
  the focused field.
- `node tools/probe.mjs [startover | search <text>]` runs the Oneplay flow from the PC: channels,
  now playing, stream types, latency, archive search.
- `tools/logo/make_logo.py` renders the banner and icon.

## How it works

Oneplay API (`http.cms.jyxo.cz/api/v1.xx/<method>` plus a websocket for async answers). The flow
follows the Kodi add-on [waladir/plugin.video.oneplay](https://github.com/waladir/plugin.video.oneplay):
`user.login.step`, `user.profile.select`, `epg.channels.display`, `epg.display`, `content.play`
(DASH, Widevine with an `x-axdrm-message` token), `page.search.display`. See
[OneplayApi.kt](app/src/main/java/cz/ivo/kukatko/OneplayApi.kt). The old O2 TV Kaltura API has
answered `403 PARTNER BLOCKED` since the move to Oneplay.

The roadmap is in [PLAN.md](PLAN.md): a home menu, a day picker for missed programmes,
a recording control bar, a *Moje pořady* list, and control through Home Assistant and voice.

## Credits and license

Thanks to [waladir](https://github.com/waladir) for the reverse engineered Oneplay flow in his
Kodi add-on. Kukátko is free software under the **GNU GPL v3**, see [LICENSE](LICENSE).
"Oneplay" and "O2" are trademarks of their owners and are used here only to say what the app
works with.
