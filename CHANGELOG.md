# Changelog

Newest first. The app is built automatically and published as `i3-dash.apk` on the
[`i3dash-latest` release](https://github.com/uknsaunders/idash/releases/tag/i3dash-latest), so the date below is when each change was made.

## 14 November 2026, what3words moves to the phone

- **what3words key, entered on the phone:** Send to car now has a what3words box, saved and tested the same way as the Google and Open Charge Map keys. It was previously a GitHub repository secret baked into the build, which meant it sat inside the public, downloadable APK. All three keys now stay on the phone only. Building your own copy with a repository secret still works, as a fallback, but is no longer the suggested way.

## 14 November 2026, cards, Settings as a menu, and charger availability

- **Settings is now a menu, not a single screen:** press the Settings card for a list - Display, Car colour, Battery and Video - a home for the smaller, less-used things. Turning the knob on the Settings screen no longer cycles the car colour directly; that is now one of the menu rows.
- **Call and Music menus are cards:** the top-level choices - Recents, Speak a name, All contacts, Type a name for Call; your apps for Music - show as cards, like the home screen. Anything you'd scroll through (the Recents list, All contacts, a browsed playlist) stays a plain list.
- **Music: apps first.** Pressing Music shows your installed apps as cards, so you choose what to play before anything else. **Resume** - what was last playing, with pause, skip, forward and back - is a later card, not the first one offered.
- **Chargers: live availability (optional).** With a free Open Charge Map key saved on the phone, the Chargers list shows what's reported available, in use or faulted, and the power rating, alongside the distance - not just where chargers are.

## 14 November 2026, new layout and colours

- **Home screen:** the top row is phone and media (Navigate, Call, Alerts, Music) and the bottom row is the car (Car, Journey, Chargers, Settings). The car row's icons are amber so the two rows are easy to tell apart.
- **Navigate is the map:** the separate Map card has gone. Navigate opens the full-screen map, and it stays full screen after you choose a place.
- **Day colours:** in daylight the whole screen is now light, white and silver with dark text and BMW blue, so it can be read in sunshine. At night it stays dark. It follows the sun, as the map already did.
- **Settings:** a new card. Display can be Automatic, Day or Night. The car colour and the battery details live there too.
- Tiles and list rows are squarer with a soft gradient, closer to the look of iDrive.

## 14 November 2026, after the Android Auto and CarPlay review

First batch of changes from comparing i3D with Android Auto and Apple CarPlay. None of it has been tried in the car yet.

- **While driving:** message text stays off the screen (the sender still shows, and it can be read aloud), and the All contacts list stops at 12 names.
- **Message banner:** a new message drops in at the top for a few seconds on any screen. Press the knob to hear it, answer it or clear it.
- **Replies:** answer a message with one of three set replies or by voice, for apps whose notifications allow a reply (WhatsApp, Messages and similar). A spoken reply is shown for you to confirm before it is sent.
- **Voice:** "Speak a place" in Navigate and "Speak a name" in Call, using the phone's microphone and Android's speech recognition. Spoken places show a short list to choose from.
- **Next appointment:** a calendar entry for today that has a place appears in Navigate. Choosing it looks the place up.
- **Missed calls:** a badge on the Call card, and missed callers first in the Call list.
- **Home screen:** while a route is running, the next turn and arrival time show above the cards.
- **Music:** Forward 30 seconds and Back 15 seconds, for podcasts.
- The phone's setup list has a new item, "Voice and calendar".
- **Chargers replaces Battery:** the Battery card has gone, because Car and Journey already cover it. Chargers lists the nearest charging points with distance and number of points, and a press drives to one. Battery temperature, 12 V battery and energy left moved to Car colour and info.

## 14 November 2026

- **Better address finder:** place search now forgives spelling. If the exact OpenStreetMap search finds nothing, a second, looser search runs, and far-away matches are dropped when there are nearby ones.
- **Google place search (optional):** paste your own Google Places key into Send to car on the phone and the search finds businesses by name, even misspelt, in the car and on the phone. The key is tested before it is saved and stays on the phone.
- With the car not connected, searches from the phone now look near your saved Home first.
- **Desk simulator:** `tools/simulator` builds a page that runs the car screen at a desk with a stand-in phone.

## 13 November 2026, evening

After the drive with the home screen.

- **Sections without buttons:** the row of buttons at the top of each section has gone. Each section shows its name, and a press of the knob gives its list, which always starts with "‹ Home screen". Battery and Journey have nothing to choose, so one press goes straight home. Turning the knob no longer moves between sections.
- **Fixed: no call details during a call.** The call panel relied on the phone's call notification, which needs notification access. The dash now also remembers the call it placed and reads the phone's own call state, so a call shows with the name, number and running time, and Hang up, even without that access. A new call moves the dash to the Call section.
- **Hang up:** during a call, a press of the knob offers Hang up first, in every section.
- **More than one number:** a contact with several numbers now asks which one to dial (Mobile, Work and so on). The letter wheel search shows the kind of number too.
- **Fixed:** the next-turn banner and the now-playing strip could overlap at the top of the map.
- The Calls permission now also asks for the phone state, which is how the dash knows a call is under way.

## 13 November 2026

After the first drive with the in-dash menu.

- **Fixed: no map in daylight.** The map only had a dark style, which can't be seen on the car's screen in sunshine. It now has a light daytime map and switches to dark at night by itself, from where the sun is.
- **Home screen:** i3D opens on a full screen of cards (Navigate, Call, Music, Map, Car, Battery, Journey, Alerts) with live details on each. Turn to choose, press to open.
- **Menu on the left:** the knob-press menu now takes the left half, seven rows tall, and the map stays on the right at full size. "Home screen" is the first row and every list starts with "‹ Menu", so there is always a way back.
- **Call:** a Call section and card. The list shows favourites, recent calls and all contacts, where it used to show only starred contacts (and nothing if you had none).
- **Easier font:** the dash now uses Inter throughout. Aptos belongs to Microsoft and can't be shipped in the app, and Inter is the closest free match.
- **Slicker permissions:** the phone's setup list now walks through Android's "Restricted setting" block in three numbered taps, each opening the right screen.
- **Crash on car stop:** the shutdown order is safer, and every step is guarded. If the app ever stops unexpectedly, the phone's home screen offers a "Send the report" button next time, so the real cause can be found.
- **The globe logo:** it is the car's own ConnectedDrive icon, drawn over every app. An app cannot remove it.

## 12 November 2026

- **Map stays live with the menu open:** the menu takes the top right and the map shrinks to the bottom right quarter beneath it, still showing your position, next turn, arrival time and speed limit. On the Map page the map keeps the whole screen.
- **One place for everything (new, untested in the car):** the knob press now opens a menu inside the dash, where it used to jump to the car's own menu. Navigate, Call, Music, Video and Read alerts aloud are all there, and the knob scrolls and selects. Pressing on the Music page goes straight to music, and on the Map page straight to places.
- **Straight in:** choosing i3D in the car opens the dash directly, with no menu screen first. The car entry is now named i3D.
- **Calls:** a ringing call shows only Answer and Decline. When the car switches to its own phone screen as a call connects, i3D brings the dash back once.
- **Music controls:** pause, next and previous from the menu.
- **Video from the car:** videos sent from the phone are remembered and can be started from the menu while parked.
- **Phone app:** one "Finish setting up" list with ticks replaces hunting for permissions. The original AAIdrive apps moved to the bottom as optional.
- **Fixed:** choosing a contact from the search suggestions did nothing and cancelled the route. It now rings them.
- **YouTube, parked only (new, untested in the car):** share a YouTube link to i3D or paste it into Send to car, then Play on car. It plays only while the car is stationary, stops when the car moves, and the knob stops it.
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
