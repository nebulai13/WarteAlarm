# WarteAlarm — API Research

Reverse-engineering of **wartenummer.at** to build an app that sounds an alarm when it's your turn.

- **Date of investigation:** 2026-06-22
- **Target service:** `https://wartenummer.at` (Austrian online waiting-number / call system)
- **Subject practice:** "Teampraxis im 6ten" (Vienna), room id `teampraxis-im-6ten`
- **Working ticket link (the only thing the patient receives by email):**
  `https://wartenummer.at/t/91f80409-4588-481c-977f-4c9a4a95a95c`
- **Email text:** *"Sie haben für Ihren heutigen Termin um 18:25 bei Teampraxis die Wartenummer "Y46" zugewiesen bekommen. Hier können Sie jederzeit die aktualisierte voraussichtliche Aufrufszeit abrufen: …/t/91f80409-…"*

> Note: "decrypt" was a misnomer — nothing is encrypted. The task was to **decipher the data layout / protocol**.

---

## TL;DR (the breakthrough)

- The page is an **AngularJS 1.x SPA**; all data comes from a backend over **REST** and **socket.io**.
- The **REST API (`/api/…`) is auth-gated** — returns `401 unauthorized_request` to a cold client. The ticket UUID is **not** a usable bearer token on REST, and the short link does **not** set a session cookie.
- **The socket.io feed is WIDE OPEN.** Connecting and emitting `join("teampraxis-im-6ten")` (the room id, which is embedded in the link) streams live room/queue/clock data with **zero authentication**.
- **Everything the alarm needs travels in the link** (room id + queue id) plus the number from the email (`Y46`). No login required for the live feed.
- **"Y46 ran out"** = end of day. At 21:03 server time every queue was `draw_state: too_late`, `currently_open: false`, and Liste Y's `last_drawn_ticket_number` was **46** — i.e. you were the last number called today; the day's queue is closed/cleared, so the ticket API now 401s.

---

## 1. Front-end stack

Fetched the raw HTML of the ticket page and listed asset references:

- **Framework:** AngularJS 1.x (`angular.min.js`, `angular-route`, `angular-cookies`, `angular-sanitize`, `angular-messages`, `ui-bootstrap`). Template placeholders like `{{ currentUser.username }}` confirm it's a client-rendered SPA — `WebFetch` of the URL sees only the empty shell.
- **Real-time:** `/socket.io/socket.io.js` (socket.io).
- **Audio:** `howler.js` — **the system already plays call sounds** (`slow-spring-board.mp3` on redeem, `nasty-error-long.mp3` on skip). We're essentially re-implementing that for ourselves.
- App logic: `app_cstm.js` (≈140 KB, minified to 4 lines). Config: `gulpNgConfig.js`.
- `<base href="/">`, HTML5-mode routing (no hash).

### Config (`gulpNgConfig.js`)
```
DEVELOPER_MODE = false
URL_INDEX   = https://wartenummer.at
URL_CSTM    = https://cstm.wartenummer.at      (customer backend constant; not referenced by name in app_cstm.js — same-origin /api is used)
URL_MGMT    = https://mgmt.wartenummer.at      (management UI)
URL_DEMO    = https://demo.wartenummer.at
```
In practice the SPA calls **same-origin** `https://wartenummer.at/api/...` and connects socket.io to the page origin.

---

## 2. URL / routing model

AngularJS routes (`$routeProvider`) of interest:

| Route | Controller | Notes |
|---|---|---|
| `/t/:ticketUrl` | (resolveRedirectTo) | **short link**; calls `TicketSvc.getByUrl(uuid)` = `GET /api/tickets/:uuid`, then redirects to the long URL |
| `/wartezimmer/:roomUrl/wartekreise/:queueUrl/ticket/:ticketUrl` | `CstmTicketInQueueCtrl` | the ticket-in-queue view |
| `/wartezimmer/:roomUrl` | `CstmQueueCtrl` | room view |

