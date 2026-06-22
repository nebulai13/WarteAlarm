package com.example.wartealarm

import com.example.wartealarm.domain.TicketState
import com.example.wartealarm.socket.SnapshotParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the defensive `org.json` → domain parsing in [SnapshotParser].
 *
 * Fixtures mirror the real room broadcast in `API-research.md` §7 (Liste Y, last_drawn 46, Zimmer 4).
 * The whole point of this layer is to never crash on the undocumented, reverse-engineered feed, so the
 * cases lean on missing / null / wrongly-typed fields as much as the happy path.
 */
class SnapshotParserTest {

    // --- parseRoom -------------------------------------------------------------------------------

    @Test
    fun `parseRoom maps room fields and queues`() {
        val room = JSONObject(
            """
            {
              "url_name": "teampraxis-im-6ten",
              "name": "Teampraxis",
              "currently_open": true,
              "queues": [
                { "url_name": "teampraxis-im-6ten-liste-y", "name": "Warteliste Y",
                  "currently_open": false, "draw_state": "too_late", "last_drawn_ticket_number": 46 }
              ]
            }
            """.trimIndent(),
        )

        val snapshot = SnapshotParser.parseRoom(room)

        assertEquals("teampraxis-im-6ten", snapshot.roomUrl)
        assertEquals("Teampraxis", snapshot.roomName)
        assertTrue(snapshot.currentlyOpen)
        assertEquals(1, snapshot.queues.size)
        with(snapshot.queues.first()) {
            assertEquals("teampraxis-im-6ten-liste-y", urlName)
            assertEquals("Warteliste Y", name)
            assertFalse(currentlyOpen)
            assertEquals("too_late", drawState)
            assertEquals(46, lastDrawnTicketNumber)
        }
    }

    @Test
    fun `parseRoom leaves tickets and serverTime null - the merge fills them in`() {
        // A room event must never set tickets; null tickets is what selects coarse mode downstream.
        val snapshot = SnapshotParser.parseRoom(JSONObject("""{ "url_name": "r" }"""))

        assertNull(snapshot.tickets)
        assertNull(snapshot.serverTime)
    }

    @Test
    fun `parseRoom on an empty object yields safe defaults`() {
        val snapshot = SnapshotParser.parseRoom(JSONObject("{}"))

        assertEquals("", snapshot.roomUrl)
        assertNull(snapshot.roomName)
        assertFalse(snapshot.currentlyOpen)
        assertTrue(snapshot.queues.isEmpty())
    }

    @Test
    fun `parseRoom keeps lastDrawn null when absent, distinct from zero`() {
        val room = JSONObject("""{ "queues": [ { "url_name": "q" } ] }""")

        assertNull(SnapshotParser.parseRoom(room).queues.first().lastDrawnTicketNumber)
    }

    @Test
    fun `parseRoom skips malformed (non-object) queue entries`() {
        // One bad element must not drop the whole list — only the valid object survives.
        val room = JSONObject(
            """{ "queues": [ "not-an-object", { "url_name": "q-ok", "last_drawn_ticket_number": 7 } ] }""",
        )

        val queues = SnapshotParser.parseRoom(room).queues
        assertEquals(1, queues.size)
        assertEquals("q-ok", queues.first().urlName)
        assertEquals(7, queues.first().lastDrawnTicketNumber)
    }

    // --- parseTickets ----------------------------------------------------------------------------

    @Test
    fun `parseTickets maps each ticket's fields and state`() {
        val tickets = JSONArray(
            """
            [
              { "number": 46, "state": "active", "queue_url": "teampraxis-im-6ten-liste-y",
                "display_string": "Y46" }
            ]
            """.trimIndent(),
        )

        val parsed = SnapshotParser.parseTickets(tickets)

        assertEquals(1, parsed.size)
        with(parsed.first()) {
            assertEquals(46, number)
            assertEquals(TicketState.ACTIVE, state)
            assertEquals("teampraxis-im-6ten-liste-y", queueUrl)
            assertEquals("Y46", displayString)
        }
    }

    @Test
    fun `parseTickets reads desk from an object's name`() {
        val tickets = JSONArray(
            """[ { "number": 46, "state": "redeemed", "desk": { "name": "Zimmer 4" } } ]""",
        )

        assertEquals("Zimmer 4", SnapshotParser.parseTickets(tickets).first().desk)
    }

    @Test
    fun `parseTickets accepts desk as a bare string`() {
        val tickets = JSONArray("""[ { "number": 46, "state": "redeemed", "desk": "Zimmer 4" } ]""")

        assertEquals("Zimmer 4", SnapshotParser.parseTickets(tickets).first().desk)
    }

    @Test
    fun `parseTickets desk is null when absent`() {
        val tickets = JSONArray("""[ { "number": 46, "state": "active" } ]""")

        assertNull(SnapshotParser.parseTickets(tickets).first().desk)
    }

    @Test
    fun `parseTickets defaults a missing number to 0 and an unknown state to UNKNOWN`() {
        val tickets = JSONArray("""[ { "state": "teleported" } ]""")

        with(SnapshotParser.parseTickets(tickets).first()) {
            assertEquals(0, number)
            assertEquals(TicketState.UNKNOWN, state)
        }
    }

    @Test
    fun `parseTickets distinguishes a null array (empty) from an empty array`() {
        assertTrue(SnapshotParser.parseTickets(null).isEmpty())
        assertTrue(SnapshotParser.parseTickets(JSONArray("[]")).isEmpty())
    }
}
