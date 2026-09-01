# Reorder confirmation, slot selection and the baseline rule — design

Notion task **15. Delivery slot selection + reorder confirmation + baseline update**. Product brief
user flow #3, steps 5–7. Closes Stage 3.

## Problem

#14 builds a delta order and stops. It has three things a person has to decide about — substitutes for
what Silpo could not supply, a delivery slot, and whether to go ahead at all — and one thing the system
has to decide afterwards: whether this order becomes the new baseline.

## Reuse the shape task 10 established

Same split, same lifecycle: `ReorderConfirmationService` for the domain, `ReorderMessageService` in
`service.telegram` for the wording, a `DRAFT` `customer_order` written at presentation time so a
duplicate confirm callback is recognised and dropped. Nothing about that pattern needed reinventing.

What is new is that this conversation has more than one turn: a substitute can be accepted or rejected,
a slot can be changed, and only then is there something to confirm. That state lives in
`conversation_state.context_json` under a new `ConversationFlow.REORDER_CONFIRMATION`, because the
router already dispatches on that enum and a second handler on the same value would be a prefix check
pretending to be a state machine.

## Per item, not all or nothing

Every unsupplied item gets its own pair of buttons naming the substitute Silpo offered:

```
Хліб → Хліб житній, 27 грн
[Взяти]  [Не треба]
```

An all-or-nothing "accept all substitutions" is the thing the product brief warns against: the whole
value of asking is that someone can take the different bread and refuse the different milk.

Decisions accumulate in the conversation state. The confirm button stays available throughout — an
undecided substitute is simply not bought, which is the safe reading of silence.

## The delivery slot follows the household's habit

Most frequent day of the week among past confirmed orders, in Kyiv time. The first offered slot on that
day wins; with no history — a second order, say — the earliest offered slot does.

Slot timestamps are read leniently, like every other MCP field: `from`, `start`, `date` and friends are
tried in turn and parsed as an instant, a local date-time or a date. A slot whose day cannot be read
simply does not match a habit, and the fallback covers it. Guessing a strptime format for a server
nobody has seen would be worse.

## What counts as an edit

Rejecting a substitute. That is the only basket-changing action this UI offers, and it is the signal the
brief's rule turns on:

- **any edit** → the confirmed contents become the new current baseline, the previous one is superseded
  (`is_current = false`, kept), and `trust_level.consecutive_unedited_confirmations` resets to 0;
- **zero edits** → the baseline is left exactly as it was, and the counter increments.

Choosing a different delivery slot is not an edit. It changes when the food arrives, not what is in the
basket, and the baseline is a basket.

Removing a line or changing a quantity — the brief's other two examples — are not offered here. Twenty
items would mean twenty more buttons, and no acceptance criterion asks for them; the edit rule is
already exercised by rejection. Worth adding later as a free-text "прибери хліб" turn.

The trust counter is plumbing. Nothing reads it to skip a confirmation, and nothing in this task should:
auto-confirm is explicitly future work.

## Confirming

Accepted substitutes are added to the cart, `silpo_update_shopping_cart` sets the chosen slot, and the
cart is read back through #9's verify step — the same "never trust the write" rule as the first order.
The confirmed contents come from that verified cart, not from what we intended to add.

Then the `customer_order` row flips to `CONFIRMED` with the delta's own type (`SCHEDULED_REORDER` or
`AD_HOC`), the baseline rule above runs, and the closing message carries the savings figure computed in
#14 and Silpo's checkout link. Payment stays with the guest, as before.

## Failure behaviour

| Failure | Behaviour |
|---|---|
| No slots offered at all | the order is presented without one; confirming says so and asks to try later |
| Slot update refused | the order still confirms; the message says the slot was not fixed |
| Second confirm tap | the row is no longer `DRAFT`; acknowledged and dropped |
| A tap with no draft behind it | ignored — a keyboard from a previous cycle |

## Out of scope

Auto-confirmation, and the scheduler that decides when a reorder cycle begins.