So the link decodes to **room + queue + ticket**:
```
room  = teampraxis-im-6ten
queue = teampraxis-im-6ten-liste-y
ticket(uuid/url_name) = 91f80409-4588-481c-977f-4c9a4a95a95c
```

---

## 3. REST API surface (extracted from `app_cstm.js`)

All same-origin under `/api`. (Auth-gated — see §5.)

| Method + path | Purpose |
|---|---|
| `GET /api/myuser` | current user (from session) |
| `GET /api/rooms` / `GET /api/rooms/:room` | rooms |
| `GET /api/rooms/bytoken?token=` | room by token |
| `GET /api/rooms/:room/desks` | desks |
| `GET /api/rooms/:room/queues` / `…/queues/:queue` | queues |
| `GET /api/tickets/:uuid` | **single ticket** (`TicketSvc.getByUrl`) |
| `GET /api/rooms/:room/tickets/non_cancelled` | **the live queue list** (`getNonCancelledByRoomUrl`) |
| `GET /api/rooms/:room/users/self/tickets/non_cancelled` | my tickets |
| `GET /api/rooms/:room/numbers/:number` | ticket by number |
| `POST /api/rooms/:room/queues/:queue/tickets/{web,sms,email,printer}` | draw a ticket |
| `PUT /api/rooms/:room/tickets/:url` `{state:"cancelled"}` | cancel |
| `POST /api/login` `{username,password}` | staff login |
| `POST /api/login/with_ticket` `{username, ticket_url}` | customer login via ticket |
| `POST /api/login/get_token?target=` `{username}` | request magic-link token (email) |
| `POST /api/login/with_token` `{userid, token}` | consume magic-link token |
| `DELETE /api/login` | logout |

---

## 4. Data model & state machine

### Ticket object (fields seen in code / `setClass` / `updateMyTicket`)
`uuid`, `url_name`, `number` (int, used for matching & position), `display_string` (shown label, e.g. the "Y" number), `state`, `queue_url`, `room_url`, `redeemTimeString`, `desk` (when redeemed), computed `current_index` (1-based position in rendered list).

### State → meaning → UI color (`TicketSvc.setClass`)
| `state` | class | meaning |
|---|---|---|
| `inactive` | btn-default (grey) | drawn, not yet up |
| `active` | btn-primary (blue) | **waiting in line** |
| `redeemed` | btn-success (green) | **CALLED — your turn** (assigned to a desk) |
| `finished` | btn-success (green) | done |
| `absent` | btn-warning (yellow) | called but no-show → skipped |
| `cancelled` | btn-danger (red) | cancelled |
| (issue_type `pause`) | btn-default | pause marker |

### "It's your turn" trigger
`updateMyTicket()` matches your ticket by **`number`** within the list. Alarm condition:
> a ticket with `queue_url == …-liste-y` AND `number == 46` flips to **`state === "redeemed"`** (the `desk` field then tells you which Zimmer).

If your `number` disappears from the list, the app nulls your ticket and shows *"Ihre Nummer wurde übersprungen"* and plays `nasty-error-long.mp3`. On redeem it plays `slow-spring-board.mp3`.

**Position** = count of `active` tickets in your queue with `number < yours`.

### Roles (`UserSvc.setUserLoggedIn`)
`anonymous` (default, username "Anonym"), `customer`, `room` (paired display device), `admin`.

### Session cookies (readable, set by server for UI; the real auth cookie is httpOnly)
`role`, `username`, `first_name`, `last_name`, `full_name`, `email`, `counter_feature`. Also `ticket_url` cookie stores the last ticket.

---

## 5. Auth investigation — trials & errors

**Goal:** find out how the patient's phone, which only has the email link, is authorized.

