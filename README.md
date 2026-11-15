<div align="center">

# 🎤 LyricsMatch

**Live, line-by-line lyrics for whatever you're playing, built for singing in the car.**

Works with Spotify, YouTube Music, Apple Music, Deezer, Tidal and any other Android music app.

</div>

<p align="center">
  <img src="docs/screenshots/phone.png" width="260" alt="Lyrics on the phone" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/car-view.png" width="260" alt="Car view" />
</p>

---

## ✨ What it does

- 🎵 **Detects the current song automatically.** Press play in your music app and the lyrics show up. You don't need to search or log in.
- ⏱️ **Highlights each line as it's sung**, karaoke style, and scrolls along with the music.
- 🚗 **Switches to Car view by itself** when your phone connects to Android Auto: big, high-contrast text with only the current line and the next two.
- 📺 **Can also show lyrics on the car's display** (optional, see below).
- 👆 **Tap any line** to jump the song to that spot.
- 🎚️ **Fix the timing with one tap** if the lyrics are a little early or late.
- 💾 **Saves lyrics you've already played**, so replays load instantly, even with a weak signal.
- 🧘 **Keeps things simple:** no accounts, no ads, no pop-ups on top of other apps.

## 📲 Install

1. Download the latest `LyricsMatch.apk` from the [**Releases**](../../releases) page on your phone.
2. Open it and allow installing from your browser or file manager when Android asks.
3. Open **LyricsMatch** and tap **Allow access**. On the next screen, switch LyricsMatch **on**.

> **Is the switch greyed out?** Android blocks this setting for apps installed outside the Play Store until you unlock it:
> **Settings › Apps › LyricsMatch › ⋮ (top right) › Allow restricted settings**, then try again.

That's it. Play a song and sing along. 🎤

## 🚗 Using it in the car

LyricsMatch runs on your **phone**. You don't install anything on the car's screen or head unit.

### Option A: lyrics on your phone (simplest)

1. Put your phone in a holder that's easy to see.
2. Connect to Android Auto as usual and open LyricsMatch on the phone.
3. It switches to **Car view** automatically, with large text and the screen kept on while music plays.

You can try Car view at home too: tap **Car view** at the top of the screen.

### Option B: lyrics on the car's display

The car display only shows apps that Android Auto trusts. Because LyricsMatch isn't from the Play Store, you need to allow it once:

1. On your phone, open **Settings › Connected devices › Connection preferences › Android Auto**.
2. Scroll to the bottom and tap **Version** about 10 times until you're asked to enable developer settings. Accept.
3. Open the **⋮ menu › Developer settings** and turn on **Unknown sources**.
4. Reconnect to your car. **LyricsMatch** now shows up in the Android Auto app launcher.

On the car display, lyrics use Android Auto's own templates. The car decides how much text it shows while you're moving, which keeps it within the driving safety rules.

## 🎚️ Tips

| If… | Do this |
| --- | --- |
| Lyrics are **ahead** of the singer | Tap **Later** at the bottom (each tap is 0.25 s) |
| Lyrics are **behind** the singer | Tap **Earlier** |
| It says *"No lyrics found"* | Some songs aren't in the lyrics library yet. Tap **Try again** if you were offline |
| Only the full text shows, with no highlighting | That song has no timed lyrics yet, so the plain text is shown instead |
| Nothing happens when music plays | Check that notification access is still on (step 3 of Install) |

## 🔒 Privacy

- LyricsMatch asks for **notification access** only because Android requires it before an app can see which song is playing. It **never reads your notifications**.
- The only data sent anywhere is the **song title, artist, album and length**, sent to [LRCLIB](https://lrclib.net) to look up lyrics.
- There are no accounts, analytics or ads.

## 🙏 Credits

Lyrics are provided by **[LRCLIB](https://lrclib.net)**, a free, community-built lyrics library. If a song is missing or out of sync, you can contribute it there.

## ⚠️ Drive safely

Keep your eyes on the road. LyricsMatch is meant for **passengers and quick glances**. Car view and the Android Auto display are kept minimal for that reason.

---

<details>
<summary><b>🛠️ For developers</b></summary>

### Build

Requirements: JDK 17 and the Android SDK (platform 35). Android Studio includes both.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

### How it works

| Piece | File |
| --- | --- |
| Watches the active media session (any music app) | `media/MediaListenerService.kt` |
| Finds and caches synced lyrics from LRCLIB | `lyrics/LyricsRepository.kt` |
| Parses LRC and finds the current line | `lyrics/LrcParser.kt` |
| Shared state for phone and car (song, lyrics, current line) | `lyrics/Karaoke.kt` |
| Phone UI and automatic Car view (Jetpack Compose) | `ui/App.kt` |
| Optional Android Auto display (Car App Library templates) | `car/LyricsScreen.kt` |

Car view turns on automatically through `CarConnection` when the phone is projecting to Android Auto. The car-display screen keeps the template's title and row count fixed, so each line change counts as a *refresh* and stays within Android Auto's template limits.

### Demo without a music app (debug builds)

```bash
adb shell cmd notification allow_listener com.lyricsmatch/com.lyricsmatch.media.MediaListenerService
adb shell am broadcast -n com.lyricsmatch/.DemoPlayerReceiver --es title "Africa" --es artist "Toto" --el startMs 60000
```

</details>
