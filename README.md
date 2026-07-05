# OpenFugu

OpenFugu is an unofficial, open-source Android app for the **eFugu** freediving pressure
device. It connects over Bluetooth LE, streams real-time nasal pressure, and turns
equalization practice into exercises and games that reward control, not force. Built in
Kotlin and Jetpack Compose.

> [!IMPORTANT]
> **Not affiliated with eFugu.** This is a community-built app for hardware you own — it is
> **not endorsed by or connected to** the makers of the eFugu device. The device is sold at
> [shop.justfreedive.com/efugu](https://shop.justfreedive.com/efugu/) and the official app
> is on [Google Play](https://play.google.com/store/apps/details?id=com.go2deep.efugu). All
> product and company names are the property of their respective owners.

> [!WARNING]
> **Safety.** Freediving and equalization training carry real risks. Forced or excessive
> pressure can injure your ears and sinuses. This app is a training aid, not medical advice
> — never push to your maximum, and train under appropriate supervision. Never freedive
> alone, and get proper training from a certified instructor.

## Features

- **Multi-device BLE** — connect to several eFugu devices at once (up to ~7 concurrent).
  Auto-scan on launch and auto-connect to your most recently used device.
- **Live pressure** — large relative-pressure readout (above an auto-calibrated ambient
  baseline), running minimum and maximum, and a live chart you can pause, scroll, and
  zoom. Each device gets its own color and mini-chart when several are connected.
- **User profiles & calibration** — per-user profiles paired to devices, with a guided
  5-step **calibration wizard** (minimum equalization pressure, comfortable maximum
  positive/negative holds). There is no "active user" — settings follow the selected
  device's paired user.
- **Exercises**
  - **Minimum Equalization Pressure** — find the *smallest* pressure that reliably
    equalizes; scored on consistency (low variation), not peak height.
  - **Constant Equalization** — hold pressure inside a sub-maximal target range
    (mouthfill-style); scored on time-in-range and streaks.
- **Games** — pressure controls *position only*:
  - **Fugu Reef** — thread your fugu through scrolling reef gaps.
  - **Fugu Feast** — eat smaller fish to grow, avoid bigger ones.
  - **Fugu Cave** — navigate a narrowing procedurally generated cave.
  - **Fugu Flow** — a rhythm game; trace a scrolling target pressure curve.
  - **Multiplayer Fugu Reef** — 2–7 players race the same course, last fugu standing.
- **Sessions** — exercises and games auto-save. Replay them on a full chart with
  exercise-specific overlays, see your stats, and share or delete them.

## Safety by design

Pressure training should never reward pushing harder. OpenFugu enforces this in code:

- In every game, pressure maps **only to vertical position** — never to speed, power, or
  score. The most precise player wins, not the one pushing hardest.
- Game ranges are **capped by your calibration**: the automatic range targets ~80% of
  your calibrated comfortable maximum, and even a manual range can never exceed the
  calibrated value itself.
- The Minimum Equalization Pressure exercise rewards the **smallest, most consistent** pressure.
- Calibration measures a *comfortable* hold and explicitly warns against pushing to your
  absolute maximum.

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3), `StateFlow`/coroutines, `AndroidViewModel`.
- Raw Android `BluetoothGatt` (no third-party BLE library).
- `minSdk 35` (Android 15), `targetSdk 36`. Portrait orientation.

## Building

You'll need a recent **Android Studio** (or a JDK 17+ and the Android SDK). Then, from the
project root:

```bash
# Compile (fast)
JAVA_HOME=<path-to-jdk> ./gradlew compileDebugKotlin

# Build a debug APK
JAVA_HOME=<path-to-jdk> ./gradlew assembleDebug
```

Or simply open the project in Android Studio and run it on a device with Bluetooth LE.

### Release builds

Debug builds need no setup. To build a **signed release APK** you need your own signing
keystore: copy [keystore.properties.example](keystore.properties.example) to
`keystore.properties` (gitignored), point it at your keystore, then run:

```bash
JAVA_HOME=<path-to-jdk> ./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Without a `keystore.properties`, `assembleRelease` produces an unsigned APK.

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — app structure, data flow, navigation, design decisions.
- [IDEAS.md](IDEAS.md) — a loose collection of ideas: some done, some planned, some just maybes.
- [PROTOCOL.md](PROTOCOL.md) — reverse-engineered BLE services, characteristics, and the
  pressure data format.
- [PRIVACY.md](PRIVACY.md) — privacy policy: everything stays on your device.

## Contributing

Contributions are welcome — bug reports, ideas, and pull requests. See
[CONTRIBUTING.md](CONTRIBUTING.md) for how to get started and how contributions
are licensed.

## License

OpenFugu is free software licensed under the **GNU General Public License v3.0**,
with an [additional permission](LICENSE-EXCEPTION) (GPLv3 section 7) allowing
distribution through app stores. See [LICENSE](LICENSE) for the full license text.
