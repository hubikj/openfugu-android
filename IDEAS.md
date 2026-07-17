# OpenFugu — Ideas

A loose collection of ideas, not a committed roadmap: some are planned, some are just maybes. Finished work is not tracked here — it lives in git history and the GitHub release notes.

## Up Next (single-device, no extra hardware needed)

### Session Sharing Enhancements
**Goal:** More ways to capture and share sessions, beyond the auto-save and `.fugu` file sharing that already ship.

- [ ] QR code sharing — student's phone shows QR, instructor scans to receive session data (works offline, no accounts)
- [ ] Shareable link — upload session to a simple cloud endpoint, get a short URL that opens the session in-app or browser (the expected long-term sharing path once internet features land)
- [ ] Save from Live tab (manual "save session" button for free recording)
- [ ] FIT file export — save sessions as Garmin FIT files (breathwork activity type) so users can log equalization training in Garmin Connect alongside their dive/fitness data. The FIT SDK is open source and supports custom developer fields for pressure data.

**Why this matters:** Instructors currently have no way to review a student's session after it ends. This enables asynchronous coaching — student trains, saves session, sends to instructor.

---

### App-Wide Baseline Drift Monitor
**Goal:** Surface baseline drift everywhere, not just in the minimum equalization screens. The stuck-detector notice (PeakDetector, 10 s elevated) only covers peak detection — drift equally corrupts the Live chart, exercises, and games.

**Concept (from device-testing the stuck notice, 2026-07-06):**
- [ ] Detect drift in `PressureSource` itself so every screen can observe it: a `baselineDriftSuspected` StateFlow set when the smoothed relative pressure stays away from zero for a long window — noticeably longer than the peak detector's 10 seconds (e.g. 60 s) to avoid false positives during normal training
- [ ] Distinguish drift from legitimate sustained effort: drift is a *flat* elevated signal (low variance), training pressure moves — require both "away from zero for the whole window" and "nearly constant" before flagging
- [ ] Watch both directions (`|smoothed|`) — weather/elevation drift can go negative too, which PeakDetector never sees
- [ ] Live tab: warning on the device card, reuse `BaselineDriftDialog` → the Recalibrate action already there
- [ ] Exercises tab device picker: per-device drift warning so it is caught *between* games (the tab has no persistent device selector anymore — devices are picked per launch)
- [ ] Never interrupt a running game — recalibration mid-game is impossible (clears history, changes the pressure mapping); at most surface the warning at game over

---

