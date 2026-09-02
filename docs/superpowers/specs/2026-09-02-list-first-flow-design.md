# Ask, show the list, then order — design

Replaces the automatic profile → plan → cart chain with one a person can steer: describe what you need
(a photo, a receipt, or a sentence), see the shopping list, approve it, and only then does anything get
ordered.

## What went wrong with the old shape

A real session produced eighty-four bananas for three hryvnia. Nothing was broken in the model; the
data we handed it was nonsense:

| Answer typed | What we stored | What the prompt then said |
|---|---|---|
| «все окрім молочки та бананів» | `["все окрім молочки та бананів"]` | «Не їдять: все окрім молочки та бананів» |
| «3к» | `3.00` | «Бюджет: 3 грн» |

The first line reads, literally, "they eat nothing except dairy and bananas" — and dairy was already an
allergy. The model obeyed. We had split a sentence on commas and called it a list, and grabbed the first
digits of "3к" and called it a budget.

Two lessons, and the second is the design:

1. **A free-text answer is not a list.** Splitting on commas cannot represent a negation, an exception
   or a qualifier, and Ukrainian is full of all three.
2. **Nothing should reach a cart without a person seeing it first.** The plan went straight from
   generation to a Silpo cart. A human glance would have stopped this in one second.

## The flow

```
"надішли фото холодильника, фото чека, або просто опиши"
        │
        ├── photo of a fridge   ─┐
        ├── photo of a receipt  ─┼──→ Claude ──→ shopping list ──→ shown for approval
        └── a sentence          ─┘                                      │
                                                    ┌───────────────────┼──────────────┐
                                                «Замовити»         «Змінити»      «Скасувати»
                                                    │                   │
                                            existing cart flow    free-text edit,
                                              (task 9 + 10)       list shown again
```

The approval step is the whole point, so it is also where the weekly plan now lands: meal-plan
generation produces a list and stops there, instead of building a cart on its own.

## One model call, three kinds of input

A fridge photo, a receipt photo and a sentence are the same question — *what should this household
buy?* — so they share a prompt and a result. The image path uses the vision call and the text path uses
structured output; both come back as a list of names, quantities and units.

The profile still matters: allergies and dislikes go into the prompt **as the user's own words**, quoted,
rather than as a parsed list. «Користувач сказав: "все окрім молочки та бананів"» is something a model
reads correctly; our comma-split was not.

## Editing is a sentence, not a keyboard

«Змінити» takes free text — «прибери банани, додай хліб і яйця» — and the model applies it to the list
it just produced. Twenty items would otherwise mean twenty buttons, and the thing a person wants to say
is a sentence anyway.

## Nothing else changes

Approval hands the list to `CartConfirmationService.present`, which is task 10's confirmation, unchanged:
the cart is built, shown with its prices, confirmed, and the baseline is written. This design only puts
a gate in front of it.

## Also fixed here

- «3к», «3 тис», «3000 грн» all parse as three thousand. A budget of three hryvnia is not a budget.
- An enrichment that returns a household size of zero is treated as no answer rather than as zero people.

## Second incident: the balance rule ate a two-item request

Live testing turned up the mirror image of the bananas bug. «12–25 positions, balanced, never one
product» exists to stop a restrictive answer collapsing to one item — and the same rule made
«давай воду та ковбасу» come back with eighteen invented staples nobody asked for.

The prompt could not tell "build me a whole week" apart from "just these two things", so it now names
the two request shapes explicitly and asks the model to classify before writing anything: a **concrete
list** (named products — respond with exactly those, nothing added) versus a **description or photo**
(no named products — build the full balanced week, twelve to twenty-five items). Editing an existing
list is called out as its own case: apply the change to what is there, never regenerate it as though it
were a fresh type-B request.

This is a prompt-only fix. `StubAnthropicServer` returns a canned response regardless of what the prompt
says, so the automated suite cannot verify a real model actually classifies these two cases correctly —
that has to be read from a live conversation, the way this bug was found.
