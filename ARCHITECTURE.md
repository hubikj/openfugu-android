# OpenFugu — Architecture

## Overview

OpenFugu is an open-source Android app for the eFugu freediving BLE pressure training device. The app connects to one or more eFugu devices via Bluetooth LE, reads real-time nasal pressure data, and provides games, exercises, and visualization tools for equalization training.

The app is built with Kotlin and Compose Multiplatform (Material 3), targeting modern Android (API 35+). There are no XML layouts — all UI is Compose. The code is split into a platform-neutral `shared` module (Kotlin Multiplatform, ready for a future iOS target — see IDEAS.md) and a thin Android `app` module.

## Project Structure

```
shared/                          — Kotlin Multiplatform module (Compose Multiplatform)
├── src/commonMain/kotlin/org/hubik/openfugu/
│   ├── AppSettings.kt           — app-level settings model (theme, developer options)
│   ├── PressureChart.kt         — Unified chart (pause/zoom/scroll, overlays)
│   ├── DeviceCard.kt            — Device card (used by Live and Devices tabs)
│   ├── ExercisesTab.kt          — Game/exercise catalog, per-launch device picker, history
│   ├── DevicesTab.kt            — Scan/connect UI, saved devices, device picker/edit dialogs
│   ├── UsersTab.kt              — User profile list, paired devices
│   ├── LogsTab.kt               — Debug log display, copy/save
│   │
│   ├── ble/                     — Pressure sources and BLE-independent device model
│   │   ├── Models.kt            — ScannedDevice, SavedDevice, PressureReading, ScanState, DeviceColors
│   │   ├── PressureSource.kt    — Abstract source: shared ingestion pipeline (calibration, history, chart)
│   │   ├── KableDeviceConnection.kt — PressureSource over Kable (multiplatform BLE)
│   │   ├── MockDeviceConnection.kt — Simulated PressureSource (20 Hz ticker, slider/sine driven)
│   │   ├── EFuguIds.kt          — BLE service/characteristic UUIDs (kotlin.uuid)
│   │   ├── UserProfile.kt       — User profile + device-user pairing data classes
│   │   ├── PeakDetector.kt      — Peak detection for min EQ calibration
│   │   └── SustainedPressureDetector.kt — Sustained hold detection for max pressure
│   │
│   ├── session/                 — Session recording (platform-free)
│   │   ├── Session.kt           — Data model (sealed class: MinEq, ConstantEq, Game sessions)
│   │   ├── SessionJson.kt       — Pure JSON (de)serialization (unit-tested round-trips)
│   │   └── SessionRepository.kt — Save/load/list/delete over the FileStore interface
│   │
│   ├── exercise/                — Training exercises (MinEq, ConstantEq, RangeTracker)
│   ├── game/                    — All games + GameUtils/MultiplayerGameUtils
│   ├── storage/Stores.kt        — KeyValueStore + FileStore interfaces
│   ├── ui/                      — SharedComponents (AppColors, rows, dialogs),
│   │   │                          CanvasText (portable canvas text), SettingsScreen
│   │   └── theme/               — Color.kt, Type.kt (Theme.kt itself is Android-side)
│   └── util/                    — fmt/formatMinSec, JSON accessors, time formats,
│                                  nowMillis, AppLog (expect)
├── src/androidMain/kotlin/…     — SharedPrefsStore, AndroidFileStore, AppLog (actual)
└── src/androidHostTest/kotlin/… — all unit tests (JVM)

app/                             — Android application shell
└── src/main/java/org/hubik/openfugu/
    ├── MainActivity.kt          — Activity: permissions, import intents, theme, overlays
    ├── EFuguApp.kt              — Root composable: tab scaffold, full-screen routing
    ├── LiveTab.kt               — Live tab panels (ViewModel-driven)
    ├── ble/
    │   ├── EFuguViewModel.kt    — Central state management (AndroidViewModel, scanning)
    │   ├── DeviceConnection.kt  — Legacy PressureSource over Android BluetoothGatt
    │   └── EFuguUuids.kt        — java.util.UUID copies of EFuguIds for the legacy stack
    ├── session/SessionViewerScreen.kt — Full-screen replay (share via FileProvider)
    └── ui/                      — MockDeviceOverlay, UserDetailScreen, CalibrationWizard,
        └── theme/Theme.kt       — Material theme with Android dynamic color
```

