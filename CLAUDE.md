# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**WarteAlarm** is an Android app (Kotlin) that watches the **wartenummer.at** waiting-queue system and
sounds a loud alarm when it's the user's turn — so they can leave the waiting room / look away and still
be alerted.

**The current source tree is an unmodified Android Studio "Basic Views Activity" template** (MainActivity
hosting a NavHostFragment with `FirstFragment`/`SecondFragment`, view binding, Material toolbar + FAB). None
of the WarteAlarm functionality exists yet — the template is scaffolding to be replaced. The actual product
and its entire technical foundation live in two docs, which are the source of truth:

- **`firstdraft.md`** — the original request and goal.
- **`API-research.md`** — the authoritative reverse-engineering of wartenummer.at. **Read this before
  implementing anything**; the app's whole feasibility rests on its findings (especially §4, §6, §9, §10).

## Build, test, run

The Gradle **daemon runs on JDK 21** (pinned in `gradle/gradle-daemon-jvm.properties`, auto-provisioned),
and toolchain versions are bleeding-edge (AGP 9.2.1, Gradle wrapper 9.4.1, `compileSdk 36`). App code targets
Java 11. Use the wrapper for everything:

```bash
./gradlew assembleDebug                  # build the debug APK
./gradlew installDebug                   # build + install on a connected device/emulator
./gradlew testDebugUnitTest              # JVM unit tests (app/src/test)
./gradlew connectedDebugAndroidTest      # instrumented tests (app/src/androidTest) — needs a device/emulator
./gradlew lint                           # Android Lint → report at app/build/reports/lint-results-debug.html
./gradlew clean
```

Run a single unit test by fully-qualified name:

```bash
./gradlew testDebugUnitTest --tests "com.example.wartealarm.ExampleUnitTest.addition_isCorrect"
```

**Dependencies are managed through the Gradle version catalog**, not inline in `build.gradle.kts`. To add a
library, declare the version + library in `gradle/libs.versions.toml` and reference it as `libs.<name>` in
`app/build.gradle.kts` (this is how the planned `socket.io-client` dependency should be added).

## Architecture that matters (from API-research.md)

Implementing the alarm requires understanding a protocol that spans several findings — these are the
load-bearing decisions, not obvious from any single file:

- **Input model — everything travels in the link.** The user only ever has the email's ticket link plus a
  number (e.g. `Y46`). The link decodes to a **room id** and **queue id**; the app extracts
  `room = teampraxis-im-6ten`, `queue = teampraxis-im-6ten-liste-y`, and `number = 46`. There is no login
  step on the user's side. Design the app to parse the link → (room, queue, number).

- **Do NOT build on the REST API.** `/api/...` is session-auth-gated and returns `401` to any cold client;
  the ticket UUID is not a usable token and the link sets no session cookie (see API-research.md §5). REST is
  only reachable with a captured httpOnly session cookie — treat it as a fallback, not the primary path.

- **The socket.io `/room` feed is the foundation — it's open and unauthenticated.** Server is socket.io v2
  (`EIO=3`); on Android use `io.socket:socket.io-client:1.0.1` for compatibility. Connect to
  `https://wartenummer.at`, namespace `/room`, then on connect `emit("join", "<roomId>")`. The server then
  streams `room` (full room object), `time` (server clock), and — for the alarm — `tickets` (array of ticket
  objects). No auth required.

- **The alarm trigger.** Match your ticket in the `tickets` array by
  `queue_url == <queue> && number == <myNumber>`; when its `state` flips to **`"redeemed"`**, it's your turn
  (the `desk` field tells you which Zimmer). Provide a **pre-alarm** based on the count of `active` tickets
  ahead of you (`state == "active" && number < myNumber`). Ticket states and their meaning are tabulated in
  API-research.md §4.

- **Known unknown to verify (API-research.md §9):** it has *not* yet been confirmed that the `tickets` event
  reaches an *unauthenticated* socket (the clinic was closed during research — only `room`/`time` were seen).
  This is the single biggest risk. Fallbacks if it turns out to be auth-gated: a coarse signal from
  `last_drawn_ticket_number` in the `room` event, or accepting a pasted session cookie. Don't assume the
  zero-auth path works until verified during clinic open hours.

- **Android runtime requirements.** To fire reliably with the screen off, the watcher must run in a
  **foreground service** with a wakelock, and raise the alarm via a **full-screen high-priority notification**
  on the alarm audio stream (looping). A Node reference implementation of the exact match logic is in
  API-research.md §10 — mirror its logic when building the Kotlin client.

## Conventions

- Package / namespace: `com.example.wartealarm`. UI uses **view binding** (`buildFeatures { viewBinding = true }`)
  and the **Navigation Component** (`app/src/main/res/navigation/nav_graph.xml`) — keep new screens on this
  pattern rather than manual fragment transactions.
- There is no CI, no custom lint baseline, and no signing config beyond defaults yet.

## Working agreement

- **Verify-first.** Before starting any task, state how you'll verify it's correct.
- **Report-after.** After finishing, run that verification and report the actual results — including failures.
- **Hot zones — ask before touching (applies once these exist).** The current tree is just the template
  scaffold, so there are no hot zones *yet*. As the WarteAlarm build progresses, treat the following as hot
  zones: before changing them, stop, ask first, and explain the blast radius (what depends on it, what breaks
  if it's wrong):
  - **Alarm delivery** — the foreground service, full-screen-intent path, and alarm engine. A silent failure
    here means the user misses their turn; this is the app's entire reason to exist.
  - **Socket / protocol layer** — the wartenummer.at connection, `join`, event parsing, and number-matching.
    It's built on undocumented behavior and can break silently if assumptions shift.
  - **Manifest permissions, FGS type, and Play release config** — a wrong change can break screen-off delivery
    or get the app rejected.
