package com.example.wartealarm

import com.example.wartealarm.domain.MyStatus
import com.example.wartealarm.domain.QueueAnalyzer
import com.example.wartealarm.domain.RoomSnapshot
import com.example.wartealarm.domain.Ticket
import com.example.wartealarm.domain.TicketState
import com.example.wartealarm.domain.WnrQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [QueueAnalyzer.analyze] — the pure queue-reasoning core.
 *
 * Fixtures use the real §7 snapshot: room `teampraxis-im-6ten`, our queue `…-liste-y`, our number 46,
 * served at "Zimmer 4" (§7 desk mapping). Mirrors the Node reference logic in §10: match by integer
 * number within the queue, count `active` tickets ahead. No Android deps → runs under
 * `./gradlew testDebugUnitTest`.
 */
class QueueAnalyzerTest {

    private val room = "teampraxis-im-6ten"
    private val myQueue = "teampraxis-im-6ten-liste-y"
    private val otherQueue = "teampraxis-im-6ten-liste-a"
    private val myNumber = 46

    /** Builds a Liste Y queue carrying [lastDrawn] as its `last_drawn_ticket_number` (§7). */
    private fun listeY(lastDrawn: Int? = 46) =
        WnrQueue(urlName = myQueue, name = "Warteliste Y", lastDrawnTicketNumber = lastDrawn)

    /** A snapshot with an explicit ticket list (precise per-ticket feed available). */
    private fun snapshotWith(tickets: List<Ticket>, queues: List<WnrQueue> = listOf(listeY())) =
        RoomSnapshot(roomUrl = room, queues = queues, tickets = tickets)

    private fun ticket(number: Int, state: TicketState, queue: String = myQueue, desk: String? = null) =
        Ticket(number = number, state = state, queueUrl = queue, desk = desk)

    // §4: position = count of `active` tickets in MY queue with number < mine. Here 40, 43, 45 are active
    // and ahead → ahead=3, position=4 (ahead + 1).
    @Test
    fun `waiting reports ahead and position from active tickets with a smaller number`() {
        val status = QueueAnalyzer.analyze(
            snapshotWith(
                listOf(
                    ticket(40, TicketState.ACTIVE),
                    ticket(43, TicketState.ACTIVE),
                    ticket(45, TicketState.ACTIVE),
                    ticket(46, TicketState.ACTIVE), // mine
                    ticket(47, TicketState.ACTIVE), // behind me — must not count
                ),
            ),
            myQueue,
            myNumber,
        )

        assertEquals(MyStatus.Waiting(ahead = 3, position = 4), status)
    }

    // §4: only `active` tickets ahead count. inactive/redeemed/absent/finished/cancelled tickets with a
    // smaller number must NOT inflate the position.
    @Test
    fun `waiting ignores non-active tickets ahead of me`() {
        val status = QueueAnalyzer.analyze(
            snapshotWith(
                listOf(
                    ticket(41, TicketState.INACTIVE),  // not in line yet
                    ticket(42, TicketState.REDEEMED),  // already called
                    ticket(43, TicketState.ABSENT),    // skipped
                    ticket(44, TicketState.FINISHED),  // done
                    ticket(45, TicketState.ACTIVE),    // the only one truly ahead
                    ticket(46, TicketState.ACTIVE),    // mine
                ),
            ),
            myQueue,
            myNumber,
        )

        assertEquals(MyStatus.Waiting(ahead = 1, position = 2), status)
    }

    // §4: tickets in OTHER queues (each its own number series, §7) must never count toward my position,
    // even when their number is smaller than mine.
    @Test
    fun `waiting does not count active tickets from other queues`() {
        val status = QueueAnalyzer.analyze(
            snapshotWith(
                listOf(
                    ticket(10, TicketState.ACTIVE, queue = otherQueue), // Liste A — irrelevant
                    ticket(20, TicketState.ACTIVE, queue = otherQueue),
                    ticket(45, TicketState.ACTIVE), // Liste Y, ahead of me
                    ticket(46, TicketState.ACTIVE), // mine
                ),
                queues = listOf(listeY(), WnrQueue(urlName = otherQueue, name = "Warteliste A")),
            ),
            myQueue,
            myNumber,
        )

        assertEquals(MyStatus.Waiting(ahead = 1, position = 2), status)
    }

    // §4 "it's your turn" trigger: my ticket flips to `redeemed` → Called, with the desk from §7 (Zimmer 4).
    @Test
    fun `called names the desk when my ticket is redeemed`() {
        val status = QueueAnalyzer.analyze(
            snapshotWith(
                listOf(
                    ticket(45, TicketState.FINISHED),
                    ticket(46, TicketState.REDEEMED, desk = "Zimmer 4"), // mine — §7: Liste Y → Zimmer 4
                ),
            ),
            myQueue,
            myNumber,
        )

        assertEquals(MyStatus.Called("Zimmer 4"), status)
    }