**Module rule:** anything that imports `android.*` (or needs `EFuguViewModel`) lives in `app`; everything else goes to `shared/commonMain`. Platform needs of common code are expressed as interfaces (`storage/Stores.kt`), `expect`/`actual` (`util/AppLog.kt`), or constructor-injected lambdas (log saving, session import).

## Data Flow

### Source → UI Pipeline

```
eFugu device (BLE notification,     MockDeviceConnection ticker
ASCII Pascals, ~20 Hz, via          (20 Hz coroutine on Dispatchers.Main,
DeviceConnection OR                 slider/sine target + low-pass + noise)
KableDeviceConnection —               ↓
see "Bluetooth engine" below)         ↓
  ↓  (parse ASCII,                    ↓
  finite values only)                 ↓
  └────────────→ PressureSource.ingestPressureHPa() ←──────────┘
  ↓ auto-calibrate ambient baseline (first 20 samples), subtract → relativeHPa
PressureReading(pressureHPa, relativeHPa, timestamp)
  ↓ ring buffer (60 min) + StateFlows
latestPressure (every sample)     chartData (immutable snapshots)
  ↓                                ↓
Display and games collect via     PressureChart renders the visible
collectAsState() (conflated       window (binary-searched slice)
per frame); sample-counting
detectors (PeakDetector,
SustainedPressureDetector)
collect the flow directly in a
LaunchedEffect so no samples drop
  ↓
Games read latestPressure each frame;
session traces come from historySnapshot() at save time
```

The algorithmic core (detectors, RangeTracker, range derivation, game
mapping, session JSON) is unit-tested in `shared/src/androidHostTest/`, and its behavior
is specified platform-neutrally in [SPEC.md](SPEC.md).

### State Management

`EFuguViewModel` (AndroidViewModel) is the single source of truth:

