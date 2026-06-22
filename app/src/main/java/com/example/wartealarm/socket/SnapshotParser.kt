package com.example.wartealarm.socket

import com.example.wartealarm.domain.RoomSnapshot
import com.example.wartealarm.domain.Ticket
import com.example.wartealarm.domain.TicketState
import com.example.wartealarm.domain.WnrQueue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the raw `org.json` payloads of the wartenummer.at socket feed into the domain model.
 *
 * These are pure functions (no socket, no Android) so they can be unit-tested against recorded
 * fixtures. The wartenummer.at protocol is reverse-engineered and undocumented (`API-research.md`
 * §6/§7), so every read is defensive: a missing, null, or unexpectedly-typed field must yield a
 * sensible default rather than throw — the feed is the only thing standing between the user and a
 * missed appointment, so it must degrade gracefully rather than crash the watcher.
 *
 * The server's JSON uses snake_case keys (`url_name`, `currently_open`, …); the mapping to our
 * camelCase model lives here and nowhere else.
 */
object SnapshotParser {

    /**
     * Parses a `room` / `data` event (`API-research.md` §7) into a [RoomSnapshot].
     *
     * Only the room-level fields and the queue list come from this event; [RoomSnapshot.tickets] and
     * [RoomSnapshot.serverTime] are intentionally left at their defaults here. The repository merges
     * those in from the separate `tickets` and `time` events — keeping `tickets` `null` until a real
     * `tickets` event arrives is load-bearing (it selects coarse vs. precise mode, see the model docs).
     */
    fun parseRoom(room: JSONObject): RoomSnapshot = RoomSnapshot(
        // `url_name` is the room id embedded in the ticket link and our stable key for the room.
        roomUrl = room.optString("url_name"),
        roomName = room.stringOrNull("name"),
        currentlyOpen = room.optBoolean("currently_open", false),
        queues = parseQueues(room.optJSONArray("queues")),
    )

    /** Parses the room's `queues` array; a missing/empty array yields an empty list. */
    private fun parseQueues(queues: JSONArray?): List<WnrQueue> =
        queues.mapObjects(::parseQueue)

    /** Parses one queue object (keys per `API-research.md` §7). */
    private fun parseQueue(queue: JSONObject): WnrQueue = WnrQueue(
        urlName = queue.optString("url_name"),
        name = queue.stringOrNull("name"),
        currentlyOpen = queue.optBoolean("currently_open", false),
        drawState = queue.stringOrNull("draw_state"),
        // The coarse fallback signal; null (not 0) when absent so "unknown" stays distinct from "zero drawn".
        lastDrawnTicketNumber = queue.intOrNull("last_drawn_ticket_number"),
    )

    /**
     * Parses a `tickets` event — a JSON array of ticket objects — into [Ticket]s.
     *
     * An empty array is preserved as an empty list (the feed is present but currently empty), which is
     * deliberately different from the `null` tickets of a snapshot that has had no `tickets` event yet.
     */
    fun parseTickets(tickets: JSONArray?): List<Ticket> =
        tickets.mapObjects(::parseTicket)

    /** Parses one ticket object (fields per `API-research.md` §4). */
    private fun parseTicket(ticket: JSONObject): Ticket = Ticket(
        // We match the user's ticket by integer number, so a missing number means "no match" (0 is harmless).
        number = ticket.optInt("number", 0),
        state = TicketState.fromServer(ticket.stringOrNull("state")),
        queueUrl = ticket.stringOrNull("queue_url"),
        displayString = ticket.stringOrNull("display_string"),
        desk = parseDesk(ticket),
    )

    /**
     * Reads the `desk` field, which the server sends either as an object (with a `name`) or, leniently,
     * as a bare string — the exact shape is unconfirmed (`API-research.md` §9.4), so we accept both and
     * fall back to `null` when the ticket isn't redeemed / has no desk.
     */
    private fun parseDesk(ticket: JSONObject): String? {
        if (ticket.isNull("desk")) return null
        ticket.optJSONObject("desk")?.let { return it.stringOrNull("name") }
        return ticket.stringOrNull("desk")
    }
}

// --- Lenient org.json accessors -------------------------------------------------------------------
// org.json's optString returns the literal "null" for JSON null and "" for missing keys, and optInt
// can't express "absent". These helpers collapse those quirks into Kotlin nullables so the parser
// above reads cleanly.

/** Returns the trimmed string at [key], or null if the key is missing, JSON null, or blank. */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }

/** Returns the int at [key], or null if the key is missing or JSON null. */
private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

/**
 * Maps every [JSONObject] element of this (possibly null) array through [transform], skipping any
 * element that isn't an object so one malformed entry can't drop the whole list.
 */
private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        optJSONObject(i)?.let { out.add(transform(it)) }
    }
    return out
}
