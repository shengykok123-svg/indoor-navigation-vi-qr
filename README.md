# Indoor Navigation for the Visually Impaired using QR Codes

An Android prototype that guides visually impaired (VI) users through indoor environments by combining **QR-code anchors**, **Pedestrian Dead Reckoning (PDR)**, **Dijkstra shortest-path routing**, and **text-to-speech guidance** — with no beacons, no Wi-Fi fingerprinting, and no dedicated infrastructure beyond printed paper.

> **UCCC2513 Mini Project** · Faculty of Information and Communication Technology, Universiti Tunku Abdul Rahman (Kampar Campus) · Bachelor of Computer Science (Hons) · June 2025

---

## Table of Contents

- [Why this project](#why-this-project)
- [Objectives](#objectives)
- [Key achievements](#key-achievements)
- [System architecture](#system-architecture)
- [How navigation works](#how-navigation-works)
- [Technical deep dive](#technical-deep-dive)
- [Test environment and dataset](#test-environment-and-dataset)
- [Repository structure](#repository-structure)
- [Getting started](#getting-started)
- [QR code payload format](#qr-code-payload-format)
- [Regenerating the map graph](#regenerating-the-map-graph)
- [Accessibility design decisions](#accessibility-design-decisions)
- [Tech stack](#tech-stack)
- [Scope and limitations](#scope-and-limitations)
- [Future work](#future-work)
- [Team](#team)

---

## Why this project

Visual impairment refers to a partial or total loss of vision that cannot be corrected by spectacles, contact lenses, or surgery. GPS has transformed *outdoor* navigation, but it is effectively useless indoors — satellite signals are blocked and degraded by building structures. That leaves VI individuals dependent on human assistance, tactile signage, or memorised routes when moving through schools, offices, hospitals, shopping malls, and other public buildings.

Existing indoor positioning alternatives each carry a real cost:

| Approach | Limitation |
|---|---|
| BLE beacons | Requires dedicated hardware, battery replacement, ongoing maintenance |
| Wi-Fi fingerprinting | Needs extensive site surveying; sensitive to AP changes and interference |
| RFID tags | Reader hardware cost; very short read range |
| LiDAR / V-SLAM | High compute and hardware cost; impractical on commodity phones |
| Ultrasonic / Infrared | Short range, line-of-sight dependent, susceptible to interference |

**QR codes are the cheapest viable anchor.** They cost the price of printer ink, can be re-printed by anyone, need no power, and are read by the camera every modern smartphone already has. Their weakness is that they are *discrete* — they tell you where you are only at the instant you scan one, and they say nothing about the metres in between.

This project's answer is a **hybrid**: QR codes supply absolute position fixes, and PDR fills the gaps between them. Each complements the other's failure mode — QR scans reset the drift that PDR accumulates, and PDR keeps guidance flowing when the next QR code cannot be found or reached.

## Objectives

1. **Localisation** — determine the user's position and orientation by scanning a QR code, with no network dependency.
2. **Routing** — compute the shortest path across a graph-based indoor map using Dijkstra's algorithm.
3. **Continuous tracking** — estimate movement between QR anchors using accelerometer-based step detection, so guidance does not stall between scans.
4. **Accessible delivery** — convert every navigation decision into clear, concise text-to-speech instructions designed to be followed without looking at the screen.

## Key achievements

### 1. A working hybrid QR + PDR navigation engine

The core contribution is [`NavigationModule.kt`](app/src/main/java/com/example/finalprototype/NavigationModule.kt) — **1,436 lines** implementing a complete state machine that fuses discrete QR fixes with continuous dead reckoning. QR codes act as absolute positional references that periodically zero out PDR drift, while PDR advances the route segment-by-segment when no QR code is available. Neither method alone can do this: pure QR navigation dies the moment a code is unreachable, and pure PDR drifts without bound.

### 2. Solved the "blind users search for QR codes" problem

This is the most interesting finding of the project, and it is not something you discover from a literature review — it only shows up once you actually simulate the use case.

A sighted user walks *to* a QR code. A blind user **sweeps the phone around hunting for it**, taking many short, irregular steps in place. A naive step counter reads those search steps as forward progress, and the position estimate corrupts immediately.

The implemented solution:

- **QR search mode** is entered as soon as the user is prompted to scan, and tracked separately from navigation (`navigationStepCount` vs. `searchStepCount`).
- **Movement-pattern analysis** maintains a rolling variance window over accelerometer magnitude. High variance (`IRREGULAR_MOVEMENT_THRESHOLD = 3.0`) is the signature of searching rather than walking.
- **Step-correction factor** assumes only ~30% of steps taken during a search window represent real displacement.
- Only *filtered navigation steps* are allowed to feed distance estimation and calibration.

### 3. Self-calibrating step length with confidence weighting

Stride length varies by person, pace, and even corridor. Rather than hard-coding it, the system learns it during use:

- Starts from a `DEFAULT_STEP_LENGTH` of 0.65 m, bounded to a plausible 0.4–1.2 m.
- On reaching each QR anchor, the *known* graph distance of the segment just walked is divided by the *filtered* step count to obtain a measured stride.
- Each measurement is scored by a **confidence function** — strides in the 0.55–0.75 m range score 1.0, implausibly short ones (likely search contamination) score 0.3.
- Confidence selects the update weight (0.30 / 0.15 / 0.05), so unreliable measurements nudge the estimate instead of wrecking it.
- A **median filter** over the last 10 measurements rejects outliers before blending.
- Calibration state is explicitly preserved across reroutes, so a wrong turn does not discard a well-tuned stride length.

### 4. Adaptive step detection, not a fixed threshold

Peak-detection step counters are notoriously sensitive to threshold choice. [`detectStepEnhanced`](app/src/main/java/com/example/finalprototype/NavigationModule.kt) instead uses a **dynamic threshold** that decays (×0.95) when no steps are detected and boosts (×1.1) after a detection, clamped to 1.2–2.5 — so it hunts for the right sensitivity for the current user instead of demanding it be configured. A 15-sample sliding window smooths accelerometer magnitude, and inter-step timing gates (900–2500 ms) reject both sensor jitter and double-counts.

### 5. Vector-math turn instructions that work without a compass

The prototype derives turn directions from geometry rather than relying on the magnetometer:

- **Initial orientation** is bootstrapped from a physical insight — a user scanning a wall-mounted QR code must be facing *opposite* to the direction the code faces. The node's `facing` field is reversed to establish heading, then compared against the bearing to the next node (`getInitialDirection`). This is the only point where `"turn around"` can be issued.
- **Mid-route turns** use the signed angle between the incoming and outgoing path vectors — `atan2(cross, dot)` over three consecutive nodes — with a ±30° deadband, so slight corridor jogs are not announced as turns (`getTurnDirection`).

### 6. Graceful degradation everywhere

Prototypes usually fail hard. This one was deliberately built not to:

| Failure | Handling |
|---|---|
| Map JSON missing or corrupt | `MapDataLoader` falls back to embedded sample nodes and edges |
| Accelerometer unavailable or failing | `handleSensorFailure` switches to time-based distance estimation |
| QR code unreachable | PDR completes the segment and guidance continues |
| Wrong QR code scanned | `handleUnexpectedQRScan` recalculates the route from the new position |
| Malformed QR payload | Field validation with spoken error feedback instead of a crash |
| QR scanned twice | 5-second debounce on repeated reads of the same node |
| User stops responding | Instructions auto-repeat every 30 s; 60 s QR-search timeout |

### 7. Deliverable accessible UI

A three-screen Fragment app (Home / Destination / Camera) in which every state change — destination selection, screen change, step progress, turn, arrival, calibration improvement — is announced through TTS and mirrored as high-contrast on-screen text. Destination selection is a **linear up/down/confirm stepper with double-tap confirmation**, not a dropdown, because dropdowns are unusable without sight. Seven `dimens.xml` variants (`values-small` through `values-xlarge`, plus `sw320dp`/`sw600dp`/`sw720dp`) scale text and touch targets across phones and tablets.

### 8. Reproducible map-data pipeline

The indoor graph is not hand-written. An annotated SVG floor plan of UTAR FICT Block N supplies node pixel coordinates; [`MapDataScript.py`](map-data/MapDataScript.py) then computes Euclidean edge weights between every connected pair and emits `edges_with_distance.json`, deduplicating bidirectional edges. Re-mapping a new floor means editing one JSON file and re-running one script.

---

## System architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  INPUT                                                          │
│  Smartphone camera  ·  Touchscreen  ·  Accelerometer            │
└────────────────────────┬────────────────────────────────────────┘
                         │
         ┌───────────────┴───────────────┐
         ▼                               ▼
┌──────────────────────┐      ┌──────────────────────────┐
│ QR DETECTION         │      │ DESTINATION SELECTION    │
│ CameraX + ML Kit     │      │ DestinationFragment      │
│ barcode scanning     │      │ (accessible stepper)     │
└──────────┬───────────┘      └────────────┬─────────────┘
           │ node ID + location             │ destination
           ▼                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  ROUTE GUIDANCE  —  SharedViewModel                             │
│  · Dijkstra shortest path over weighted graph                   │
│  · Turn generation via vector cross/dot product                 │
│  · Route validation and live re-routing                         │
└────────────────────────┬────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  HYBRID POSITION TRACKING  —  NavigationModule                  │
│  · Adaptive peak-detection step counter                         │
│  · QR-search-mode filtering                                     │
│  · Confidence-weighted stride calibration                       │
│  · Segment progression + drift correction on each QR fix        │
└────────────────────────┬────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  OUTPUT                                                         │
│  Android TTS → speaker   ·   RouteMapView (SVG + route overlay) │
│  TTSMessageManager (on-screen mirror of every spoken message)   │
└─────────────────────────────────────────────────────────────────┘
```

### Module responsibilities

| File | Lines | Responsibility |
|---|---:|---|
| [`NavigationModule.kt`](app/src/main/java/com/example/finalprototype/NavigationModule.kt) | 1,436 | PDR engine, step detection, stride calibration, QR-search filtering, segment progression, TTS guidance |
| [`SharedViewModel.kt`](app/src/main/java/com/example/finalprototype/SharedViewModel.kt) | 659 | Graph state, Dijkstra pathfinding, turn-instruction generation, route validation, LiveData for the UI |
| [`CameraFragment.kt`](app/src/main/java/com/example/finalprototype/CameraFragment.kt) | 620 | CameraX lifecycle, ML Kit barcode analysis, QR payload parsing and validation, navigation start/stop |
| [`HomeFragment.kt`](app/src/main/java/com/example/finalprototype/HomeFragment.kt) | 500 | Main screen, bottom navigation, floor-plan route rendering, live status banners, TTS announcements |
| [`MapDataLoader.kt`](app/src/main/java/com/example/finalprototype/MapDataLoader.kt) | 245 | Asset JSON parsing, node-ID ↔ display-name mapping, fallback sample data |
| [`DestinationFragment.kt`](app/src/main/java/com/example/finalprototype/DestinationFragment.kt) | 196 | Accessible destination stepper with double-tap confirmation and state preservation |
| [`MainActivity.kt`](app/src/main/java/com/example/finalprototype/MainActivity.kt) | 165 | App entry, map loading, shared `NavigationModule` ownership, callback wiring |
| [`RouteMapView.kt`](app/src/main/java/com/example/finalprototype/RouteMapView.kt) | 151 | Custom `View`: SVG floor-plan rendering with route overlay, pinch-zoom and pan |
| [`MapFragment.kt`](app/src/main/java/com/example/finalprototype/MapFragment.kt) | 97 | Full-screen map view |
| [`TTSMessageManager.kt`](app/src/main/java/com/example/finalprototype/TTSMessageManager.kt) | 54 | Mirrors spoken messages as timed on-screen text (6 s) |

**Total: 4,123 lines of Kotlin.**

## How navigation works

```
          ┌──────────────────────────┐
          │  Is current location     │
          │  known?                  │
          └────┬────────────────┬────┘
            No │                │ Yes
               ▼                ▼
      ┌─────────────────┐  ┌──────────────────────┐
      │ Prompt: scan a  │  │ Prompt: choose a     │
      │ QR code         │  │ destination          │
      └────────┬────────┘  └──────────┬───────────┘
               │                      │
               ▼                      ▼
      ┌─────────────────┐  ┌──────────────────────────────┐
      │ ML Kit decodes  │  │ Dijkstra → shortest path     │
      │ {location, ID}  │  │ Generate turn instructions   │
      └────────┬────────┘  │ Estimate steps to next node  │
               │           └──────────────┬───────────────┘
               │                          ▼
               │           ┌──────────────────────────────┐
               │           │ Speak: initial orientation   │
               │           │ ("turn around", "go straight")│
               │           └──────────────┬───────────────┘
               │                          ▼
               │           ┌──────────────────────────────┐
               │           │ PDR counts filtered steps    │
               │           │ toward segment target        │
               │           └──────────────┬───────────────┘
               │                          ▼
               │           ┌──────────────────────────────┐
               └──────────▶│ Segment target reached:      │
                           │ prompt QR scan (anchor) OR   │
                           │ auto-advance on PDR estimate │
                           └──────────────┬───────────────┘
                                          ▼
                           ┌──────────────────────────────┐
                           │ QR scanned → correct drift,  │
                           │ recalibrate stride, reroute  │
                           │ if position unexpected       │
                           └──────────────┬───────────────┘
                                          ▼
                           ┌──────────────────────────────┐
                           │ Destination reached →        │
                           │ announce success, stop       │
                           └──────────────────────────────┘
```

The loop is the important part: **periodic QR scans act as position anchors that correct cumulative PDR error**, and PDR keeps the user moving when a scan is not possible.

## Technical deep dive

### Dijkstra over a weighted indoor graph

`findShortestPath` in `SharedViewModel.kt` runs Dijkstra across the node/edge graph loaded from assets, treating edges as undirected (`edge.from == u || edge.to == u`), tracking `dist` and `prev` maps, and reconstructing the path by walking predecessors back from the destination. An iteration cap of `2 × |V|` guards against pathological input. Results are wrapped in a sealed `RouteResult` (`Success` / `SameLocation` / `Error`) so the UI can speak a meaningful message for each outcome rather than silently failing.

### Step detection pipeline

```
accelerometer (x,y,z)
   → magnitude = √(x²+y²+z²)
   → 15-sample sliding window average
   → peak detection against DYNAMIC threshold (1.2–2.5, auto-adjusting)
   → timing gate (900 ms ≤ Δt ≤ 2500 ms)
   → QR-search classification (variance-based)
        ├── searching  → counted as search step, ×0.3 correction
        └── navigating → counted as navigation step
   → distance += adaptiveStepLength
   → compare against segment target → advance or prompt
```

### Instruction generation

`generateNavigationInstructions(path)` walks the full node list and produces a complete turn-by-turn script before movement begins, so the user can hear the whole route up front and the system can still announce the next turn even if a QR scan is skipped. Instructions are deliberately short (`generateConciseInstruction`, `extractConciseFromFullInstruction`) — long sentences are hostile to audio-only navigation.

## Test environment and dataset

The prototype is mapped to the **ground floor of UTAR FICT Block N (Kampar campus)**.

| Metric | Value |
|---|---|
| Graph nodes | 29 |
| Graph edges | 46 |
| QR anchor nodes | 12 |
| Turning-point nodes | 17 |
| Selectable destinations | 13 |
| Minimum Android version | 7.0 (API 24) |
| Target SDK | 36 |

Selectable destinations: `Main Entrance`, `NG-026`, `NG-033`, `NG-005`, `N007A`, `N007B`, `NGT4&5_Toilet`, `N012A`, `N012B`, `N001A`, `N001B`, `N009A`, `N009B`.

Each node carries an ID, display name, type (`QR Node` / `Turning Point`), `x`/`y` coordinates, a `facing` direction (`Up`/`Down`/`Left`/`Right`/`None`), and its adjacency list.

## Repository structure

```
.
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   ├── indoor_map_graph.json      # 29 nodes: coords, facing, adjacency
│       │   │   ├── edges_with_distance.json   # 46 weighted edges
│       │   │   └── fict_gfloorplan.svg        # floor plan rendered by RouteMapView
│       │   ├── java/com/example/finalprototype/
│       │   │   ├── MainActivity.kt
│       │   │   ├── NavigationModule.kt        # PDR engine
│       │   │   ├── SharedViewModel.kt         # graph + Dijkstra + instructions
│       │   │   ├── CameraFragment.kt          # CameraX + ML Kit
│       │   │   ├── HomeFragment.kt
│       │   │   ├── DestinationFragment.kt
│       │   │   ├── MapFragment.kt
│       │   │   ├── MapDataLoader.kt
│       │   │   ├── RouteMapView.kt
│       │   │   ├── TTSMessageManager.kt
│       │   │   └── ui/theme/
│       │   └── res/
│       │       ├── layout/                    # 5 Fragment/Activity layouts
│       │       ├── values/ … values-xlarge/   # 7 dimens variants
│       │       └── drawable/, mipmap-*/
│       ├── test/                              # JVM unit tests
│       └── androidTest/                       # instrumented tests
├── map-data/
│   ├── MapDataScript.py                       # edge-weight generator
│   ├── indoor_map_graph.json                  # master node definitions
│   ├── indoor_map_graph.csv                   # spreadsheet-friendly node table
│   ├── edges_with_distance.json               # generated output
│   ├── FICT_Ground_Floor_Plan.png             # source floor plan
│   ├── Ground_Coordinates.svg                 # coordinate-annotated plan
│   └── fict_gfloorplan1.svg                   # SVG used for in-app rendering
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Getting started

### Prerequisites

- Android Studio Narwhal (2025.1.1) or newer
- JDK 11
- Android SDK 36 (`compileSdk`), minimum device API 24
- A **physical Android device** — the camera and accelerometer are both required, so the emulator cannot exercise navigation

### Build and run

```bash
git clone https://github.com/shengykok123-svg/indoor-navigation-vi-qr.git
```

Then:

1. Open the cloned folder in Android Studio and let Gradle sync. The wrapper pins Gradle 8.13 and AGP 8.11.1, so no manual setup is needed.
2. `local.properties` is intentionally **not** committed (it holds a machine-specific SDK path). Android Studio regenerates it on first sync; if you build from the CLI, create it yourself:
   ```
   sdk.dir=/absolute/path/to/your/Android/Sdk
   ```
3. Connect a physical device with USB debugging enabled and run the `app` configuration — or build from the CLI:

```bash
./gradlew installDebug
```

4. Grant the **Camera** permission when prompted. The app also declares `BODY_SENSORS` and `ACTIVITY_RECOGNITION` for step detection.

### Using the app

1. On launch, tap the large central button and scan any location QR code to establish your position.
2. Open **Destination**, step up/down through the list, and double-tap to confirm. The route is computed immediately and the floor plan shows it.
3. Follow the spoken guidance. Scan QR codes when prompted to correct drift — or keep walking if you cannot find one; PDR will advance the route for you.

## QR code payload format

Each printed QR code encodes a JSON object. `CameraFragment.handleQrResult` requires both fields and rejects anything else with spoken feedback:

```json
{
  "location": "NG-026",
  "ID": "QR17"
}
```

| Field | Required | Validation |
|---|---|---|
| `location` | yes | Non-empty human-readable location name, spoken aloud on scan |
| `ID` | yes | Must start with `QR` and resolve to a node in `indoor_map_graph.json` |

Invalid JSON, missing fields, a malformed ID, or an unknown node each produce a distinct spoken error rather than a crash.

## Regenerating the map graph

Edge weights are derived, never hand-entered. Edit the node definitions, then regenerate:

```bash
cd map-data
python MapDataScript.py
```

The script reads `indoor_map_graph.json`, computes the Euclidean distance between every connected node pair, deduplicates each bidirectional edge so it appears exactly once, and writes `edges_with_distance.json`. Copy the result into `app/src/main/assets/` to take effect in the app.

To map a different floor: annotate its SVG plan with QR node positions, record each node's coordinates, type, facing direction, and adjacency list in `indoor_map_graph.json`, re-run the script, and drop in the new SVG.

## Accessibility design decisions

Every interaction choice in this prototype was made for a user who cannot see the screen.

- **Audio first, visuals second.** TTS is the primary output channel; `TTSMessageManager` mirrors each spoken message on screen for 6 seconds purely as a sighted-observer aid during testing and demonstration.
- **No dropdowns, no lists to scan.** Destination selection is a linear stepper — up, down, confirm — so position in the list is announced and never has to be seen.
- **Double-tap confirmation** on destructive or committing actions (start/stop navigation, destination confirm) prevents accidental activation from exploratory touches.
- **Large, high-contrast targets** throughout, scaled across seven screen-size resource buckets.
- **Concise instruction phrasing.** Long sentences are actively shortened before speaking — audio-only navigation needs the verb first.
- **Instruction repetition** every 30 seconds, so a user who missed or forgot the last instruction never has to ask for it.
- **Spoken calibration and progress feedback** ("Step detection accuracy improved") to build trust in a system whose internal state is otherwise invisible.
- **Fully offline.** All detection, routing, and speech run on-device. No connectivity means no dead zones and no privacy exposure.

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 |
| Build | Gradle 8.13, AGP 8.11.1, JDK 11 |
| UI | Android Views + Fragments, ViewBinding, Material Components 1.8.0; Compose BOM 2024.04.01 available |
| Camera | AndroidX CameraX 1.3.4 (core, camera2, lifecycle, view) |
| QR detection | Google ML Kit Barcode Scanning 17.2.0 |
| Speech | Android `TextToSpeech` API |
| Sensors | `SensorManager` / `TYPE_ACCELEROMETER` |
| State | AndroidX ViewModel + LiveData 2.8.4 |
| Serialisation | Gson 2.10.1 |
| Vector graphics | AndroidSVG 1.4 |
| Math | Apache Commons Math 3.6.1 |
| Logging | Timber 5.0.1 |
| Async | kotlinx-coroutines-android 1.7.3 |
| Testing | JUnit 4.13.2, Mockito 5.7.0, Espresso 3.5.1 |
| Map tooling | Python 3.12 |

## Scope and limitations

Stated plainly, because an honest prototype is more useful than an overclaimed one.

- **Single floor only.** Ground floor of UTAR FICT Block N. No multi-floor or stair/lift transitions.
- **Static map data.** The graph is a committed JSON file, not generated or updated at runtime.
- **No obstacle detection.** The system routes along a known graph; it cannot see a chair in the corridor.
- **Heading is inferred, not measured.** Orientation comes from QR `facing` values and path geometry rather than the magnetometer, so the system assumes the user actually turns when instructed.
- **PDR drift between anchors.** Step-based estimation is corrected only on each QR scan; long unanchored segments accumulate error.
- **Not yet tested with VI users.** Evaluation to date is functional and simulated. Validation with actual visually impaired participants is required before any claim about real-world usability.
- **Deep-learning QR enhancement is not in this repository.** The broader project investigated a simple CNN for low-light enhancement and a U-Net for warped/damaged QR restoration, both trained in Python on 1,000 synthetic image pairs and exported via TorchScript. That work was evaluated standalone and is **not** integrated into this Android prototype; this app relies on ML Kit's native detection.
- **No voice input.** Destination selection is touch-driven.

## Future work

1. **Integrate the CNN and U-Net enhancement models** into the ML Kit pipeline via TorchScript, and measure the on-device inference cost and the detection-rate gain under low light and physical damage.
2. **Train on real-world backgrounds** rather than synthetic pairs, to test robustness and avoid overfitting.
3. **Fuse the magnetometer and gyroscope** for measured heading, reportedly accurate to ~1°, replacing inferred orientation.
4. **Haptic proximity feedback** — vibration intensity scaled to distance from the destination.
5. **Expand and refine the node graph** for full floor coverage, then extend to multi-floor routing.
6. **Usability testing with visually impaired participants**, and iteration on instruction phrasing and timing from their feedback.
7. **Voice command input** and integration with Android accessibility services (TalkBack).

## Team

**P4 Group 1** — Bachelor of Computer Science (Hons), Faculty of Information and Communication Technology, Universiti Tunku Abdul Rahman (Kampar Campus).

| Member | Primary contributions |
|---|---|
| Cheah Ken Win | QR code detection module, image-enhancement models (CNN / U-Net), implementation challenges analysis |
| Chin Kah Wen | QR code reader module, literature review on QR methods, project timeline |
| Kok Yong Sheng | Research objectives, project scope, system design and architecture, route guidance module |
| Tan Jin Huang | Problem statement and motivation, user interface module, novelties analysis |

Supervised under **UCCC2513 Mini Project**, UTAR.

## Acknowledgements

- Google **ML Kit** for on-device barcode scanning
- **AndroidX CameraX** for a consistent camera pipeline across devices
- **AndroidSVG** for floor-plan rendering
- UTAR FICT for access to Block N floor plans

---

*Academic prototype developed for UCCC2513 Mini Project. Not a certified mobility aid — it is a research prototype and must not be relied upon for safe independent travel.*
