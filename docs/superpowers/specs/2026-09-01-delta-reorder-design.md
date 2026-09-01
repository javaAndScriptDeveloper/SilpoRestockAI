# Delta order builder — design

Notion task **14. Delta order builder (replacements, promotions, bonuses)**. Product brief user flow
#3, steps 1–5. Slot selection and confirmation are #15.

## Problem

The first order was a whole week's shopping list. A reorder must not be. By now the system knows what
ran out (#13's `getUpcomingNeeds`) and what nobody eats (#13's `getRemovalCandidates`), and the job is
to turn that into the smallest cart that fixes the fridge — with substitutes offered for whatever Silpo
cannot supply, and promotions taken where they exist.

## Reuse, not a second cart flow

`CartBuildingService` already owns the documented sequence and exposes every step separately. The
reorder composes those steps and inserts two of its own calls between them:

```
getOrCreateCartContext   (#9)
silpo_get_promotions     (new)
resolveProducts          (#9)   ← promo variants preferred here
addProductsToCart        (#9)
getVerifiedCart          (#9)
silpo_get_replacements   (new)  ← only for what did not make it into the cart
```

No time slot is fetched. #15 owns slot selection, so `getVerifiedCart` is called with a null slot and
#15 fills it in before the order is written.

## How much of each item

Quantities come from the household's current baseline, not from thin air: the baseline is what they
confirmed buying last time, and "the same amount of milk as last time" is the right default for a
restock. An item with no baseline line falls back to one unit.

## Replacements are offered, never applied

An item can fail to arrive two ways — no search hit, or a hit that the verified cart does not contain.
Both are the same thing to the user, so both are collected after the verify step and asked about once:
`silpo_get_replacements` per missing item, capped so a broken branch cannot turn one reorder into forty
calls.

The suggestions travel in the result as `pendingReplacements`, next to the original name. Nothing is
substituted here. Swapping someone's bread for a different bread without asking is exactly the kind of
"helpful" the brief warns against, and #15 has the user in front of it.

## Promotions, and an honest savings figure

`silpo_get_promotions` is read once per branch before resolving. When a needed item matches a promoted
product, that product id is used and the difference between its old and new price, times the quantity,
is added to the estimate.

The estimate is explicitly rough: it compares what Silpo says the item used to cost against what it
costs now, for the lines that matched a promotion. It is a number to show, not an accounting figure,
and the message that renders it should read that way.

## The result

```java
DeltaOrder(userId, type, triggerItem, cart, reordered, pendingReplacements, estimatedSavings, excluded)
```

Three separate lists, because #15 renders them differently: what is in the cart, what needs a decision,
and what was deliberately left out (the removal candidates — worth showing once so the user can object).
`type` is `SCHEDULED_REORDER` or `AD_HOC` depending on which entry point was used, and `triggerItem`
names the item that could not wait.

## Two entry points, one path

`buildScheduledDeltaOrder(userId)` and `buildTriggeredDeltaOrder(userId, triggerItem)` differ only in
the order type and in the trigger item being folded into the needs list. Everything after that is the
same method, so the two cannot drift apart.

No scheduler here. The reorder day is a trigger #15 wires up, and an agent that silently assembles carts
on a cron before anything can show them to anyone is a worse thing to own than a missing job.

## Nothing to buy is a normal outcome

A household that reports everything is fine produces an empty needs list. That returns an empty
`DeltaOrder` — no cart calls at all — rather than an exception, because "no reorder this cycle" is a
result, not a failure.

## Out of scope

Slot selection, the confirmation UI, persisting the order and updating the baseline are #15.
