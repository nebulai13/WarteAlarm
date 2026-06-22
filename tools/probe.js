#!/usr/bin/env node
/*
 * WarteAlarm — live-feed probe (reference listener from API-research.md §10).
 *
 * Purpose: the §9 open question. We freely received `room`/`time` events on an anonymous socket, but
 * never a `tickets` array (the clinic was closed during research). This script connects exactly like the
 * AngularJS client and logs whether a `tickets` event ever reaches an UNAUTHENTICATED socket while a
 * queue is active — the make-or-break check for the whole zero-auth design.
 *
 * Run during clinic OPEN hours. Closed queues broadcast nothing useful.
 *
 *   cd tools && npm i socket.io-client@2 && node probe.js
 *
 * Parameters (env var or positional argv, both optional — defaults are the research values):
 *   ROOM       (argv[2])  default "teampraxis-im-6ten"
 *   QUEUE      (argv[3])  default "teampraxis-im-6ten-liste-y"
 *   MY_NUMBER  (argv[4])  default 46
 *
 *   ROOM=... QUEUE=... MY_NUMBER=... node probe.js
 *   node probe.js <room> <queue> <myNumber>
 */

const io = require("socket.io-client"); // npm i socket.io-client@2  (server is v2 / EIO3, §6)

const ROOM = process.env.ROOM || process.argv[2] || "teampraxis-im-6ten";
const QUEUE = process.env.QUEUE || process.argv[3] || "teampraxis-im-6ten-liste-y";
const MY_NUMBER = parseInt(process.env.MY_NUMBER || process.argv[4] || "46", 10);

const BASE = "https://wartenummer.at";

function ts() {
  return new Date().toISOString().slice(11, 19); // HH:MM:SS, local-ish for log readability
}
function log(...args) {
  console.log(`[${ts()}]`, ...args);
}

log(`WarteAlarm probe — ROOM=${ROOM} QUEUE=${QUEUE} MY_NUMBER=${MY_NUMBER}`);

// Namespace /room on the page origin, same as the AngularJS client's io("/room", …) (§6 namespace note).
const socket = io(`${BASE}/room`, {
  transports: ["websocket"],
  reconnection: true,
});

let sawTicketsEvent = false;

socket.on("connect", () => {
  log("connected (anonymous socket, id=" + socket.id + ")");
  socket.emit("join", ROOM);
  log(`emitted join("${ROOM}")`);
  // The §9 check, spelled out:
  log(
    "listening… (run during clinic OPEN hours; watching for a `tickets` event on an anonymous socket)",
  );
});

// `room` (and the initial `data`) event → full room object. Coarse fallback signal lives here (§9a).
function logRoom(label, r) {
  const q = r && Array.isArray(r.queues)
    ? r.queues.find((x) => x.url_name === QUEUE)
    : undefined;
  const lastDrawn = q ? q.last_drawn_ticket_number : "—";
  const open = q ? q.currently_open : "—";
  const drawState = q ? q.draw_state : "—";
  log(`${label}: ${QUEUE} last_drawn=${lastDrawn} open=${open} draw_state=${drawState}`);
}
socket.on("data", (r) => logRoom("data ", r)); // sent once on join (§6)
socket.on("room", (r) => logRoom("room ", r));

// THE event we care about (§9). If this ever fires on this anonymous socket, the zero-auth design works.
socket.on("tickets", (list) => {
  if (!Array.isArray(list)) {
    log("tickets: (non-array payload)", list);
    return;
  }
  if (!sawTicketsEvent) {
    sawTicketsEvent = true;
    log("*** §9 CONFIRMED: a `tickets` event reached the anonymous socket ***");
  }

  const mine = list.find((t) => t.queue_url === QUEUE && t.number === MY_NUMBER);
  const ahead = list.filter(
    (t) => t.queue_url === QUEUE && t.state === "active" && t.number < MY_NUMBER,
  ).length;

  log(
    `tickets: count=${list.length} | my state=${mine ? mine.state : "—"} | ahead=${ahead}`,
  );

  if (mine && (mine.state === "redeemed" || mine.state === "finished")) {
    // \x07 = terminal bell, same audible cue idea as the app's alarm.
    log(`\x07YOUR TURN → ${mine.desk || "(desk unknown)"}`);
  } else if (!mine) {
    log("  (my number not present in this tickets payload)");
  }
});

// Informational: the server's "refetch" hints and clock, for completeness (§6).
socket.on("trigger_tickets_reload", (v) => log("trigger_tickets_reload:", v));
socket.on("trigger_room_reload", (v) => log("trigger_room_reload:", v));
// `time` fires every ~2s — keep it quiet unless you want a heartbeat; uncomment to see it.
// socket.on("time", (t) => log("time:", t));

socket.on("connect_error", (e) => log("connect_error:", e && e.message ? e.message : e));
socket.on("error", (e) => log("error:", e && e.message ? e.message : e));
socket.on("disconnect", (reason) => log("disconnect:", reason));

process.on("SIGINT", () => {
  log(
    sawTicketsEvent
      ? "exiting — §9 verified (tickets event was received)."
      : "exiting — no `tickets` event seen (queue may have been closed/empty; retry during open hours).",
  );
  socket.close();
  process.exit(0);
});
