# Changelog

Versions are numbered year.month.number.

## 2026.11.5

- The settings page stays reachable: the frame now keeps the tablet's Wi-Fi from filtering the
  network's broadcast messages, which left computers unable to find the tablet.
- Themes to set aside: what Immich recognises as "screenshot", "document"… no longer shows.
- People can be seen more or less often than the others: tap a name on the settings page.
- Photos favourited from the frame come back now and then.
- Update from the web page: a clear warning that Google Play Protect often refuses it, and what
  to do instead.
- Counters only count photos that were actually shown.

## 2026.11.4

- Fixed a frame that stayed black for hours. Once the screen had gone off during the day for
  any reason (the power button, another window), it could not stay on again: Android only
  honoured the window's "keep the screen on" request after the next layout pass, and the screen
  timed out ten seconds after every wake-up, until a full night had passed. The frame now holds
  the screen on with a lock of its own.
- If the tablet turns its screen off during the day without the frame asking for it, the frame
  turns it back on within seconds and brings itself to the front.
- The log now records these unexpected switch-offs, with whether the frame was in front and
  holding the screen on, and when another window comes in front of the frame.

## 2026.11.3

Fixes for a frame that looked frozen in the morning.

- The search for "On this day" memories no longer holds up the next photo. It ran dozens of
  requests before the first photo of the day (up to 180 with several people chosen), leaving the
  frame unresponsive for about half a minute; it now runs in the background and is capped at 50.
- Touching a black frame turns it back on: until the presence sensor reports the room empty
  again, or for two minutes at night. Before, a frame switched off by the presence sensor stayed
  black, at minimum brightness, until the sensor noticed you.
- When the frame turns back on (morning, someone coming in) it comes to the front, even if
  another window had taken its place, and greets you with a new photo.
- The morning wake-up no longer lights the screen in an empty room.
- The presence sensor is read every 30 seconds at night instead of every 2.
- At most a handful of requests are made to Immich for one photo, however tight the filters.
- A short "One moment…" appears when a photo takes more than two seconds to arrive.

Also in this version:

- Log: the web page shows what happened to the frame — screen off or back on and why (night,
  empty room, touch), errors, slow photos — so a black or frozen frame can be explained without
  plugging the tablet into a computer.
- Ages: shown for people under 18 by default (adjustable), and only when the photo's date comes
  from the camera, which leaves out scanned prints.
- Wake by camera (off by default): when the frame is off because the room is empty, the front
  camera watches for movement and turns it back on without waiting for the presence sensor.
- Themes: photos found by their content ("dog", "landscape") with Immich's smart search.
- People, themes and albums are now three lists that add up, each with its share of the
  slideshow. Before, choosing people excluded everything else, and albums and people narrowed
  each other.
- Guest mode: one switch, on the web page or in Home Assistant, limits the frame to one album.
- Different night hours on Friday and Saturday nights.
- Three square photos are grouped in one mat, like two portraits.
- "On this day" uses Immich's own memories in a single request when no album or people filter
  is set and the API key has `memory.read`.
- The choice of photos was moved into a module of its own and is now tested against a fake
  Immich that counts requests.

## 2026.11.2

- Birthdays: on someone's birthday the frame shows more of their photos, and the caption can
  give people's age at the time of the photo ("Emma, 3 years old"), from the birth dates in
  Immich.
- Season: photos from the same time of year in past years.
- A photo is not shown again for a week (adjustable).
- Paper look: a slider from the photo as it is in Immich to a very matte print.
- Web page: live preview of the frame, the latest photos shown with Favourite and Set aside
  buttons, and counters (memories, series, images skipped by the filters).
- If the app crashes, it restarts by itself.
- Home Assistant (MQTT): battery level and charging state of the tablet.
- Unit tests, including a check that every text has its English translation.

## 2026.11.1

First public version.

- Immich photos shown as matte prints behind a black or cream mat, in landscape, never cropped;
  two portrait photos side by side.
- Sources: whole library, favourites or chosen albums; people and period filters, chosen with
  tokens and a search field, with faces from Immich.
- "On this day" memories, and series of photos from the same day.
- Not-a-photo filters: screenshots, images under 1000 pixels and, optionally, images with no
  camera.
- Gestures: next and previous photo, swipe up for Immich favourites, swipe down for the photo's
  details, double tap to set a photo aside; photos set aside can be put back one by one.
- Screen always on during the day, brightness by time of day, real sleep at night with
  automatic wake-up, start at boot, optional PIN lock on the tablet.
- Offline reserve of the last 80 photos.
- Home Assistant: a presence sensor read with a long-lived token turns the screen off when the
  room is empty; optional MQTT device with a screen switch, photo buttons, the current photo as
  image and caption, brightness, photo source and share of memories.
- Web settings page in French and English, with an optional PIN, backup and restore, and
  updates by APK upload or from a GitHub repository.
