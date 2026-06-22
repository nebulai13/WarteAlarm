# WarteAlarm tools — live-feed probe

`probe.js` is the standalone Node reference listener from
[`../API-research.md`](../API-research.md) §10. It verifies the **§9 open question**: does the
per-ticket `tickets` event reach an *unauthenticated* socket while a queue is active? That answer is
make-or-break for WarteAlarm's zero-auth design (the whole app rides on the open socket.io `/room` feed).

## Run

```bash
cd tools
npm init -y
npm i socket.io-client@2        # server is socket.io v2 / EIO3 (§6)
node probe.js
```

It connects to `https://wartenummer.at` namespace `/room`, emits `join(<room>)`, then logs:

- each `room` event's `last_drawn_ticket_number` for your queue (the coarse fallback signal, §9a), and
- each `tickets` event: whether your number appeared, its state, how many `active` tickets are ahead,
  and a clear `YOUR TURN → <desk>` line when your ticket is `redeemed`.

On connect it prints a note that it's listening, and reminds you to run it **during clinic OPEN hours**
— a closed/empty queue broadcasts no `tickets` array (that's exactly why §9 was left open in research).

## Parameters

Defaults are the research values
(`teampraxis-im-6ten` / `teampraxis-im-6ten-liste-y` / `46`). Override via env vars **or** positional argv:

```bash
ROOM=<room> QUEUE=<queue> MY_NUMBER=<n> node probe.js
# or
node probe.js <room> <queue> <myNumber>
```

Press `Ctrl-C` to stop; it prints whether a `tickets` event was ever seen (i.e. whether §9 is confirmed).
