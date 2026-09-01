# Inventory diffing and trend tracking — design

Notion task **13. Inventory diffing & trend tracking against baseline**. Product brief user flow #2,
steps 4–5. Deciding what to actually reorder is #14.

## Problem

Task 12 stores a `CheckinDelta` per check-in and nothing reads it. Two questions have to become
answerable from that history:

- **What does this household need soon?** — for the delta reorder (#14).
- **What does this household never actually eat?** — so plan regeneration (#7) stops suggesting it.

Both are approximate by design. This tracks a trend, not stock. Nobody weighs their buckwheat.

## The streak rule

One counter per `(user, item)`: how many check-ins in a row the item was reported as **still there**.

```
item in stillHave        → streak = streak + 1
item in runningLow/gone  → streak = 0
item not mentioned       → unchanged
```

An item at or above `komora.checkin.removal-threshold` (default 3) is a removal candidate.

The task text describes this as "increment when the item was *also* `stillHave` in the previous
check-in". That reading counts one cycle fewer than it looks: three consecutive `stillHave` check-ins
would score 2, and the acceptance criterion asks for three to be enough. Counting the appearances
themselves gives the same behaviour the sentence intends — the streak still breaks the moment an item
is consumed — and matches the criterion, so that is what is implemented.

**Not mentioned means unchanged**, never zero. A check-in message names two or three things; treating
everything unsaid as evidence would make the counter a measure of how talkative someone is.

## Restock resets, by construction

`stillHave → goneCompletely → (restocked) → stillHave` scores 1, 0, 1. The reset is not a special case
about baskets or orders — `goneCompletely` *is* consumption, and consumption breaks the streak. No part
of this needs to know that a restock happened in between.

## Upcoming needs

`getUpcomingNeeds(userId)` reads the most recent check-in **that parsed**, and returns
`goneCompletely` followed by `runningLow`, deduplicated, in that order. Gone first because #14 will
want the urgent half of the list at the top, and dedup because a person can say the same item twice in
one sentence.

An unparsed check-in is skipped rather than treated as "nothing needed": #12 already asked that user
to clarify, and an empty list here would quietly mean "no restock".

## Where the update happens

Inside `CheckinParsingService`, immediately after a check-in is stored. Every stored check-in updates
the trend — including the fridge-photo path (#17), which will store one the same way. Putting it in the
Telegram flow instead would make that invariant depend on which channel the answer arrived through.

## What consumes it

- `getRemovalCandidates(userId)` is added to the meal-plan prompt as an explicit "do not suggest" list,
  which is the only reason the counter exists. Empty list, no line — existing plans are unaffected.
- `getUpcomingNeeds(userId)` is left for #14.

## Out of scope

Choosing quantities, substitutions, promotions and the reorder cart are #14. This task ends at two
query methods and a counter that moves correctly.
