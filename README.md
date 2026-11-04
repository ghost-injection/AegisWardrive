# AEGIS//WARDRIVE

Passive WiFi survey instrument for Android. Sweeps, scores, maps and exports every
access point your radio can hear — then tells you which ones are worth caring about.

No root. No injection. No monitor mode. It listens to what the OS already broadcasts.

```
NODES 79   OPEN 0   WEP 0   PRIME 54   HIDDEN 9   GPS LOCK
```

## What it does

**Sweep** — continuous `WifiManager` scans through a foreground service, deduplicated
by BSSID and persisted to SQLite. Survives screen-off and reboots.

**Score** — every hit is graded on security posture, not just signal. WEP and open
networks rise to the top, WPS and WPA3-transition flags raise risk, enterprise auth
lowers it. `PRIME` is the filtered shortlist.

**Catch hidden networks** — hidden SSIDs are tracked in a `was_hidden` column, so once
a probe response leaks the name you keep the association forever: `HIDDEN` counts them,
`REVEAL` shows the unmasked set.

**Locate** — GPS-tagged observations plus a multilateration estimate for nodes seen
from multiple positions. `MAP` renders the survey, `RadarView` gives range and bearing
to the selected target, `SpectrumView` shows channel occupancy.

**Export** — CSV for spreadsheets and `airodump-ng` pipelines, KML for Google Earth,
GeoJSON for anything else. Import accepts existing `airodump-ng` CSVs.

**Skins** — five themes, one hue each, driving the entire UI: `NEON`, `CATHODE`,
`HACKER GREEN`, `SPECTRUM RGB`, `ELECTRIC BLUE`. Plus an optional CRT overlay.

## Filters

`ALL` · `PRIME` · `HIDDEN` · `REVEAL` · `CRIT` · `OPEN` · `WEP` · `WPA3` · `WPS` · `5G+6G` · `TAGGED`

Sort by `SCORE` · `NEAREST` · `SIGNAL` · `SEEN` · `RECENT`. Strength floor and free-text
search are independent of the filter.

## Requirements

- Android 13+ (targets API 35). Built and tested on a Galaxy S25 Ultra, Android 17.
- `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`.
- Location must be enabled system-wide or scan results come back empty. That is an
  Android platform rule, not an app bug.

## Build

The build is deliberately toolchain-minimal — no Gradle, no Android Studio:

```bash
export ANDROID_HOME=$HOME/android-sdk     # build-tools 35.0.0, platform android-35
bash build.sh
```

Output: `out/AegisWardrive.apk`, signed with a local debug keystore generated on first
run. Install with `adb install -r out/AegisWardrive.apk`.

## Layout

| File | Role |
|---|---|
| `MainActivity.java` | UI, tabs, filters, intel panel |
| `WardriveService.java` | foreground scan service, GPS, persistence |
| `Db.java` | SQLite schema v4, `hits` / `obs` / `est` |
| `WifiFacts.java` | capability decoding, security classification |
| `MapView.java` | plot, labels, multilateration estimates |
| `RadarView.java` | range and bearing |
| `SpectrumView.java` | channel occupancy |
| `CsvExporter` / `CsvImport` / `KmlExporter` | data out, data in |
| `Skin.java` / `CrtOverlay.java` | theme engine, CRT effect |
| `BootReceiver.java` | restart the service after reboot |

## Limits, stated honestly

- **Truly silent APs cannot be unmasked.** A hidden network that never answers a probe
  stays hidden at this layer. Confirming one requires monitor mode and a second radio —
  a Pi with an external adapter running `airodump-ng`, whose CSV this app can import.
- **`PRIME` is heuristic.** It flags WEP, open, confirmed WPS and WPS-suspect networks.
  Treat it as a shortlist to review, not a verdict.
- **Multilateration is coarse.** Two or three observation points give a rough fix, not
  a survey-grade one.

## Ethics

This is a passive receiver. It transmits nothing, authenticates against nothing and
attacks nothing — it reports what your own device already sees. Survey your own space,
or space you have permission to survey. Knowing which of your neighbours' networks run
WPS is a useful audit; using that knowledge against them is a crime in most
jurisdictions.

## License

MIT. See `LICENSE`.