| # | Trial | Result |
|---|---|---|
| 1 | `GET /api/tickets/<uuid>` cold | **401** `unauthorized_request` |
| 2 | `GET /api/rooms/teampraxis-im-6ten` cold | **401** |
| 3 | `GET /api/rooms/.../tickets/non_cancelled` cold | **401** |
| 4 | `GET /` then reuse cookie jar | only cookie set is `DO-LB` (DigitalOcean load-balancer, `Max-Age=300`). **No app session.** Still 401. |
| 5 | `GET /t/<uuid>` (the short link) with cookie jar, follow redirects | HTTP 200 (SPA HTML), **only `DO-LB` set — no session cookie**. API still 401. → **the page load does not authenticate; the UUID is not a page-level capability either.** |
| 6 | `POST /api/login/with_ticket {ticket_url:<uuid>}` (no username) | **401** — needs a username too. |
| 7 | Header matrix on `GET /api/tickets/<uuid>`: `X-Requested-With: XMLHttpRequest`; `Origin`+`Referer`; both; `Wnr-Client: customer`; `Wnr-Client: cstm`; browser UA + XRW + Referer + Accept | **401 on every variant** — no header unlocks it. |

**Conclusion on REST auth:** the REST API requires a real authenticated **session** (httpOnly cookie). The customer side advertises `require_customer_login: true` and `require_customer_invitation: true`. The mechanism by which the email link logs the browser in could **not** be reproduced from a cold client in these tests (no cookie issued, UUID rejected on the API, login needs a username/token I don't possess). For the app, REST is therefore treated as **not available without a captured session cookie**.

> This is what made the "everything must be transported with the link" framing seem blocked — until the socket feed was checked (next section).

---

## 6. socket.io — the open live feed (BREAKTHROUGH)

### Versions / handshake (open, no auth)
- Client lib: **socket.io-client `2.1.2`** → **Engine.IO protocol 3 (`EIO=3`)**, classic v2 framing.
- `GET /socket.io/?EIO=3&transport=polling` → `200` with
  `0{"sid":"…","upgrades":["websocket"],"pingInterval":25000,"pingTimeout":5000}` then `40` (connected to default ns). `EIO=4` also answers `200`.

### Joining the room namespace (no auth) and what streams back
Flow over EIO3 long-polling (framing `<len>:<packet>`; `40/room,` = connect ns `/room`; `42/room,["join","<room>"]` = emit):
1. handshake → `sid`
2. POST `40/room,` → `ok`  (open `/room` namespace)
3. POST `42/room,["join","teampraxis-im-6ten"]` → `ok`
4. poll `GET …&sid=…` → **server pushes events**

**Events received by an unauthenticated socket:**
- `data` (on join) — full room object
- `room` — full room object (pushed on change)
- `time` — server clock string, every ~2 s (e.g. `"21:03:08"`)
- `trigger_tickets_reload` `false` / `trigger_room_reload` `false` — "refetch" hints for authed clients

In the AngularJS client, `roomSocket.on("tickets", t => setTickets(t))` expects `t` to be an **array of ticket objects** (each with `uuid`/`number`/`state`). We did **not** observe a `tickets` event during testing — see §9 (everything was closed/empty at 21:03).

### Namespace note
The client calls `io("/room", …)`. With socket.io v2, connect to namespace `/room` on the page origin → in a client lib: `io("https://wartenummer.at/room", { transports:["websocket"] })`.

---

## 7. Live room snapshot (captured 2026-06-22 ~21:03 via open socket)

Room **"Teampraxis"** (`teampraxis-im-6ten`), `currently_open: true`, `require_customer_login: true`, `require_customer_invitation: true`, `allow_online_draw: false`, `allow_anonymous_draw: false`.

### 9 queues (each its own number series)
| Name | url_name | currently_open | draw_state | last_drawn_ticket_number |
|---|---|---|---|---|
| Warteliste A | teampraxis-im-6ten-liste-a | false | too_late | 46 |
| Warteliste B | teampraxis-im-6ten-liste-b | false | too_late | 51 |
| Warteliste C | teampraxis-im-6ten-liste-c | false | too_late | 21 |
| Warteliste D | teampraxis-im-6ten-liste-d | false | too_late | 22 |
| Warteliste E | teampraxis-im-6ten-liste-e | false | too_late | 25 |
| Warteliste X | teampraxis-im-6ten-liste-x | false | too_late | 26 |
| **Warteliste Y** | **teampraxis-im-6ten-liste-y** | false | too_late | **46  ← our ticket** |
| Warteliste Z | teampraxis-im-6ten-liste-z | false | too_late | 30 |
| Warteliste W | teampraxis-im-6ten-liste-w | false | too_late | 0 |

**Queue object keys:** `name, url_name, short_description, active, currently_open, draw_state, last_drawn_ticket_number, max_nr_of_tickets_per_day, max_nr_of_tickets_per_day_reached, customer_ticket_initial_state, import_ticket_initial_state, inactive_ticket_weight, nr_of_workers, ticket_absent_limit, terminal, display_row_number, cstm_page_title, icon_filename, image_filename, enable_reservation_tickets_in_ui, enable_reservation_tickets_import, issues, hours, hours_today`.
(No per-queue "currently serving" field in the public broadcast — that comes from the `tickets` event / REST.)

### 9 desks (Zimmer 1–9), each wired to one queue
`active_queues` is a boolean array indexed in queue order **[A, B, C, D, E, X, Y, Z, W]** (indices 0–8).

| Desk | active index → queue |
|---|---|
| Zimmer 1 | 5 → Liste X |
| Zimmer 2 | 4 → Liste E |
| Zimmer 3 | 2 → Liste C |
| **Zimmer 4** | **6 → Liste Y** |
| Zimmer 5 | (none) |
| Zimmer 6 | 0 → Liste A |
| Zimmer 7 | 1 → Liste B |
| Zimmer 8 | 3 → Liste D |
| Zimmer 9 | (none) |

→ **Liste Y is served at Zimmer 4** today (mapping can change per day). `current_ticket_number` was `None` for all desks in the broadcast (closed). **Desk keys:** `name, url_name, short_description, active, open, capacity, active_queues, icon_filename, image_filename`.

---

## 8. Why "Y46 ran out"

1. **Tickets are per-day** — the app speaks of *"Wartenummer für **heute**"* (number *for today*).
2. At capture time (**21:03**, well after the 18:25 appointment) **all queues were `too_late` / closed**, and **Liste Y `last_drawn_ticket_number == 46`** — you were the last number called today.
3. The day's queue is finished/cleared, so the ticket's session/record is no longer live → the REST API returns `401`.

So "ran out" = normal end-of-day expiry, not encryption or a bug.

---

## 9. Open questions / to verify during OPEN hours

1. **Does the `tickets` event reach an unauthenticated socket?** We received `room`, `time`, and `trigger_*_reload` freely, but never a `tickets` array (all lists closed & empty tonight). The waiting-room display is inherently public, so it's **very likely** broadcast to all sockets in the room — but confirm while a queue is active.
   - If **yes** → the alarm works with **zero auth**, using only data from the link.
   - If **no** (withheld from anonymous sockets) → fall back to:
     - (a) coarse signal from `last_drawn_ticket_number` in the `room` event, or
     - (b) capture the httpOnly **session cookie** from a logged-in browser tab and send it with REST + socket.
2. Confirm `display_string` format for Liste Y (is it literally `"Y46"`, or `46` with a separate prefix?). Match on integer `number` to be safe.
3. Confirm the per-day reset time and whether the link is reusable next day (likely a new ticket/number each day).
4. Confirm the exact socket event name/payload for "now serving" (per-ticket `state==="redeemed"` with `desk`, vs. any aggregate field).

---

## 10. App design implications (for this Android project)

**Inputs the app extracts:**
- From the link: `room = teampraxis-im-6ten`, `queue = teampraxis-im-6ten-liste-y`.
- From the email (or user input): `number = 46`.

**Runtime (no login needed for the live feed):**
1. socket.io **v2-compatible** client → server is `2.1.2`. On Android use **`io.socket:socket.io-client:1.0.1`** (compatible with server v2 / EIO3). Connect to `https://wartenummer.at`, namespace `/room`.
2. On `connect` → `socket.emit("join", "teampraxis-im-6ten")`.
3. On `tickets` → find `t.queue_url == queue && t.number == 46`:
   - `state == "redeemed"` → **ALARM** (loud, looping; show `desk`/Zimmer).
   - pre-alarm when `active`-ahead count ≤ N (e.g. 2).
4. Keep a **foreground service** + wakelock so it fires with the screen off; use a full-screen high-priority notification + alarm-stream sound (`Howl` equivalents already exist server-side as `.mp3`s).
5. Fallback path if `tickets` is auth-only: poll `last_drawn_ticket_number` from the `room` event, and/or accept a pasted session cookie.

**Reference: Node listener (same logic, for quick verification on a laptop):**
```js
const io = require("socket.io-client"); // npm i socket.io-client@2
const ROOM="teampraxis-im-6ten", QUEUE="teampraxis-im-6ten-liste-y", MY_NUMBER=46;
const s = io("https://wartenummer.at/room", { transports:["websocket"] });
s.on("connect", () => s.emit("join", ROOM));
s.on("room",    r => console.log("Y last_drawn:", r.queues.find(q=>q.url_name===QUEUE)?.last_drawn_ticket_number));
s.on("tickets", list => {
  const mine  = list.find(t => t.queue_url===QUEUE && t.number===MY_NUMBER);
  const ahead = list.filter(t => t.queue_url===QUEUE && t.state==="active" && t.number<MY_NUMBER).length;
  console.log("state:", mine?.state ?? "—", "| ahead:", ahead);
  if (mine?.state === "redeemed") console.log(`\x07YOUR TURN → ${mine.desk}`);
});
```

---

## 11. Appendix — exact commands used

```bash
# raw HTML + asset list
curl -s -L "https://wartenummer.at/wartezimmer/teampraxis-im-6ten/wartekreise/teampraxis-im-6ten-liste-y/ticket/91f80409-4588-481c-977f-4c9a4a95a95c" -o page.html
grep -oE '(src|href)="[^"]+\.(js|css)[^"]*"' page.html | sort -u

# config + app bundle
curl -s -L "https://wartenummer.at/gulpNgConfig.js"
curl -s -L "https://wartenummer.at/app_cstm.js" -o app_cstm.js   # minified; grep with context windows

# REST auth probes (all 401)
curl -s "https://wartenummer.at/api/tickets/91f80409-4588-481c-977f-4c9a4a95a95c"
curl -s "https://wartenummer.at/api/rooms/teampraxis-im-6ten"
curl -s "https://wartenummer.at/api/rooms/teampraxis-im-6ten/tickets/non_cancelled"
#  + header matrix (X-Requested-With / Origin / Referer / Wnr-Client) — all 401
#  + short link cookie test: curl -c jar -L .../t/<uuid>  -> only DO-LB set

# socket.io (OPEN)
curl -s "https://wartenummer.at/socket.io/socket.io.js" | grep version   # "2.1.2"
curl -s "https://wartenummer.at/socket.io/?EIO=3&transport=polling"       # handshake -> sid
#  then POST 40/room, ; POST 42/room,["join","teampraxis-im-6ten"] ; GET poll -> data/room/time events
```

---

*Findings recorded from the reverse-engineering session of 2026-06-22. REST is auth-gated; the socket.io `/room` feed is open and is the foundation for the alarm — pending the open-hours `tickets`-event confirmation in §9.*