| State | Type | Persistence |
|-------|------|-------------|
| `connections` | `Map<String, PressureSource>` | In-memory only |
| `savedDevices` | `List<SavedDevice>` | SharedPreferences (JSON) |
| `userProfiles` | `List<UserProfile>` | SharedPreferences (JSON) |
| `deviceUserPairings` | `List<DeviceUserPairing>` | SharedPreferences (JSON) |
| `recentSessions` | `List<SessionIndexEntry>` | JSON files (app internal storage) |
| `scanState` | `ScanState` | In-memory only |
| `scannedDevices` | `List<ScannedDevice>` | In-memory only |
| `logMessages` | `List<String>` | In-memory only |
| `appSettings` | `AppSettings` | SharedPreferences (JSON) |
| `userMessages` | `SharedFlow<String>` | One-shot snackbar messages (shown by MainActivity's root host) |

Each `PressureSource` (BLE `DeviceConnection` or simulated `MockDeviceConnection`) owns its own state:

| State | Description |
|-------|-------------|
| `state` | Connecting / Connected / Disconnected / Error |
| `latestPressure` | Most recent PressureReading (null until calibrated) |
| `chartData` | Rolling 60-minute pressure history (~72000 samples at 20 Hz) |
| `chartMin` / `chartMax` | Running min/max of relative pressure |
| `batteryLevel` | 0-100% (Battery Service on real devices; fixed 100 for simulated) |
| `deviceInfo` | Firmware, hardware, serial, manufacturer |
| `isCalibrated` | True after ambient baseline is established (~20 samples) |

## Navigation

The app uses a flat navigation model — no navigation library. Full-screen routing is handled by state flags in `EFuguApp`:

```
EFuguApp {
  if (showLogs)           → full-screen LogsTab with back button
  if (showSettings)       → full-screen SettingsScreen
  if (showUserDetail)     → full-screen UserDetailScreen
  if (viewingSessionId)   → full-screen SessionViewerScreen
  if (calibratingUserId)  → full-screen CalibrationWizard
  if (activeGame)         → full-screen game/exercise (no chrome)
  else                    → Scaffold with bottom tabs
}
```

Bottom tabs: **Live** | **Exercises** | **Devices** | **Users**

Logs and Settings are accessible from top-right icons on the main screen.
The Logs screen header shows the app version (passed down from
`BuildConfig`), and the version is also the first line logged on startup so
it rides along in copied logs. Settings hold the theme (System/Light/Dark),
developer options (show simulated devices, Bluetooth engine), and the About
links; they persist as JSON under the `app_settings_json` preference key.

### First-run guidance

There is no stored "onboarded" flag — guidance derives from state:
- No saved devices → the app starts on the Devices tab, which shows a
  welcome card (auto-scan already runs on open).
- A device connects while no user profiles exist → a dialog offers to
  create the first user and pairs it to that device.
- After creating a user (from the first-run dialog or the Users tab), a
  dialog offers to launch the calibration wizard.

### Session import

`MainActivity` runs as `launchMode="singleTask"` and registers `ACTION_VIEW`
/ `ACTION_SEND` intent filters for `application/octet-stream` — sessions are
shared as `.fugu` files (JSON inside), which receiving apps resolve to that
MIME type. Incoming intents (onCreate or onNewIntent) flow into `EFuguApp`
as Compose state; `EFuguViewModel.importSession` validates and saves the
session into history, then the standard session viewer opens it. Foreign
files are rejected with a snackbar message.

## Key Design Decisions

### Multi-device from the start
`connections` is a `Map<String, PressureSource>` — every device has independent state. The UI adapts: single device = full panel, multiple = scrollable compact cards.

### Pressure sources are abstract
Everything above the BLE layer depends on `PressureSource`, never on a concrete connection class directly. The base class owns the ingestion pipeline (ambient auto-calibration, history ring buffer, chart snapshots, running extremes — unit-tested in `PressureSourceTest`), so both sources behave identically downstream. `MockDeviceConnection` (addresses `MOCK-1`, `MOCK-2`, …) is a simulated source: added from an unobtrusive button at the bottom of the Devices tab, saved and user-paired like real hardware, driven by floating sliders (`MockDeviceOverlay`, mounted in `MainActivity` above `EFuguApp` so it draws over every screen including games) with a per-device sine-wave mode for hands-free demos. Simulated devices need no Bluetooth at all — the app is fully explorable on the emulator, including multiplayer games with several mocks. The app is fully explorable on the emulator, including multiplayer games with several mocks. The "Add simulated device" button is gated behind the settings toggle "Show simulated devices".

### Two Bluetooth engines
Real devices connect through one of two `PressureSource` implementations, selected by the "Bluetooth engine" developer setting (applies to new connections):
- **Android** (default): `DeviceConnection` in the app module — the proven BluetoothGatt implementation (MTU 517, main-thread-confined GATT callbacks).
- **Kable**: `KableDeviceConnection` in shared commonMain over the Kable multiplatform BLE library — the bring-up path for iOS. Scanning switches with it (one unfiltered Kable scan replaces the two legacy scans).
Both run the same sequence: subscribe pressure/battery notifications, read device info, send the 128-byte auth challenge (see PROTOCOL.md).

### User ≠ Device
Users (profiles with calibration data) and devices (BLE hardware) are separate entities linked by `DeviceUserPairing`. One user can be paired to multiple devices. In a group setting, an instructor can quickly reassign users to different devices via the device picker.

### Calibration is per-user, not per-device
Calibration results (min EQ, max positive, max negative) are stored on `UserProfile`. When launching a game, the app looks up the user paired to the selected device to get pressure range and expert mode settings.

### Unified PressureChart
One chart composable used everywhere (Live tab, calibration wizard, exercises). Optional overlay parameters add exercise-specific features (peak markers, target range, scoring-colored line segments) without separate chart implementations.

### Game pressure mapping
All games use `calculateTargetY()` from GameUtils.kt. Normal mode: 0 hPa = bottom, pressureRange = top. Expert mode: 0 hPa = center, positive maps upper half, negative maps lower half (asymmetric ranges).

### No "active user" concept
User context flows from device pairing. Games/exercises get their settings from `viewModel.userForDevice(address)` based on which device is selected. The Users tab shows a list — tapping a user opens their detail screen directly.

### One card per game — players chosen at launch
The Exercises tab has no separate multiplayer section. Each game/exercise is one catalog entry (`ExerciseEntry` in ExercisesTab.kt) declaring a player range; a future game needing, say, exactly two players just sets `minPlayers = 2, maxPlayers = 2`. With one device connected, tapping a card launches on it immediately (entries declaring `minPlayers > 1` open the picker instead). With several, a device picker opens per launch — checkboxes for multiplayer-capable games (picking one device runs the single-player version), radio buttons for single-player-only entries. The picker pre-checks the devices from the previous launch (state hoisted to `EFuguApp` so it survives games and tab switches). Routing keys off the selection count: `activeGameDeviceAddresses.size == 1` renders the single-player screen, `>= 2` the multiplayer one. Calibration-gated entries (Constant Equalization) grey out the card when the single connected device's user lacks calibration, or grey out individual devices inside the picker when several are connected.

## Persistence

All JSON is written and parsed with kotlinx.serialization's `JsonElement` API behind org.json-style accessors (`util/Json.kt`); the on-disk schema predates that switch and is guarded by round-trip and legacy-file tests.

**SharedPreferences via `KeyValueStore` (JSON strings)** — small, frequently accessed data:
- `SavedDevice` — address, name, nickname, color, lastConnectedAt
- `UserProfile` — calibration data, game range settings, expert mode
- `DeviceUserPairing` — device address ↔ user ID

**File storage via `FileStore`** (`context.filesDir/sessions/`) — large session recordings:
- One JSON file per session (`session_{id}.json`) with full pressure trace
- Index file (`sessions_index.json`) for fast listing without loading traces
- Writes are atomic (temp file + rename); all I/O runs on Dispatchers.IO behind a
  mutex; serialization lives in `SessionJson` (pure, covered by round-trip tests)
- Auto-cleanup: keeps last 50 sessions, deletes oldest on save
- Session types: `MinEqSession` (with peak markers), `ConstantEqSession` (with range/scoring data), `GameSession` (with score and pressure bounds), `MultiplayerGameSession` (per-player results)
- Pressure traces are filtered to the exercise/game time window, not the full rolling buffer

## BLE Protocol

See [PROTOCOL.md](PROTOCOL.md) for full BLE protocol documentation.

Key points:
- Pressure data comes from a custom service (not the auth service)
- ASCII Pascals at ~20 Hz, no CCCD write needed
- Auth is device-proves-identity via RSA signature (not required for pressure)
- The `dcdf` characteristic purpose is still unknown

## Technology Stack

- **Language:** Kotlin (Multiplatform: `shared` commonMain/androidMain + Android `app`)
- **UI:** Compose Multiplatform + Material 3 (dynamic color on Android)
- **State:** StateFlow (Kotlin Coroutines)
- **Serialization:** kotlinx.serialization (JSON)
- **Dates/times:** kotlinx-datetime
- **Storage:** SharedPreferences + files behind `KeyValueStore`/`FileStore`
- **BLE:** Android BluetoothManager/BluetoothGatt (default) or Kable (multiplatform), switchable in settings
- **Lifecycle:** AndroidViewModel (app module)
- **Min SDK:** 35 (Android 15)
- **Build:** Gradle with Kotlin DSL; AGP 9.1 + `com.android.kotlin.multiplatform.library` + Compose Multiplatform plugin
