# Spec: WarteAlarm

> Status: draft for review · 2026-06-22 · Source research: [`../firstdraft.md`](../firstdraft.md), [`../API-research.md`](../API-research.md)

## Problem

Patients with a **wartenummer.at** appointment (e.g. Teampraxis im 6ten) can only see when their number is
called by keeping the live web page in front of them. Step out, look away, or let the screen sleep and you can
miss your turn — and a missed call means you're skipped (`absent`). The official page plays a sound only while
it's open and focused; nothing alerts you in the background. People end up tethered to a screen in a waiting
room instead of waiting comfortably (outside, in the car, reading).

WarteAlarm watches the queue in the background and raises a configurable alarm the moment you're called, so you
can wait however you like and still make it to the right room.

## What it does

Paste your wartenummer.at ticket link and enter your number; the app connects to the queue's live public feed,
tracks your position, gives a configurable heads-up as you near the front, and raises a configurable alarm
(showing which Zimmer) when you're called — reliably, even with the screen off.

- Parse a wartenummer.at ticket link into **room + queue**; capture the user's **number**.
- Connect to the open **socket.io `/room`** feed (no login) and track the queue live.
- Show live position (how many `active` tickets are ahead of you) and queue open/closed state.
- **Configurable pre-alarm** ("warn when N ahead", default 2) plus the final alarm.
- **Configurable alarm modality**, combinable: sound (with an *only over headphones* option), vibration,
  visual blink (screen and/or camera flashlight), and "full system alarm" (max volume on the alarm stream,
  override Do-Not-Disturb).
- Final alarm names the **desk/Zimmer** you're called to.
- Keeps running with the screen off (foreground service + wakelock).
- **Coarse fallback**: if the per-ticket `tickets` event isn't delivered to anonymous sockets, fall back to
  `last_drawn_ticket_number` from the `room` event.

## Who it's for

Patients (Android users, minSdk 24) who have a wartenummer.at ticket and want to wait without staring at a
screen. Distributed publicly via the **Play Store**, so it must be self-explanatory to a first-time user who
only has the email link and their number.

## Who it's NOT for

- **Clinic staff / admins** — no management, desk, or ticket-drawing features.
- **Non-wartenummer.at queues** — the protocol and queue logic are specific to this provider.
- **iOS / web / Wear** — Android only for v1.
- **People wanting to book, draw, or cancel tickets** — WarteAlarm is *watch-only*; it never mutates the queue.
- **Use after the clinic's daily close** — tickets are per-day; a closed queue has nothing to watch.

## What success looks like

- Given a real ticket link + number during open hours, the app shows the correct live **position within a few
  seconds** of opening.
- When the number flips to `redeemed`, the configured alarm fires **within a few seconds even with the screen
  off and the app backgrounded**, and names the correct Zimmer.
- The pre-alarm fires at the configured threshold.
- Each alarm modality behaves as configured (e.g. *headphones-only* stays silent on the speaker; vibrate/flash
  work with sound off; full system alarm overrides DND).
- Survives a typical 30–90 min wait with socket reconnects across network blips without losing state.
- The §9 open-hours feed test passes (see Open questions) — verified end-to-end against a live queue at least once.
- Published and installable from the Play Store.

## Out of scope (v1)

- Drawing / booking / cancelling tickets (the auth-gated REST path) — watch-only.
- Watching multiple tickets / queues at once (one active ticket at a time).
- iOS, web, Wear OS.
- The cookie-paste authenticated REST fallback (v1 uses the open socket + coarse `last_drawn` only).
- Wait-time prediction / ETA beyond what the feed already provides; historical stats.
- User accounts, cloud sync, or any backend of our own (the app talks only to wartenummer.at).

## Constraints

- **Platform / stack:** this existing Android project — Kotlin, view binding, Navigation component, `minSdk 24`,
  `compileSdk 36`, Gradle 9.4.1 / AGP 9.2.1, Gradle daemon on JDK 21. Dependencies via the version catalog
  (`gradle/libs.versions.toml`).
