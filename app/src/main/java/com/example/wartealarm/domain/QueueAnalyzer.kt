package com.example.wartealarm.domain

/**
 * Where the user's number stands in its queue, derived from a [RoomSnapshot].
 *
 * This is what the service turns into alarms and the UI renders, so the cases are phrased in
 * user-facing terms rather than raw ticket states.
 */
sealed interface MyStatus {

    /** Still waiting. [ahead] people are in front; [position] is 1-based (`ahead + 1`). */
    data class Waiting(val ahead: Int, val position: Int) : MyStatus

    /** It's the user's turn. [desk] is the room/"Zimmer" to go to, when known. This fires the alarm. */
    data class Called(val desk: String?) : MyStatus

    /** The number was called but missed, or vanished from the list — the user was skipped. */
    data object Skipped : MyStatus

    /**
     * Coarse fallback when the precise per-ticket feed isn't available (`API-research.md` §9):
     * we only know the queue's [lastDrawn] number. [reached] is true once it meets/passes the user's
     * number, which is the best "it might be your turn" signal we have in this mode.
     */
    data class Coarse(val lastDrawn: Int?, val reached: Boolean) : MyStatus

    /** Not enough information yet (e.g. the ticket hasn't appeared in the feed). */
    data object Unknown : MyStatus
}

/**
 * Pure queue reasoning — no Android, no I/O, fully unit-testable.
 *
 * Mirrors the client logic decoded in `API-research.md` §4/§10: match the user's ticket by integer
 * number within their queue, and count the `active` tickets ahead of them for position.
 */
object QueueAnalyzer {

    /**
     * Computes the user's [MyStatus] for ticket [myNumber] in [queueUrl] given the latest [snapshot].
     *
     * When [RoomSnapshot.tickets] is `null` we have no per-ticket data and fall back to the coarse
     * `last_drawn_ticket_number` signal from the matching queue.
     */
    fun analyze(snapshot: RoomSnapshot, queueUrl: String, myNumber: Int): MyStatus {
        val tickets = snapshot.tickets
            ?: return coarse(snapshot, queueUrl, myNumber)

        val mine = tickets.firstOrNull { it.queueUrl == queueUrl && it.number == myNumber }
            ?: return inferMissing(snapshot, queueUrl, myNumber)

        return when (mine.state) {
            TicketState.REDEEMED, TicketState.FINISHED -> MyStatus.Called(mine.desk)
            TicketState.ABSENT, TicketState.CANCELLED -> MyStatus.Skipped
            TicketState.ACTIVE, TicketState.INACTIVE -> {
                val ahead = tickets.count {
                    it.queueUrl == queueUrl && it.state == TicketState.ACTIVE && it.number < myNumber
                }
                MyStatus.Waiting(ahead = ahead, position = ahead + 1)
            }
            TicketState.UNKNOWN -> MyStatus.Unknown
        }
    }

    /** Coarse signal from the queue's `last_drawn_ticket_number` when no per-ticket feed exists. */
    private fun coarse(snapshot: RoomSnapshot, queueUrl: String, myNumber: Int): MyStatus {
        val lastDrawn = snapshot.queues.firstOrNull { it.urlName == queueUrl }?.lastDrawnTicketNumber
        return MyStatus.Coarse(lastDrawn = lastDrawn, reached = lastDrawn != null && lastDrawn >= myNumber)
    }

    /**
     * The ticket isn't in an otherwise-present list. If the queue has already drawn past the user's
     * number they were almost certainly called/skipped; otherwise we simply don't know yet.
     */
    private fun inferMissing(snapshot: RoomSnapshot, queueUrl: String, myNumber: Int): MyStatus {
        val lastDrawn = snapshot.queues.firstOrNull { it.urlName == queueUrl }?.lastDrawnTicketNumber
        return if (lastDrawn != null && lastDrawn >= myNumber) MyStatus.Skipped else MyStatus.Unknown
    }
}
