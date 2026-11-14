# Changelog

Versions are numbered year.month.number.

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