- **Built on undocumented, reverse-engineered behavior** of wartenummer.at (socket.io v2 `/room` feed). No API
  contract — it can change or break with no notice.
- **socket.io client must be v2 / EIO3 compatible** → `io.socket:socket.io-client:1.0.1`.
- **Play Store requirements:** `USE_FULL_SCREEN_INTENT` declaration, a foreground-service type (Android 14+),
  a privacy policy, and store review. See risks.
- Tickets and numbers are **per-day**; the link is for a single appointment day.

## Key decisions

- **Data source** — auth-gated REST (`401` cold) vs. open socket.io `/room`. **Default: the open socket.io
  `/room` feed**, because it needs zero auth and everything required travels in the link + number (research §6).
- **Input model** — hardcode the clinic vs. paste any link. **Default: paste any link + enter number**, parsing
  room+queue from the URL; works generally and survives the daily reset.
- **socket.io client** — **Default: `io.socket:socket.io-client:1.0.1`** (compatible with server v2 / EIO3),
  added via the version catalog.
- **"Your turn" matching** — **Default: match on integer `number`** (`queue_url == queue && number == mine`),
  not `display_string` (its exact format is unconfirmed, §9.2); alarm when `state == "redeemed"`, read `desk`.
- **Fallback when `tickets` is withheld from anonymous sockets** — **Default: coarse signal from
  `last_drawn_ticket_number`** in the `room` event; defer the cookie-paste authed path.
- **Background execution** — WorkManager vs. foreground service. **Default: foreground service + partial
  wakelock**, since we hold a persistent live socket and must deliver with the screen off (WorkManager is for
  deferrable work — wrong fit).
- **Alarm delivery surface** — **Default: full-screen high-priority notification via `USE_FULL_SCREEN_INTENT`**,
  the only reliable way to surface over a locked screen.
- **Alarm modality** — **Default: a combinable set the user configures** — sound (+ *headphones-only* option
  driven by detecting the active audio route), vibration, visual blink (screen flash and/or camera flashlight),
  and "full system alarm" (alarm stream at max volume, DND override). When *headphones-only* is on and no
  headset is connected, fall back to the user's non-audio modalities (vibrate/flash) rather than going silent.
- **Pre-alarm** — **Default: configurable threshold** ("warn when N ahead", default 2) + final alarm.
- **App architecture** — **Default: single-activity + ViewModel + coroutines/Flow**; the socket lives behind a
  repository that exposes queue state as a Flow. Keep the existing Navigation + view-binding scaffolding.
- **Persistence** — **Default: DataStore / SharedPreferences** for the active ticket + settings; no database
  (single user, single ticket, per-day).

## Build plan

