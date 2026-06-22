# WarteAlarm — Build Journal

A running log of how the prototype was built, the decisions behind it, and how to verify it.
Spec: [`specs/wartealarm.md`](specs/wartealarm.md) · Research: [`API-research.md`](API-research.md).

## 2026-06-22 — Prototype build (subagent-parallelised)

### Approach
Built the **shared foundation first** (the contracts that would collide if edited concurrently), then
fanned out **4 parallel subagents** on disjoint, independently-owned slices. Each agent owned a separate
set of files; shared glue (build config, manifest, domain types, event bus, settings store) was written
up front so agents only had to implement against stable interfaces — no agent edits another's files.

### Architecture (clean layering, framework-free core)
```
domain/   pure Kotlin, no Android   → Models, LinkParser, QueueAnalyzer, AlarmSettings, WatchParams
data/     feed + persistence        → WnrRepository (interface), SettingsStore (DataStore)
core/     process-wide glue         → WatchBus (service ↔ UI bridge)
socket/   feed implementation       → WnrSocketRepository  (Agent A)
service/  background watcher        → WatchService (foreground + wakelock)  (Agent B)
alarm/    alerting                  → AlarmEngine + AlarmActivity (full-screen)  (Agent B)
ui/       screens                   → entry + status/settings fragments, ViewModel  (Agent C)
test/ + tools/  verification        → unit tests + Node live-feed probe  (Agent D)
```

### Foundation written first (this layer is the reference style: KDoc + readable, testable, no Android in `domain/`)
- **Build**: added `socket.io-client:1.0.1` (server is socket.io v2/EIO3, `API-research.md` §6), plus
  coroutines, lifecycle-service, DataStore, via the version catalog. Excluded socket.io's bundled
  `org.json` (Android provides it).
- **Manifest**: INTERNET, FOREGROUND_SERVICE(+SPECIAL_USE), WAKE_LOCK, POST_NOTIFICATIONS,
  USE_FULL_SCREEN_INTENT, VIBRATE; declared `WatchService` (specialUse FGS) and the full-screen
  `AlarmActivity` (showWhenLocked/turnScreenOn). Flashlight uses `setTorchMode` → no CAMERA permission.
- **Domain**: `Ticket`/`WnrQueue`/`RoomSnapshot`, `TicketState`; `LinkParser` (long + short `/t/` links);
  `QueueAnalyzer` → `MyStatus` (Waiting/Called/Skipped/Coarse/Unknown), matching by integer number and
  counting `active` ahead, with the coarse `last_drawn` fallback baked in (§9). `tickets == null` means
  "no precise feed → coarse mode", distinct from an empty list.
- **Glue**: `WnrRepository` interface; `WatchBus` (StateFlows the service publishes and the UI observes);
  `SettingsStore` (DataStore-backed `AlarmSettings`, context-passed, no global init).

### Subagent assignments
- **A — socket layer**: `socket/WnrSocketRepository` implementing `WnrRepository`; JSON → domain parsing.
- **B — service + alarm**: `WatchService`, `AlarmEngine` (sound/headphones-only/vibrate/screen+torch
  blink/full-system), full-screen `AlarmActivity`, notifications.
- **C — UI**: entry (paste link + number), live status + settings screens, `MainViewModel`; starts/stops
  the service; runtime POST_NOTIFICATIONS permission.
- **D — verification**: unit tests for `LinkParser` + `QueueAnalyzer` (fixtures from §4/§7/§10); Node
  `tools/probe.js` for the §9 open-hours live-feed check.

### Verification plan (per the working agreement)
- `./gradlew assembleDebug` must compile; `./gradlew testDebugUnitTest` must pass the domain tests.
- The live §9 check (does the `tickets` event reach an anonymous socket?) is environment-gated — the
  clinic is closed and the sandbox network is restricted — so it ships as a runnable probe, not a claim.

### Results

**A — socket feed layer.** Added `SnapshotParser` (pure `org.json` → domain) and `WnrSocketRepository`
(socket.io-client 1.0.1, `/room` namespace). The merge is the load-bearing bit: `room`/`data` events
preserve existing `tickets` so `tickets` stays `null` until a real `tickets` event arrives (coarse vs
precise mode). `join()` is re-emitted on every `EVENT_CONNECT` so reconnects re-join for free. Defensive
throughout — wrong-typed/missing JSON returns defaults, never throws; `URISyntaxException` wrapped. State
via thread-safe `StateFlow`. _To confirm at the §9 probe:_ `desk` shape (object-or-string, §9.4), the
`time` payload type, and whether to force websocket-only transport.

