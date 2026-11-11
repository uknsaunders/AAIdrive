# Changelog

Newest first. The app is built automatically and published as `i3-dash.apk` on the
[`i3dash-latest` release](https://github.com/uknsaunders/idash/releases/tag/i3dash-latest), so the date below is when each change was made.

## 12 November 2026

- **Docs:** the main page now covers the i3D name, finding it under ConnectedDrive, the car menu, the music picker, calls and the current list of things still to confirm in the car.
- **Music wheel (new, untested in the car):** press the knob and scroll to Music in the i3D menu. It lists your music and streaming apps, then that app's playlists and library. Press a playlist to play it. The Music page tells you where to find it.
- **Screenshots:** added [pictures of every page](docs/SCREENSHOTS.md), plus mockups of the car menu and phone screens.
- **Standalone app:** i3 Dash now has its own app ID (`me.hufman.androidautoidrive.i3dash`), so it installs next to the original AAIdrive without uninstalling it. Settings, saved places and permissions start fresh. The new app is named **i3D** on the phone, to tell it apart from the earlier build. Uninstall the earlier i3 Dash build once this one works.

- **Clearer car menu:** the entries now read "Open i3 Dash" and "Search place or contact", the unused widescreen option is gone, and a new **Places and calls** list shows your saved places and starred contacts for one-press navigating or ringing.
- Header moved down a little so the car's own logo and status bar no longer clip the tabs and the temperature.

## 11 November 2026

**Calls, alerts and tidy-ups**
- Answer and hang up from the car: press the knob during a call and choose **Answer call** or **Hang up**.
- Dial contacts from the car: choose **Search**, type a name (or "call" and a name) and pick the "Call …" row.
- New **Alerts** page showing recent phone notifications, with a count on the tab.
- New "i3D" icon for the car's menu.
- The now-playing strip no longer covers the turn instruction, and the map credit no longer covers the arrival battery figure.
- The colour page no longer blurs the car.
- Cover art for Spotify and other players is found more reliably and resent when the page loads.
- Phone app: new **Calls and contacts** button on the home screen to grant the call permissions.

**Documentation**
- README rewritten for i3 Dash, with screenshots and diagrams. The original AAIdrive README is in `docs/UPSTREAM.md`.

## 10 November 2026

- **Send to car:** share a place from Google Maps or any app, or type a place, postcode or what3words address.
- **Saved places** and recent destinations.
- **Speed limit** sign on the map with an over-limit warning.
- **Chargers** on the map and the nearest charger on the Battery page.
- **Call panel** showing who is calling.
- **Journey costs:** cost per mile, a 14 day efficiency chart and recent trips, at your own price per kWh.
- **Phone app redesign:** clear connection card and a list of large buttons, replacing the side menu.

## 9 November 2026

- Half-and-half layout with larger text and a dark night map.
- Now-playing strip and a Music page, with quicker response to the iDrive knob.
- Weather icon beside the temperature, and an **i** page for extra information.
- Tyre warning rings that only show when a tyre is low.
- Range shown in the units the car reports.
- what3words addresses in the destination search.
- Blank caption at the top of the car screen.

## 8 November 2026

- First version: full-screen i3 dashboard and open-source sat nav as a car app, with a named entry in the car's menu.