1. **Entry screen (link + number).** Decision: how to validate the pasted URL. Default — permissive parse of the
   long form `/wartezimmer/:room/wartekreise/:queue/...`; if only the short `/t/:uuid` link is given we can't
   derive room/queue cold (it resolves via authed REST), so prompt for the long link or a manual queue pick
   (see Open questions #2).
2. **Socket repository.** Connect to `https://wartenummer.at` namespace `/room`, `emit("join", room)`, expose
   `room` / `tickets` / `time` as Flows, with reconnect + backoff. Decision: threading. Default — wrap the
   socket.io callbacks in a `callbackFlow` on a coroutine dispatcher.
3. **Queue logic (pure Kotlin).** Compute position (count `active` with `number < mine`), detect `redeemed`,
   detect skipped (`absent` / number vanished). Default — pure functions over the ticket list, mirroring the
   Node reference in research §10, so they're fully unit-testable.
4. **Live status UI.** One screen bound to the ViewModel Flow: position, queue open/closed, last-drawn, current
   alarm config summary.
5. **Foreground service + wakelock.** Keep the socket alive screen-off. Decision: FGS type (Android 14+).
   Default — `specialUse` with a written justification (queue-watch/alarm); confirm against Play policy.
6. **Alarm engine.** Final + pre-alarm built on `USE_FULL_SCREEN_INTENT`, driving the configured modalities:
   looping alarm-stream sound, headphones-route detection for *headphones-only*, vibration patterns, screen
   flash + `CameraManager` torch blink, and a DND-override "full system alarm" path. Includes stop/snooze.
   Default — each modality is an independent toggle the alarm engine ORs together at fire time.
7. **Settings.** Pre-alarm threshold N, alarm modalities + per-modality options (sound choice, headphones-only,
   flash source), persisted via DataStore.
8. **Fallback path.** If no `tickets` event arrives within X seconds of join, switch to `last_drawn_ticket_number`
   coarse mode and show a banner explaining reduced precision.
9. **Play Store prep.** Privacy policy, `USE_FULL_SCREEN_INTENT` + FGS-type declarations, store listing, signing.
   Default — minimal privacy policy: no personal data leaves the device except the socket connection to
   wartenummer.at.

## Verification plan

- **Live-feed probe (do this FIRST).** Before building UI, connect to the live `/room` feed *during clinic open
  hours* and confirm the §9 open question — does the `tickets` event reach an anonymous socket? Use the Node
  reference in research §10 (`socket.io-client@2`) or a small JVM harness. This is make-or-break for the whole
  zero-auth design.
- **Link parsing & queue logic** — JUnit unit tests (`./gradlew testDebugUnitTest`, single test via
  `--tests "...ClassName.method"`) over sample links and recorded ticket-array fixtures built from research
  §4/§10. These are the load-bearing pure functions.
- **Alarm modalities** — manual device checklist, screen off / app backgrounded / DND on, triggering `redeemed`
  via a replayed/mock socket event: sound-only, headphones-only (with and without a headset connected),
  vibrate-only, flash-only, full system alarm. Confirm correct Zimmer shown and fire latency of a few seconds.
  Test specifically on Android 14+ for FGS + full-screen-intent behavior.
- **Reconnect resilience** — manual: toggle airplane mode mid-wait; confirm the socket reconnects and state recovers.
- **End-to-end** — one real (or a friend's) live appointment, start to finish.
- **Tools:** Android Studio + emulator/physical device, `./gradlew` (unit + `connectedDebugAndroidTest`), a
  mock-socket / event-replay fixture for deterministic alarm tests, Node + `socket.io-client@2` for the live probe.

## Open questions & risks

1. **§9 — does `tickets` reach an unauthenticated socket?** Unverified (clinic was closed during research; only
   `room`/`time` were observed). The entire zero-auth design depends on this — verify before building the UI.
   Mitigation: coarse `last_drawn` fallback.
2. **Short link can't be resolved cold.** `/t/:uuid` redirects via authed REST, so if the user only has the short
   link we can't derive room/queue. Need the long link or a manual queue picker — confirm what the email actually
   contains.
3. **`display_string` vs integer `number`** (§9.2) — matching on integer `number` to stay safe; confirm.
4. **Legal / ToS exposure — amplified by going public on the Play Store.** The app relies on undocumented,
   reverse-engineered endpoints of a third-party, health-adjacent service, with no permission. Personal use is
   low-risk; **publishing broadly is materially riskier** — the provider may object, may change or block the
   feed, and public distribution could conflict with their terms. Recommend checking wartenummer.at's terms (and
   ideally contacting them) before public release. *This is the blast radius of the Play Store decision.*
5. **Play Store policy for full-screen alarms (Android 14+).** `USE_FULL_SCREEN_INTENT` is gated to calling/alarm
   apps — an alarm is a legitimate use but needs a Console declaration and may be reviewed; the persistent-socket
   foreground service has no perfectly-matching FGS type (`specialUse`).
6. **Per-day reset & link reuse** (§9.3) — confirm the daily reset time and whether the link/number are reusable
   the next day (likely a new number each day).
7. **No contract — the server can change anytime** (socket.io upgrade, auth added, room-id scheme change). Build
   the socket layer to fail loudly and degrade gracefully rather than hang silently.
