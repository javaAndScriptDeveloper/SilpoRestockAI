# Blackout mode — design

Notion task **19. Blackout special mode**. Stretch. Product brief user flow #5, "гаряче на обід у
блекаут".

## Problem

The power is out. Nothing can be cooked and the fridge is a cupboard. The regular machinery — a weekly
plan, a baseline, a check-in rhythm — is exactly the wrong shape for the next two hours.

## Only the query changes

Everything about building a cart already exists in #9, and everything about confirming one exists in
#10. Blackout mode is therefore not a pipeline; it is a list of search terms plus the two calls that
already do the work:

```
BlackoutModeService  →  CartConfirmationService.present(user, items, AD_HOC)
                             └─ CartBuildingService.buildCart (#9)
```

`BlackoutModeService` owns one thing: what a household can eat with no stove and no fridge.

## A curated list, not an inferred attribute

Silpo's product data does not carry a "needs no cooking" flag, and inferring one per product from a
name is how a demo ends up ordering frozen dumplings during an outage. So the list is written down —
ready meals, tinned fish and meat, pâté, bread, nuts, biscuits, juice, still water — and it lives in one
constant that a person can read and argue with.

It is deliberately short. This is a lunch, not a shop.

## Ad-hoc means the baseline is untouched

The confirmation lifecycle from #10 is reused as-is, with one thing made explicit that was implicit
before: only an `INITIAL` order becomes the baseline. A blackout run is `AD_HOC`, and an emergency
sandwich is not evidence about what this household normally eats. Task 15 already owns the "an edited
reorder becomes the new baseline" rule; nothing here touches it.

That is the whole change to `CartConfirmationService`: `present` takes an order type, and the baseline
write is guarded by it. The two-argument overload stays for the meal-plan hand-off, which is always the
first order.

## Triggered by a command, not by inference

`/blackout` in the chat. The task offers natural-language detection ("світло вимкнули") as a bonus; it
is not built. A false positive here sends someone an unwanted order at the worst possible moment, and
the whole point of the mode is that the person is already having a bad afternoon.

Nothing sets `UserProfile.specialMode` either. That field drives meal planning for a *sustained* period
— a medical diet, a mass-gain phase — and one outage is not a plan.

## Out of scope

Power-outage detection, DTEK schedule integration, and any attempt to keep the mode "on" for a window
of time.