### Game Score Leaderboard
**Goal:** Local leaderboards for game scores — see personal bests and compare between users on the same phone (family, training buddies, a class sharing an instructor's device).

**UX concept:**
- Per-game leaderboard: best scores for Fugu Reef, Fugu Feast, Fugu Cave (and future games), with user name and date
- Personal bests per user, plus an all-users view for friendly competition on a shared phone
- "New personal best!" / "New record!" celebration on the game over screen
- Entry point from the Exercises tab (alongside session history) or per-game

**Technical notes:**
- Game sessions are already auto-saved with scores — the leaderboard can be derived from the session index rather than a new data store
- Scores tie to the user via the device-user pairing at play time
- Multiplayer games could later feed the same leaderboards (one entry per player)
- Consider whether scores from different difficulty settings should rank separately

**Safety note:** Fine as-is per the design principle — game scores already reward precision and survival, never raw pressure, so a leaderboard doesn't incentivize dangerous force.

---

### Sound & Haptics
**Goal:** Audio and vibration feedback so games and exercises communicate without the user staring at the screen — useful during equalization training where visual attention is limited.

- [ ] Game sound effects — scoring, collisions, game over (and matching haptic pulses)
- [ ] Exercise cues — sound/vibration when entering or leaving the target range, on detected equalizations, and on phase changes, so exercises work eyes-free
- [ ] Countdown ticks and start signals for games and exercises
- [ ] Settings toggles for sound and haptics separately; respect the system silent mode

**Technical notes:**
- Playback and vibration are platform APIs — needs an expect/actual abstraction in `shared` (e.g. `SoundPlayer` / `HapticFeedback`) with an Android implementation now and an iOS one later, following the module rule
- Keep sounds short and bundled as app resources; no downloads

---

## Needs Multiple Devices

### Instructor Multi-Device Monitoring
**Goal:** Dedicated view for a freediving instructor watching multiple students train simultaneously.

**UX concept:**
- Grid or stacked layout of compact pressure cards, one per connected device
- Each card shows: student name (from paired user), device color, live pressure number, mini chart
- Alerts when a student exceeds a threshold (e.g., sharp spike = likely Valsalva instead of gentler Frenzel)
- Session concept: instructor starts/stops a monitoring session, all data recorded

**Alert system:**
- Configurable threshold (e.g., "alert if pressure > 40 hPa")
- Visual alert on the card (red flash, border color change)
- Optional vibration/sound on instructor's phone
- Helps catch dangerous technique (Valsalva) before it becomes a habit

**Session recording:**
- When monitoring session is active, all connected device data is recorded with timestamps
- After session: summary per student (min EQ found - how?, consistency - how?, technique alerts - how?)
- Export session as a report (CSV or shareable format)

---

### Multiplayer Constant Equalization Game
**Goal:** Competitive version of the constant equalization exercise — who can stay in range longest?

**UX concept:**
- All players share the same target range (based on configurable difficulty)
- Each player must first activate by crossing the threshold - yes, but the first crossing should start the grace period (maybe slightly longer than single player) already, to avoid opponents to prolong their start making the first one hold their breath for too long. If somebody does not cross their activation threshold before grace period ends, they are eliminated?
- Real-time leaderboard showing each player's in-range percentage and current streak
- Players are identified by device color and paired user name
- Game ends after a set time or when all but one player drop below a threshold percentage

**Scoring:**
- Primary: time in range percentage
- Tiebreaker: best streak duration
- Live "survival" mode: if you drop below 80% in-range, you're eliminated - I think 50% would be too easy, nobody would get eliminated...

---

### Interactive Multiplayer Games

**Design principle:** Never incentivize maximum pressure — it's dangerous to the middle ear. All games must reward precision, timing, and control, not raw force. Pressure maps to position/direction, never to power/advantage.

#### Pressure Pong
**Goal:** Two-player pong where each player controls their paddle position with equalization pressure. Could also work as single-player Breakout.

**UX concept:**
- Shared screen with a ball bouncing between two paddles
- Player 1's paddle position = their pressure reading mapped to screen height
- Player 2's paddle position = their pressure reading mapped to screen height
- Standard pong scoring (first to N points)
- Ball speed increases over time

**Technical notes:**
- Requires the players to pick exactly 2 devices in the device selection — the Exercises tab catalog already supports this (`minPlayers = 2, maxPlayers = 2` on the entry)
- Ball physics: angle of reflection based on where ball hits paddle

---

#### Asymmetric Duel
**Goal:** Two-player game with asymmetric roles — one attacks, one defends. Roles swap each round.

**UX concept:**
- Attacker controls a projectile's launch angle with their pressure — release (lift device or release pressure?) to fire
- Defender controls a shield position with their pressure
- Attacker tries to be unpredictable (vary pressure before releasing), defender tries to read and react
- Round-based: each player gets N rounds as attacker, N as defender
- Scoring: attacker scores for hits, defender scores for blocks

**What makes it interesting:**
- Completely different skill per role — attacking is about deception, defending is about reaction
- Players develop strategies: fake-outs (move to one angle, quickly shift and fire), pattern reading
- No pressure magnitude advantage — both players use the same position-mapped range

---

#### 2D Navigator
**Goal:** Two players each control one axis of a shared object navigating through levels. The foundational cooperative two-eFugu game.

**UX concept:**
- Player A's pressure maps to X axis (left/right), Player B's pressure maps to Y axis (up/down)
- Navigate a shared object (fugu?) through mazes, collect items, avoid obstacles
- Levels designed to force diagonal movement, curves, and precise timing
- Both players see the same screen

**Level design ideas:**
- Tutorial: straight corridors (only one player needs to act at a time)
- Intermediate: L-shaped turns (one player holds steady, other moves)
- Advanced: diagonal paths (both must change pressure simultaneously)
- Expert: moving obstacles, narrow passages requiring coordinated precision

**What makes it interesting:**
- Players cannot talk during play (mouth closed, hands busy) — coordination must be purely intuitive
- To move diagonally, both players must change pressure at the same rate simultaneously
- Over time, good pairs develop wordless synchronization — this is the core emotional payoff
- Could have dozens of levels with increasing complexity

---

#### Lunar Lander
**Goal:** Cooperative multiplayer lunar lander — players share control of thrust and tilt axes.

**UX concept (2 players):**
- Player A (thrust): pressure maps to upward thrust countering gravity. Zero pressure = freefall, more pressure = more lift
- Player B (tilt): pressure maps to horizontal tilt. Neutral = straight, positive = tilt right, negative = tilt left (requires expert pressure mode)
- Land on platforms of decreasing size across levels
- Gravity pulls constantly — Player A must manage fuel/endurance while Player B corrects drift

**Expansion to 3 players — full 3D landing:**
- Player A (thrust): controls vertical thrust (up/down)
- Player B (tilt X): controls tilt left/right
- Player C (tilt Z): controls tilt forward/back
- Screen shows multiple simultaneous views: top-down view, front side view, right side view
- Landing pad is now a 2D target area, not just a 1D line — requires precision in both horizontal axes
- Three players must coordinate a stable descent in 3D space

**What makes it interesting:**
- Asymmetric but cooperative — each player has a distinct and essential role
- The tension of a gentle shared descent is unique — if any player panics, everyone crashes
- Creates natural "hold steady!" moments where all players must maintain precise pressure simultaneously
- Fuel mechanic could add strategic depth (thrust player can't just max thrust the whole time)
- The 3-player version with multiple views is a genuinely novel control experience — landing a spacecraft with three people and no verbal communication

---

#### Submarine
**Goal:** Cooperative two-player submarine navigation — one controls depth, the other controls speed.

**UX concept:**
- Player A (depth): pressure maps to vertical position — more pressure = deeper dive, less = surface
- Player B (speed): pressure maps to forward thrust — more pressure = faster
- Navigate through underwater cave systems with obstacles, currents, and narrow passages
- Side-scrolling view, constant gentle forward drift even at zero speed pressure

**Level elements:**
- Narrow vertical passages (Player A must be precise while Player B maintains steady speed)
- Speed gates (timed sections where Player B must burst forward while Player A holds depth steady)
- Hover zones (both players must hold minimal pressure while a danger passes overhead — the "both hold still" moments are the most tense)
- Currents that push the sub up/down (Player A must compensate) or slow/speed it (Player B must compensate)

**What makes it interesting:**
- The speed player controls pacing for both — going too fast doesn't give the depth player time to react
- Natural communication emerges: the speed player learns to slow down before tight passages
- "Both hold still" moments create shared tension without any pressure magnitude competition

---

#### Fugu Snake
**Goal:** Two classic snakes on one screen, each controlled by one player. Cooperative or competitive depending on the mode.

**UX concept:**
- Two snakes moving at constant speed on a shared grid
- Each player's pressure controls their snake's turning — positive pressure = turn right, negative = turn left, neutral = go straight (requires expert pressure mode)
- Alternatively for non-expert mode: pressure above threshold = turn clockwise, below = turn counter-clockwise, in the middle = straight
- Food spawns on the grid — eating food grows your snake
- Hit a wall, yourself, or the other snake = elimination

**Game modes:**
- **Competitive:** each player tries to survive longest. Other player's snake is an obstacle. Classic snake duel.
- **Cooperative:** both snakes must survive. Food only counts when both players have eaten one. Levels require coordination — e.g., one snake must clear a path for the other.

**What makes it interesting:**
- The control scheme is inherently challenging — you can only turn, and turning requires precise pressure timing at the right moment
- Two snakes on one grid creates dynamic obstacles that both players must react to
- Simple to understand, deep to master, highly replayable

---

#### Fugu Blaster (Tyrian-style Space Shooter)
**Goal:** A vertically scrolling space shooter controlled by eFugu. Works as single-player and scales up to a multi-eFugu crew.

**Single-player mode:**
- Ship auto-fires continuously, scrolls upward automatically
- Pressure controls horizontal position (left/right dodge)
- Destroy enemies to earn money, avoid enemies too tough to kill, dodge their projectiles
- Spend money on upgrades between waves (better guns, shields, speed)
- Progressive difficulty — more enemies, faster projectiles, tighter gaps
- This is a complete game on its own — simple controls, deep gameplay loop

**Multi-player mode (2-4+ eFugus):**
- One shared ship on screen, each eFugu controls a different role:
  - **Pilot X:** pressure controls horizontal position (left/right dodge)
  - **Pilot Y:** pressure controls vertical position (forward/back on screen)
  - **Main gun:** pressure controls aim angle of a turret (auto-fires, but player aims)
  - **Shield/special:** pressure charges a special weapon — hold to charge, release to deploy
- The more players, the more capable the ship — single pilot has auto-fire and no shield, adding players unlocks those systems
- Enemy waves scale with player count

**What makes it interesting:**
- Single-player mode is immediately accessible — same one-axis control as existing games, but a new genre
- Multi-player creates a "bridge crew" feeling — like Star Trek where everyone has a station
- Each role feels completely different — the pilot dodges, the gunner aims, the shield player manages timing
- Enemy patterns can require role coordination (e.g., shield must activate at the exact moment pilot dodges through a gap)
- Natural progression path: master single-player, then recruit friends to unlock the full ship

---

### Device Color in Multi-Device
Multiplayer games and the Live chart already use assigned device colors. Remaining:
- Use assigned colors in future instructor views
- Physical identification: 3D-printed colored nose pieces or colored tape to match app colors to physical devices

---

## Remote / Online Features

### Remote Multiplayer & Instructor Monitoring
**Goal:** Connect to other OpenFugu users over the internet for remote coaching or multiplayer.

**Approach:** Firebase Realtime Database
- Simplest real-time sync solution, free tier is generous, ~100ms latency
- Each user streams pressure readings to a shared "room" (database path)
- Other users subscribe to the room and receive updates in real-time
- No server to maintain — Firebase handles everything

**Data model:**
```
/rooms/{roomId}/
  /players/{deviceId}/
    name: "Alice"
    color: "#43A047"
    pressure: 12.5       (updated at ~10 Hz, throttled from 20 Hz)
    timestamp: 1711234567890
  /config/
    gameType: "constant_eq"
    difficulty: "medium"
    duration: 60
```

**Use cases:**
- Instructor at home monitors student practicing remotely
- Friends compete in Fugu Reef from different locations
- Group class where students use their own phones but share a room

---

## In Progress: iOS Version (Kotlin Multiplatform)

Decided 2026-07-07, work started 2026-07-12. There is real demand: several iPhone-owning friends want the app. **Status: M0–M3 merged to main and released as v1.4.0 (2026-07-15). M3 code is done and Android-retested; the final M3 checkbox — sideload the .ipa and see games run on the iPhone — waits for the phone to arrive.**

**Approach:**
- One repo, migrated in place — **no fork**. Restructure into `shared/` (core logic, games, Compose Multiplatform UI) plus thin `app/` (Android) and `iosApp/` shells. Rename the repo (e.g. `openfugu`) when it happens; GitHub redirects old URLs.
- The algorithmic core (PeakDetector, SustainedPressureDetector, RangeTracker, UserProfile, Session/SessionJson — ~700 lines) already has zero Android imports and ports as-is. SPEC.md is the platform-neutral reference.
- The BLE layer is the real rewrite: Android `BluetoothGatt` → [Kable](https://github.com/JuulLabs/kable) (Kotlin Multiplatform BLE). iOS CoreBluetooth exposes per-device UUIDs instead of MAC addresses, so device identity / device-user pairing keys need rethinking.
- UI: Compose Multiplatform runs the existing Compose UI on iOS (stable since 2025); the Canvas-based games and PressureChart translate directly.

**Economics (free & open source, no Mac owned; an iPhone 15 is available since 2026-07):**
- GitHub Actions macOS runners are free for public repos — CI can build the iOS target, run shared-core tests, and upload to TestFlight (fastlane + App Store Connect API key). No Mac required for the pipeline.
- BLE testing on the own iPhone 15 with the eFugu in hand. Installs without a Mac and without iTunes/Apple software on Windows (done 2026-07-16/17): CI publishes the unsigned `.ipa` plus a SideStore source feed to the rolling `ios-dev-latest` GitHub prerelease, and SideStore on the phone re-signs with a free Apple ID (throwaway account; 7-day signatures auto-refresh on device) — every merge to main arrives as an update in SideStore, no cable. One-time bootstrap: `iloader` running on the Linux dev VM (iPhone USB-passthrough via Proxmox, `usbmuxd` as the driver, GUI over Xvfb+noVNC) installed SideStore; the same recipe re-pairs after an iOS update or reset. TestFlight over the air once the developer account exists. Live device logs via `idevicesyslog`, crash reports via `idevicecrashreport`; the in-app log-export screen is the primary diagnostic regardless of host. Note: Kotlin Apple targets compile only on macOS — iOS-specific compile errors surface in CI, not on the dev VM.
- Still build in diagnostics (on-screen log, log export via share sheet) from day one so remote testers can report usefully.
- Distribution needs someone's Apple Developer account ($99/year) — mine or a contributor's. TestFlight external link for friends; builds expire after 90 days.

**Milestones (estimated 2026-07-11, ~11,100 lines total at the time):**
- **M0 — DONE (2026-07-12, merged to main).** JVM/Android-isms swapped (kotlinx.serialization with unchanged on-disk schema, kotlin.uuid, kotlinx-datetime, snackbar messages, Compose clipboard, AppLog facade, portable `fmt`/`nowMillis`), storage abstracted behind `KeyValueStore`/`FileStore`, MainActivity split into a ~120-line shell + EFuguApp/LiveTab/DevicesTab. Also new: settings screen (theme, simulated-devices gate, About).
- **M1 — DONE (2026-07-12, branch `kmp-m1`).** `shared/` module (commonMain/androidMain, `com.android.kotlin.multiplatform.library` + Compose Multiplatform 1.9.3); games, exercises, detectors, sessions, chart, most screens moved; canvas text drawing rewritten on TextMeasurer (`ui/CanvasText.kt`) since `nativeCanvas` is Android-only; all 79 unit tests moved and green.
- **M2 — DONE (2026-07-13, verified on real eFugu hardware).** `KableDeviceConnection` + Kable scanning in commonMain (Kable 0.42.0 — newest Kotlin-2.2-compatible), switchable at runtime via the "Bluetooth engine" developer setting with the legacy `DeviceConnection` as default/fallback. On Android both engines key devices by MAC address, so saved devices/pairings are engine-independent (iOS will differ — see M4).
- **M3 — DONE (verified on the iPhone 15, iOS 26, 2026-07-17: app boots, games run on simulated devices).** Real-device bring-up found two launch crashes CI and the simulator can't catch: Compose Multiplatform's `PlistSanityCheck` aborts without `CADisableMinimumFrameDurationOnPhone` in Info.plist, and `NSLog` varargs aren't bridged by Kotlin/Native (C variadic, `%@` dereferenced raw Kotlin string bytes) — the iOS logger now renders lines in Kotlin and escapes `%`. Also added: app icon (rendered from `assets/icon-512.svg` — brand SVG sources moved into the repo), per-build version stamping (`1.4.0.<run>`), and the `ios-dev-latest` SideStore-source publishing in `ios.yml`. On-device observations for the polish pile: some colors render slightly off vs Android, layouts are cramped on the smaller screen, and UUID-based device identities are much longer than MACs and overflow their rows (ties into M4's identity work). Turned out to be more refactor than anticipated: EFuguViewModel's portable logic became `EFuguStore` (commonMain) behind a new `BlePlatform` seam (Android keeps the legacy engine + permission checks in `AndroidBlePlatform`; iOS is Kable-only and hides the Bluetooth-engine setting), and EFuguApp/LiveTab/SessionViewer/UserDetail/CalibrationWizard/MockDeviceOverlay moved to commonMain (`BackHandler` from CMP's `ui-backhandler`, share/save-logs as injected callbacks, `OpenFuguRoot` as the shared root). Apple targets + static `OpenFuguShared` framework in `shared`; iosMain actuals (NSUserDefaults/Documents storage, NSLog logger, share sheet, static theme) + `MainViewController`; `iosApp/` SwiftUI shell with XcodeGen (`project.yml`; the .xcodeproj is generated); CI: `ios.yml` builds the unsigned `.ipa` on a macOS runner, `android.yml` compiles + runs the unit tests. Portrait lock and the Bluetooth permission string went into Info.plist already. Keep-screen-on turned out to not exist on Android either — deferred to the sound-and-haptics/polish pile. CI iterations to green (2026-07-14): `Math.random()`/`java.util.Random` in games (JVM-only, invisible on Android builds) → kotlin.random; `Dispatchers.IO` isn't common API → `IoDispatcher` expect/actual; `ExperimentalForeignApi` opt-ins in iosMain; and release linking (LTO) OOMs a 2 GB heap — `org.gradle.jvmargs` is the knob, since Kotlin 2.x runs the Native compiler inside the Gradle daemon (`kotlin.native.jvmArgs` did nothing). Android retest fixes (2026-07-14): Bluetooth power-state listener in `BlePlatform` (radio off drops real connections that GATT callbacks miss; back on auto-rescans), system enable-Bluetooth prompt, Kable promoted to default engine on both platforms, swipe-to-delete made deliberate (red background, 60% threshold, one direction), log saving replaced by the share sheet (app-private storage was unreachable). Calibration gate made actionable (2026-07-15): the gate distinguishes "no user assigned" from "user not calibrated", the gated card opens a dialog that assigns/creates a user or jumps to the calibration wizard, and the multi-device picker labels the specific reason.
- **M4 — iOS BLE bring-up: core VERIFIED (2026-07-17).** Kable's CoreBluetooth backend works against real eFugu hardware on the iPhone with no code changes — permission prompt, connect, live pressure, and **two devices concurrently in a multiplayer game**. Fixed 2026-07-17 after the first on-device day: `.fugu` import registered (exported UTI + `onOpenURL` → the shared import seam), brand color schemes replacing the Material template purple/pink that iOS fell back to (orange fugu on ocean navy, `ui/theme/`), and Fugu Flow's start screen (was unstartable on iPhone: fixed-height column pushed the Start button off-screen — now a scrollable tap-a-pattern-to-start list). UUID-keyed identity confirmed stable in practice (same eFugu reattaches to its saved device/pairing across relaunches, reboots, and days of builds; app deletion wipes local data by design — nothing to persist). Remaining before calling M4 done: device-card density with large system fonts (UUID and battery/firmware/hardware rows overflow — applies to any device with big fonts, not just iOS). Iteration loop is push-to-main → SideStore update (~20 min).
- **M5 — TestFlight.** Paperwork.

**What keeps the option cheap meanwhile:** SPEC.md stays platform-neutral, the core stays free of Android imports, protocol knowledge lives in PROTOCOL.md.

---

## Rejected: PWA / Browser Version

Considered and rejected 2026-07-07. A browser app would need Web Bluetooth to talk to the device, and Web Bluetooth only exists in Chromium browsers (Chrome/Edge on desktop and Android). It is unavailable on iOS entirely — every iOS browser is WebKit underneath and Apple does not ship it — so a PWA fails the "runs on any device" goal and specifically excludes the iPhone users who motivate cross-platform work in the first place.

If desktop support is ever wanted, it falls out of the Kotlin Multiplatform restructuring above (Compose Multiplatform also targets desktop) rather than a web app; desktop BLE would need its own library choice, but the shared core and SPEC.md keep that door open.

---

## Investigation
- [ ] Figure out what the `dcdf` BLE characteristic does (exercise start/stop? device config? LED control?) — needs another HCI snoop while using the official app's exercise modes

## Low Priority
- [ ] Enable R8 minification for release builds — Play warns about the missing deobfuscation file (minify is off); R8 would cut app size substantially. Needs keep-rules for reflection users (kotlinx.serialization, possibly Kable), a `mapping.txt` upload to Play per release, and a full device retest. Pair with the next Kotlin/AGP upgrade session.
- [ ] Simulated dive mode — dry-run dive training: simulates the length of a breath hold and the frequency of equalizations. The user declares the depth at their first equalization; the app then predicts the following equalization points (same relative pressure-change intervals) down to the target depth and prompts the user to equalize at each one. The official app already has this; our focus is on games and instructor features. (Not to be confused with the shipped simulated device — this uses a real device.)
- [ ] Landscape orientation support
