# i3 Dash

A personal fork of [AAIdrive](https://github.com/BimmerGestalt/AAIdrive) (MIT licence, by Walter Huf and contributors)
that adds **i3 Dash**: one full-screen app in the BMW i3's ConnectedDrive menu showing

- your i3 (in your paint colours) with live battery %, range, speed and drive/regen
- journey distance and efficiency, Eco Pro range gained, the car's average consumption
- a 3D sat nav using free open-source maps (OpenFreeMap / OpenStreetMap) and OSRM routing,
  with spoken directions, arrival time and battery % on arrival

## Using it
- Turn the **iDrive knob** to move between pages: Car, Battery, Journey, Map, Paint.
- On the Paint page, turning the knob changes the car's colours.
- **Press the knob** for the menu: search for a destination, stop or recalculate the route.

## Installing
Download `i3-dash.apk` from the **i3dash-latest** release on your phone and open it.
Uninstall the original AAIdrive first (they share an app ID). Plug the phone into the car's
USB with MyBMW installed and logged in, as before.

## Building
GitHub Actions builds it automatically on every push to `main` (`.github/workflows/i3dash.yml`).
The signing key `i3dash-debug.keystore` is a personal debug key so updates install over each other.
