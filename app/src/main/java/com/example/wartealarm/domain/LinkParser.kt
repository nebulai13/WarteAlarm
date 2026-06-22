package com.example.wartealarm.domain

/**
 * The room/queue/ticket identifiers decoded from a wartenummer.at link (`API-research.md` §2).
 *
 * A long link carries [room] and [queue]; the short `/t/<uuid>` link carries only [ticketUrl] and
 * cannot be resolved to a room/queue without the auth-gated REST API, so [isShortLink] flags that the
 * UI must ask the user for the long link (or pick a queue manually).
 */
data class TicketLink(
    val room: String?,
    val queue: String?,
    val ticketUrl: String?,
    val isShortLink: Boolean,
)

/**
 * Decodes a pasted wartenummer.at URL into its [TicketLink] parts.
 *
 * Pure and string-only so it can be unit-tested directly. Matching is intentionally permissive: we
 * search anywhere in the input (not anchored) so trailing query strings, fragments, or surrounding
 * whitespace pasted from an email don't trip it up.
 */
object LinkParser {

    // /wartezimmer/<room>/wartekreise/<queue>[/ticket/<uuid>]
    private val longLink =
        Regex("""/wartezimmer/([^/]+)/wartekreise/([^/?#]+)(?:/ticket/([^/?#]+))?""")

    // /t/<uuid>
    private val shortLink = Regex("""/t/([^/?#]+)""")

    // /wartezimmer/<room>   (room view only, no queue)
    private val roomOnly = Regex("""/wartezimmer/([^/?#]+)""")

    /** Returns the decoded [TicketLink], or `null` if [input] is not a recognisable link. */
    fun parse(input: String): TicketLink? {
        val text = input.trim()

        longLink.find(text)?.let { m ->
            return TicketLink(
                room = m.groupValues[1],
                queue = m.groupValues[2],
                ticketUrl = m.groupValues[3].ifBlank { null },
                isShortLink = false,
            )
        }
        shortLink.find(text)?.let { m ->
            return TicketLink(room = null, queue = null, ticketUrl = m.groupValues[1], isShortLink = true)
        }
        roomOnly.find(text)?.let { m ->
            return TicketLink(room = m.groupValues[1], queue = null, ticketUrl = null, isShortLink = false)
        }
        return null
    }
}