**D — verification artifacts.** `LinkParserTest` (8 cases) pins the §2 routing decode: long/short/room-only
links, whitespace, and trailing query strings that must not bleed into the queue/ticket. `QueueAnalyzerTest`
(13 cases) drives the §7 Liste-Y / 46 / Zimmer-4 fixture through every `MyStatus` branch — Waiting position
(active-ahead only, other queues excluded), Called on redeemed, Skipped on absent, the coarse `last_drawn`
fallback (`tickets == null`), and the `inferMissing` Skipped-vs-Unknown split. Both are Android-free →
`./gradlew testDebugUnitTest`. `tools/probe.js` (socket.io-client@2) ships as the runnable §9 open-hours
check, not a claim. _Behaviour pinned by tests, worth a glance:_ `QueueAnalyzer` treats `finished` the same
as `redeemed` (both → `Called`), and `cancelled` like `absent` (→ `Skipped`).

**B — service + alarm engine.** `WatchService` (LifecycleService): foreground + partial wakelock (3h cap),
constructs `WnrSocketRepository()`, `combine`s snapshots + connection, analyses each non-null snapshot, and
publishes status/connection/coarseMode/params to `WatchBus`. Alarms fire on *status transitions* (diffed vs
the previous `MyStatus`, captured synchronously before the launched settings read) so each fires once:
`Called`/coarse-`reached` → loud looping `AlarmEngine.fireAlarm` + full-screen-intent notification launching
`AlarmActivity`; `Waiting.ahead ≤ preAlarmThreshold` → `preAlarm`. `AlarmEngine` is an `object` (so the STOP
button reaches it) ORing the configured modalities: alarm-stream `Ringtone` (USAGE_ALARM, looping on 28+),
headphones-only gated on `getDevices` output routes, waveform vibration (VibrationEffect 26+ / legacy below),
`CameraManager` torch blink, and the max-volume "full system alarm" (USAGE_ALARM already bypasses DND, so no
policy access requested). `AlarmActivity` shows-when-locked (setShowWhenLocked 27+ / window flags below), owns
the `visualBlink` background animation, and STOP calls `AlarmEngine.stop` + cancels the alarm notification.
_Cross-layer contracts the UI/parent must honour:_ start via `WatchService.start(context, WatchParams)` /
`WatchService.stop(context)`; channel ids `watch_ongoing` (LOW) + `watch_alarm` (HIGH); notification ids
2001 (ongoing) and 1001 (alarm). _Not buildable/run-verified here (build was out of scope) — needs a device
pass for FGS + full-screen-intent on Android 14+._

**C — UI (entry + status/settings).** Repurposed the two template fragments. `FirstFragment` (entry): paste
link + number, `LinkParser.parse` with explicit inline errors — empty / unrecognised / short `/t/` link
(can't resolve cold, §2/§5) / missing queue — then requests `POST_NOTIFICATIONS` on 33+ via
`registerForActivityResult` (starts the watch either way; the perm only gates the posted notification),
calls `WatchService.start`, and navigates `action_FirstFragment_to_SecondFragment`. `SecondFragment` (status
+ settings): observes `WatchBus` through `MainViewModel` with `repeatOnLifecycle(STARTED)` — connection text,
coarse banner, `MyStatus` headline (Waiting N ahead / IT'S YOUR TURN → desk / Skipped / coarse last-called) +
a detail line; settings bound to `SettingsStore` (pre-alarm stepper 0–20 + six `MaterialSwitch`es), each
change persisted via `MainViewModel.updateSettings`; switches use a `setCheckedSilently` helper so the flow
echo doesn't re-trigger writes. STOP → `WatchService.stop` + nav back. Material3 layouts (TextInputLayout /
MaterialSwitch / MaterialButton), `fragment_second` scrollable. Removed the template FAB/Snackbar + options
menu (settings live on-screen); all user-facing strings in `strings.xml`. _Not build-verified (out of scope);
depends on the agreed `WatchService` FQN + `action_*` nav ids, both confirmed present._
