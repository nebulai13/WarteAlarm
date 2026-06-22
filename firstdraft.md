# WarteAlarm — First Draft / Original Specification

> Captured verbatim from the start of the conversation (2026-06-22).

## Original request

> https://wartenummer.at/wartezimmer/teampraxis-im-6ten/wartekreise/teampraxis-im-6ten-liste-y/ticket/91f80409-4588-481c-977f-4c9a4a95a95c
> decrypt the waiting numbers -- i want to build an app that automatically sounds an alarm if its your turn -- for that I first need to understand the layout and my number run out

## Clarification 1

> decypher its not encrypted

## Clarification 2 (what the user actually receives)

> what is provided to the user is this email:
>
> *Sie haben für Ihren heutigen Termin um 18:25 bei Teampraxis die Wartenummer "Y46" zugewiesen bekommen. Hier können Sie jederzeit die aktualisierte voraussichtliche Aufrufszeit abrufen: https://wartenummer.at/t/91f80409-4588-481c-977f-4c9a4a95a95c*
>
> hence for the app everything must be transported with each link

---

## Restated goal

Build an app that **automatically sounds an alarm when it's your turn** in the wartenummer.at waiting queue.

Prerequisites the user identified:
1. **Understand the layout** — decipher (not decrypt) how the waiting numbers / queue data are structured.
2. Understand why **their number "ran out."**
3. Constraint: the app must work from **only what's transported in the link** (the email gives just the ticket link + the number "Y46").

See [API-research.md](API-research.md) for the full findings.
