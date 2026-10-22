# Kukátko

Lightweight Android TV client for **Oneplay** (where O2 TV customers were moved in 2025),
for use with your own subscription. Opens straight into the last channel, D-pad zapping,
channel list with what's on now.

Kotlin · Compose for TV · Media3 ExoPlayer (DASH; Widevine through Oneplay's licence server when a channel needs it) · OkHttp.

## Remote

| Key | Action |
|-----|--------|
| Up / Down, CH+ / CH− | next / previous channel |
| OK, Guide | channel list (Back closes) |
| Left / Right, Info | show the info banner |
| 0–9 | jump to channel number |
| Right on a channel in the list | its guide for the last 7 days (archive); OK plays a past programme |

While playing from the archive: Left / Right skip 30 s, Rewind / Fast-forward 5 min,
Play/Pause or Space pauses, Back returns to live TV, Up / Down zap to live channels.

## API

Oneplay API (`http.cms.jyxo.cz/api/v1.xx/<method>` plus a websocket for async answers), flow
taken from [waladir/plugin.video.oneplay](https://github.com/waladir/plugin.video.oneplay):
`user.login.step` → `user.profile.select` (first profile) → `epg.channels.display`,
`epg.display` (now playing), `content.play` (DASH; Widevine with an `x-axdrm-message` token
header when needed). Each login registers a device on the account; the app names it "Kukátko"
and removes its own older entries. Unofficial and can change without warning; see
[app/src/main/java/cz/ivo/kukatko/OneplayApi.kt](app/src/main/java/cz/ivo/kukatko/OneplayApi.kt).

The old O2 TV 2.0 Kaltura API (partner 3201) has answered `403 PARTNER BLOCKED` since the move.

## Tools

Account in `%USERPROFILE%\.kukatko.env` as `O2_USER=` / `O2_PASS=` (the Oneplay login).

- `node tools/probe.mjs` runs the whole flow from the PC: channels, now playing, stream types, latency.
- `tools/login.ps1` logs the app in over adb (emulator or TV), no typing with a remote.
- `tools/type-clipboard.ps1` types the PC clipboard into the focused field over adb.

## Build & install

Toolchain lives in `C:\Users\you\android-dev` (JDK 17, SDK 35, `install.sh`). Android TV
emulator: AVD `KukatkoTV` (`sdk/emulator/emulator.exe -avd KukatkoTV`).

```sh
export JAVA_HOME="C:\\Users\\you\\android-dev\\jdk17"
./gradlew assembleRelease
/c/Users/you/android-dev/sdk/platform-tools/adb connect <tv-ip>:5555
/c/Users/you/android-dev/sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```

The login and session are stored in the app's private prefs on the TV.
