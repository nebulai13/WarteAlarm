package com.example.wartealarm.domain

/**
 * Core domain model for the wartenummer.at queue feed.
 *
 * These types are a deliberately small, framework-free vocabulary that the rest of the app shares:
 * the socket layer parses raw JSON into them, the [QueueAnalyzer] reasons over them, and the UI and
 * alarm layers render them. Keeping them pure (no Android imports) is what makes the queue logic
 * unit-testable without a device.
 *
 * Field meanings are taken from the reverse-engineering notes in `API-research.md` §4 and §7.
 */

/** Lifecycle of a single ticket, mirroring the server's `state` string (`API-research.md` §4). */
enum class TicketState {
    /** Drawn but not yet in line. */
    INACTIVE,

    /** Waiting in line — counts toward the people ahead of you. */
    ACTIVE,

    /** Called — it's this ticket's turn; [Ticket.desk] names the room. */
    REDEEMED,

    /** Served and done. */
    FINISHED,

    /** Called but no-show, so skipped. */
    ABSENT,

    /** Cancelled. */
    CANCELLED,

    /** Any state we don't recognise — treated conservatively. */
    UNKNOWN;

    companion object {
        /** Maps the server's lowercase `state` string to a [TicketState]; unknown/null → [UNKNOWN]. */
        fun fromServer(raw: String?): TicketState = when (raw?.lowercase()) {
            "inactive" -> INACTIVE
            "active" -> ACTIVE
            "redeemed" -> REDEEMED
            "finished" -> FINISHED
            "absent" -> ABSENT
            "cancelled" -> CANCELLED
            else -> UNKNOWN
        }
    }
}

/**
 * One ticket in a queue. We match the user's ticket by integer [number] within [queueUrl] — the
 * `display_string` label format is unconfirmed (`API-research.md` §9.2), so it is informational only.
 */
data class Ticket(
    val number: Int,
    val state: TicketState,
    val queueUrl: String?,
    val displayString: String? = null,
    /** Desk / "Zimmer" assigned once the ticket is [TicketState.REDEEMED]. */
    val desk: String? = null,
)

/** One waiting list within a room (each queue is its own number series). */
data class WnrQueue(
    val urlName: String,
    val name: String? = null,
    val currentlyOpen: Boolean = false,
    val drawState: String? = null,
    /**
     * The highest number called so far in this queue. This is the coarse fallback signal used when
     * the per-ticket feed is unavailable to anonymous sockets (`API-research.md` §9).
     */
    val lastDrawnTicketNumber: Int? = null,
)

/**
 * A point-in-time view of a room, assembled from the socket feed.
 *
 * [tickets] is `null` until we actually receive a `tickets` event. That distinction is load-bearing:
 * a `null` list means "the precise per-ticket feed is not (yet) available, fall back to coarse mode",
 * whereas an empty list means "the feed is available and currently empty".
 */
data class RoomSnapshot(
    val roomUrl: String,
    val roomName: String? = null,
    val currentlyOpen: Boolean = false,
    val queues: List<WnrQueue> = emptyList(),
    val tickets: List<Ticket>? = null,
    val serverTime: String? = null,
)
