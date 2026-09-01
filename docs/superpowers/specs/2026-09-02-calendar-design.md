# Google Calendar delivery events — design

Notion task **18. Google Calendar delivery event integration**. Stretch, per the product brief's
"nice to have" list. One-way creation only.

## Problem

A confirmed order carries a delivery window nobody writes down. The agent knows it; the user's calendar
does not.

## An event, not a dependency

Nothing in the ordering flow may depend on Google being configured, connected, or reachable. So the
confirmation services publish `OrderConfirmedEvent` and stop caring:

```
CartConfirmationService     ─┐
                             ├─→ OrderConfirmedEvent ─→ (async) CalendarIntegrationService
ReorderConfirmationService  ─┘
```

The listener drops out early three ways — the integration is unconfigured, this user never connected a
calendar, or the slot carries no start time — and each of those is a normal Tuesday, logged at debug.
Same shape as the meal-plan hand-off from #7, for the same reason: the publishing thread is answering a
webhook.

## Feign, not Google's client library

The task suggests Google's Java client library. This uses two Feign clients instead — one for the token
endpoint, one for `POST /calendars/primary/events`.

The reason is that the work is already done: `SilpoOAuthApiClient` is a Feign client doing a
form-encoded OAuth token exchange against a real authorization server, and the calendar insert is a
single JSON POST with a bearer header. `google-api-services-calendar` would bring its own HTTP
transport, JSON layer and credential store to replace three files that follow the repository's stated
outbound-HTTP convention. For a stretch feature that creates one event, that trade is the wrong way
round.

## A separate table, not a discriminator column

`mcp_oauth_token` has `user_id` as its primary key. Adding a `provider` discriminator means a composite
key, a changed entity, and a migration touching the one table whose contents nobody can re-derive.
`google_oauth_token` is one changeset with the same shape, and it makes "these are different secrets
from different providers" true in the schema rather than in a column value.

Encryption is unchanged: the same `TokenCipher`, the same AES-256-GCM at rest, the same rule that
nothing logs a token and no endpoint returns one.

## Connecting is opt-in and explicit

`/calendar` in the chat answers with a link to Google's consent screen; `/auth/google/callback`
completes the exchange and stores the tokens. Someone who never types the command never has a calendar
touched. With no `GOOGLE_CLIENT_ID` configured the command says so plainly instead of offering a link
that cannot work.

## The delivery time has to exist first

`silpo_get_time_slots` returns windows; task 09 kept only the identifier, because an identifier is all
the cart needed. A calendar event needs the actual instant, so `CartSummary` gains
`deliverySlotStartsAt`, filled from the same `OfferedSlot` the slot id came from. Where the server's
date format defeats parsing the instant is null — and a null start time is one of the three reasons the
listener skips.

Duration is a flat two hours from the start. Silpo's slots are windows, but the end time is not
something the cart tools reliably return, and a two-hour block on a calendar communicates "delivery
around then" correctly.

## Out of scope

Updating or deleting the event when an order is cancelled or edited. One-way creation, as the task says.
The event carries the order id in its description, so a later task can find it again.