    // §4: my ticket `absent` (called but no-show) → Skipped.
    @Test
    fun `skipped when my ticket is absent`() {
        val status = QueueAnalyzer.analyze(
            snapshotWith(listOf(ticket(46, TicketState.ABSENT))),
            myQueue,
            myNumber,
        )

        assertEquals(MyStatus.Skipped, status)
    }

    // §9 coarse fallback: tickets == null means no per-ticket feed, so fall back to the queue's
    // `last_drawn_ticket_number`. last_drawn=45 < my 46 → reached=false.
    @Test
    fun `coarse fallback when tickets is null and queue has not reached my number`() {
        val snapshot = RoomSnapshot(roomUrl = room, queues = listOf(listeY(lastDrawn = 45)), tickets = null)

        val status = QueueAnalyzer.analyze(snapshot, myQueue, myNumber)

        assertEquals(MyStatus.Coarse(lastDrawn = 45, reached = false), status)
    }

    // §9 coarse fallback: last_drawn=46 >= my 46 → reached=true ("it might be your turn" signal). This is
    // the §7 end-of-day state (Liste Y last_drawn_ticket_number == 46).
    @Test
    fun `coarse fallback reports reached once last drawn meets my number`() {
        val snapshot = RoomSnapshot(roomUrl = room, queues = listOf(listeY(lastDrawn = 46)), tickets = null)

        val status = QueueAnalyzer.analyze(snapshot, myQueue, myNumber)

        assertEquals(MyStatus.Coarse(lastDrawn = 46, reached = true), status)
    }

    // §9 coarse fallback edge: no matching queue / null last_drawn → Coarse(null, reached=false).
    @Test
    fun `coarse fallback with unknown last drawn reports null and not reached`() {
        val snapshot = RoomSnapshot(roomUrl = room, queues = listOf(listeY(lastDrawn = null)), tickets = null)

        val status = QueueAnalyzer.analyze(snapshot, myQueue, myNumber)

        assertEquals(MyStatus.Coarse(lastDrawn = null, reached = false), status)
    }

    // Ticket missing from a present list, but the queue has already drawn past my number → almost
    // certainly called/skipped, so Skipped (inferMissing path).
    @Test
    fun `missing ticket with queue drawn past my number is treated as skipped`() {
        val snapshot = snapshotWith(
            tickets = listOf(ticket(50, TicketState.ACTIVE)), // mine (46) not present
            queues = listOf(listeY(lastDrawn = 48)),          // already past 46
        )

        val status = QueueAnalyzer.analyze(snapshot, myQueue, myNumber)

        assertEquals(MyStatus.Skipped, status)
    }

    // Ticket missing from a present list and the queue has NOT yet reached my number → we simply don't
    // know yet → Unknown (inferMissing path).
    @Test
    fun `missing ticket with queue not yet at my number is unknown`() {
        val snapshot = snapshotWith(
            tickets = listOf(ticket(44, TicketState.ACTIVE)), // mine (46) not present
            queues = listOf(listeY(lastDrawn = 44)),          // hasn't reached 46
        )

        val status = QueueAnalyzer.analyze(snapshot, myQueue, myNumber)

        assertEquals(MyStatus.Unknown, status)
    }

    // §4: a `finished` ticket is also surfaced as Called (the analyzer groups REDEEMED+FINISHED). Noted
    // because it differs from a naive "only redeemed" reading — this matches QueueAnalyzer.kt as written.
    @Test
    fun `finished ticket is reported as called`() {
        val status = QueueAnalyzer.analyze(
            snapshotWith(listOf(ticket(46, TicketState.FINISHED, desk = "Zimmer 4"))),
            myQueue,
            myNumber,
        )

        assertEquals(MyStatus.Called("Zimmer 4"), status)
    }

    // Sanity: an `inactive` ticket (drawn, not yet in line) is still "waiting" with the active-ahead count.
    @Test
    fun `inactive ticket is reported as waiting`() {
        val status = QueueAnalyzer.analyze(
            snapshotWith(
                listOf(
                    ticket(45, TicketState.ACTIVE),
                    ticket(46, TicketState.INACTIVE), // mine
                ),
            ),
            myQueue,
            myNumber,
        )

        assertTrue(status is MyStatus.Waiting)
        assertEquals(MyStatus.Waiting(ahead = 1, position = 2), status)
    }
}
