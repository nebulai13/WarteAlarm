package com.example.wartealarm

import com.example.wartealarm.domain.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LinkParser] — the link → room/queue/ticket decoder.
 *
 * The sample URLs are taken straight from `API-research.md` §2 (the routing model) and the working
 * ticket link in the research header. Pure string logic, no Android, so this runs under
 * `./gradlew testDebugUnitTest`.
 */
class LinkParserTest {

    private val uuid = "91f80409-4588-481c-977f-4c9a4a95a95c"

    // §2: the long form /wartezimmer/:room/wartekreise/:queue/ticket/:uuid decodes to room+queue+ticket.
    @Test
    fun `full long link decodes room, queue and ticket uuid and is not a short link`() {
        val link = LinkParser.parse(
            "https://wartenummer.at/wartezimmer/teampraxis-im-6ten" +
                "/wartekreise/teampraxis-im-6ten-liste-y/ticket/$uuid",
        )

        assertEquals("teampraxis-im-6ten", link!!.room)
        assertEquals("teampraxis-im-6ten-liste-y", link.queue)
        assertEquals(uuid, link.ticketUrl)
        assertFalse(link.isShortLink)
    }

    // §2: the /t/:uuid short link carries only the ticket uuid; room/queue need the auth-gated REST API,
    // so they stay null and isShortLink flags that the UI must ask for the long link.
    @Test
    fun `short link sets isShortLink and leaves room and queue null`() {
        val link = LinkParser.parse("https://wartenummer.at/t/$uuid")

        assertNull(link!!.room)
        assertNull(link.queue)
        assertEquals(uuid, link.ticketUrl)
        assertTrue(link.isShortLink)
    }

    // §2: the room view /wartezimmer/:room has no queue segment → room set, queue/ticket null.
    @Test
    fun `room-only link decodes the room with no queue or ticket`() {
        val link = LinkParser.parse("https://wartenummer.at/wartezimmer/teampraxis-im-6ten")

        assertEquals("teampraxis-im-6ten", link!!.room)
        assertNull(link.queue)
        assertNull(link.ticketUrl)
        assertFalse(link.isShortLink)
    }

    // A long link without the /ticket/<uuid> tail is still a valid room+queue link; ticketUrl is null.
    @Test
    fun `long link without ticket segment decodes room and queue but null ticket`() {
        val link = LinkParser.parse(
            "https://wartenummer.at/wartezimmer/teampraxis-im-6ten/wartekreise/teampraxis-im-6ten-liste-y",
        )

        assertEquals("teampraxis-im-6ten", link!!.room)
        assertEquals("teampraxis-im-6ten-liste-y", link.queue)
        assertNull(link.ticketUrl)
        assertFalse(link.isShortLink)
    }

    // Pasted from an email: surrounding whitespace must be tolerated (parser trims the input).
    @Test
    fun `surrounding whitespace is tolerated`() {
        val link = LinkParser.parse(
            "  \n https://wartenummer.at/wartezimmer/teampraxis-im-6ten" +
                "/wartekreise/teampraxis-im-6ten-liste-y/ticket/$uuid \t ",
        )

        assertEquals("teampraxis-im-6ten", link!!.room)
        assertEquals("teampraxis-im-6ten-liste-y", link.queue)
        assertEquals(uuid, link.ticketUrl)
        assertFalse(link.isShortLink)
    }

    // A trailing query string / fragment must not bleed into the queue (or ticket) value: the regex
    // stops the queue group at /, ?, or #.
    @Test
    fun `trailing query string is not captured into the queue`() {
        val link = LinkParser.parse(
            "https://wartenummer.at/wartezimmer/teampraxis-im-6ten" +
                "/wartekreise/teampraxis-im-6ten-liste-y?lang=de&foo=bar",
        )

        assertEquals("teampraxis-im-6ten", link!!.room)
        assertEquals("teampraxis-im-6ten-liste-y", link.queue)
        assertNull(link.ticketUrl)
        assertFalse(link.isShortLink)
    }

    // A trailing query string after the ticket uuid must not bleed into the ticket value either.
    @Test
    fun `trailing query string is not captured into the ticket uuid`() {
        val link = LinkParser.parse(
            "https://wartenummer.at/wartezimmer/teampraxis-im-6ten" +
                "/wartekreise/teampraxis-im-6ten-liste-y/ticket/$uuid?utm_source=email",
        )

        assertEquals(uuid, link!!.ticketUrl)
        assertEquals("teampraxis-im-6ten-liste-y", link.queue)
    }

    // Anything that isn't a recognisable wartenummer.at link → null (the UI shows a paste error).
    @Test
    fun `non-matching string returns null`() {
        assertNull(LinkParser.parse("just some random text, not a link"))
        assertNull(LinkParser.parse("https://example.com/some/other/path"))
        assertNull(LinkParser.parse(""))
    }
}
