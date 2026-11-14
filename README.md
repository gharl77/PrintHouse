# PrintHouse

A photo frame for an Android tablet that shows your [Immich](https://immich.app) photos as
prints on matte paper behind a mat, not as pictures on a screen.

PrintHouse is a small native app (Kotlin, no dependency, about 100 KB). It talks to your Immich
server directly with an API key, stays on all day, sleeps at night, and is set up from a web
page served by the tablet itself.

## What it does

- **Print look** — a black or cream mat with a bevelled window, lifted blacks, paper-white
  highlights, slightly held-back colours and paper grain. Photos are never cropped.
- **Sources** — whole library, favourites or chosen albums; filter by people and by period.
- **On this day** — mixes in photos taken on today's date in past years ("5 years ago").
- **Series** — now and then, a photo is followed by a few more from the same day.
- **Two portraits side by side** in the same mat.
- **Not-a-photo filters** — skips screenshots, tiny images and, optionally, images with no camera.
- **Offline reserve** — the last 80 photos stay on the tablet and keep the frame going when
  Immich is unreachable.
- **Screen** — always on during the day, brightness by time of day, real sleep at night with
  automatic wake-up, starts at boot, can be the tablet's home app.
- **Home Assistant** — reads a presence sensor with a long-lived token to turn the screen off
  when the room is empty; optionally appears as an MQTT device (screen switch, next/previous
  buttons, current photo as image and caption, brightness, source, share of memories).
- **Web settings page** — every setting, in French or English, with an optional PIN, backup and
  restore, and updates by APK upload or from a GitHub repository.

## Gestures on the tablet

| Gesture | Action |
| --- | --- |
| Tap, or swipe left | Next photo |
| Swipe right | Previous photo |
| Swipe up | Add to Immich favourites |
| Swipe down | Photo details (date, place, people, albums) |
| Double tap | Set the photo aside (it will not come back) |
| Long press | Settings |

## Setup

1. Install the APK on the tablet (Android 8.0 or later) and open PrintHouse.
2. In Immich, create an API key (Account Settings → API Keys) with `asset.read`, `asset.view`
   and `album.read`. Add `person.read` to filter by people and `asset.update` to add favourites
   from the frame.
3. Long-press the screen, or open the address shown on the tablet (`http://<tablet>:8080`) from
   a computer, and enter the Immich address and the key.

The API key, the Home Assistant token, the MQTT password and the PIN are encrypted on the
tablet with the Android Keystore and are never sent back to the settings page.

## Build

```
./gradlew assembleRelease
```

Needs JDK 17 and the Android SDK (`sdk.dir` in `local.properties`). The release build is signed
with the development key, which is enough to install on your own tablet over USB:

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

## License

MIT — see [LICENSE](LICENSE).
