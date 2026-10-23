# Plan

## 1. Test on the real TV (next, before anything else)

`run.bat release login tv <ip>` (or `setx KUKATKO_TV <ip>` once, then `run.bat release login tv`).
Needs developer options + network debugging on the TV.

- Widevine/DRM channels play (the emulator is not representative either way).
- Remote: press every button and read the log (`key KEYCODE_… code= scan=`, logged by
  `MainActivity.dispatchKeyEvent`); map CH+/−, digits, Guide, Info, rewind/forward, colour keys.
  Buttons the system swallows (Home, Power, …) never reach the app: use `adb shell getevent -l`.
- Start-up speed to the last channel on the `release` build.
- Device slots in the Oneplay account stay under the limit after repeated logins.

## 2. Simpler UI for an elderly viewer (proposal, not started)

They watch live TV, missed programmes and favourite shows, using only the arrows, OK, Back and
CH+/−; no colour buttons. Rules: one key means one thing everywhere, no hidden directions or
long presses, everything you can do is a visible button.

- **CH+/− always switch live channels**, from anywhere: in a menu, a list or a recording. That
  makes it the way back to live TV. Up/Down do the same while watching live TV.
- **OK on live TV opens a home menu** with three big tiles: *Kanály* · *Zmeškané pořady* ·
  *Moje pořady* (plus *Pokračovat* when a recording was left unfinished).
- **Left/Right on live TV only show the info banner**; they open no other screen.
- **Kanály**: favourite channels only (set up from the PC / HA, not with the remote). Each row has
  visible buttons `▶ Sledovat` · `⏮ Od začátku` · `📅 Zmeškané`; Right moves between them.
  *Od začátku* plays the running programme from the archive.
- **Zmeškané pořady**: channel → day tiles (*Dnes · Včera · Út 21. 10. …*) → that day's
  programmes only, focus on the evening (on now for today).
- **Moje pořady**: a short list of favourite shows; each opens its episodes from the last 7 days
  across all channels, newest first. Needs the 7-day guide of the favourite channels indexed in
  the background (same index as `/search` below). A show is added with `☆ Přidat do Mých pořadů`
  on any archive programme, or from the PC / HA.
- **Playing a recording**: an on-screen bar with big buttons `⏪ 5 min` `⏯` `⏩ 5 min`
  `📺 Živě`; Left/Right move along it, OK presses. Back asks "Vrátit se na živé vysílání?".
  The position is remembered (*Pokračovat od 21:14*).
- **Feedback**: large "Přepínám na ČT1…" with number and logo while loading; panels close by
  themselves after ~30 s without a key press.

## 3. Control and state for Home Assistant / voice / AI

Not started. Build after the TV test, in this order:

1. **MediaSession (Media3)**: HA's Android TV integration and Google Assistant then see title,
   state and play/pause for free. Cheapest step, biggest effect.
2. **Local HTTP API in the app** (bearer token, LAN only), on top of `TvState`
   (`play`, `playArchive`, `zap`, `seek`, `togglePause`):
   - `GET /state`: channel, number, current programme (from–to), live or archive, position, paused.
   - `GET /channels`, `GET /search?q=` (archive, last 7 days).
   - `POST /play` (channel number or name), `POST /archive` (channel + programme or time),
     `POST /pause`, `POST /seek`.
   - Optionally push state to an HA webhook on change instead of HA polling.
3. **Home Assistant side**: `rest_command` + sensors, custom Assist sentences
   ("pusť ČT1", "pusť Columbo z archivu"), scripts exposed to Assist so the AI (HA MCP) can call
   e.g. `script.kukatko_play` with channel/title/time. Channel-name aliases for Czech speech
   ("Nova" / "TV Nova").

Open points:

- **Cold start**: the app is not always running, so the HTTP server is only up in the foreground.
  HA wakes the TV (HDMI-CEC / Android Debug Bridge integration) and starts the app with
  `am start … --es …` like `tools/login.ps1` does. A foreground service is possible later, but
  Android 10+ blocks starting an activity from the background unless the overlay permission is
  granted over adb.
- **Archive search**: unknown whether the Oneplay API has one. If not, index the 7-day guide of all
  channels in the background and search locally (costs API calls).
