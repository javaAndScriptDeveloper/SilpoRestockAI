# Cart confirmation in Telegram + baseline persistence — design

Notion task **10. Cart confirmation UX in Telegram + baseline persistence**. Closes Stage 1 of the
lifecycle: product brief user flow #1, steps 4–6.

## Problem

Task 09 ends with a `CartSummary`: a verified Silpo cart, its total, its loyalty bonuses and the
names Silpo could not match. Nothing calls it, nothing shows it, and nothing records that a person
agreed to it. Until a confirmed basket is persisted, every later stage (check-ins #11, reorders #13)
has no reference point to compare against.

## Shape

Four pieces, and the boundary between them is "does this know about Telegram copy".

| Piece | Package | Job |
|---|---|---|
| `CartConfirmationService` | `service` | the domain flow: build, persist DRAFT, confirm, cancel |
| `CartMessageService` | `service.telegram` | turns a `CartSummary` into text and buttons |
| `MealPlanHandoffService` | `service` (existing) | continues past "plan is ready" into "here is the cart" |
| `TelegramRoutingService` | `service.telegram` (existing) | routes taps to the confirmation flow |

`CartConfirmationService` never formats a sentence; `CartMessageService` never touches a repository.

## The flow

1. **Present.** After the first weekly plan and its shopping list exist, `CartConfirmationService.present`
   calls `CartBuildingService.buildCart`, writes a `customer_order` row with `status = DRAFT`,
   `type = INITIAL`, the verified items, the Silpo cart id and the delivery slot, then parks the chat in
   `ConversationFlow.CART_CONFIRMATION` with the order id in `context_json` and sends the cart message.
2. **Confirm.** The tap loads the order by the id in the conversation state. Bonuses first (below),
   then `status = CONFIRMED` + `confirmedAt`, then the baseline snapshot, then a closing message
   carrying the checkout links.
3. **Cancel.** `status = CANCELLED`, conversation state cleared, baseline untouched.

## Decisions

### The bonus question is a third button, not a second round trip

The task asks for a "bonus-application question if applicable". Asking it as its own message means a
second conversation step, a second callback, and a second thing to make idempotent. Instead, when
`summary.bonusDecisionPending()` is true the cart message carries three buttons:

```
[Підтвердити]  [Підтвердити + N бонусів]  [Скасувати]
```

The question is answered by *which* confirm was tapped. `cart:confirm` and `cart:confirm-bonus` land
in the same handler with a boolean. Nobody is asked twice, and spending someone's loyalty points is
still an explicit act, never a default.

`cart:confirm-bonus` calls `silpo_update_shopping_cart` with `{cartId, bonusRequested}` before the
order is written. If that call fails the confirmation still proceeds without bonuses and says so —
losing a discount is worth less than losing the order.

### The order row exists before the user answers

`DRAFT` is written at presentation time, not at confirmation time. That is what makes a double tap
cheap to defend against: the confirm handler reads the row, and anything that is not `DRAFT` is a
duplicate to be acknowledged and dropped. The alternative — inserting on confirm — would need a
unique constraint on the cart id to catch the same race, and would leave no record of a cart that was
shown and then ignored.

Telegram delivers duplicate callback queries after a network hiccup, and a person who taps twice
because the first tap felt slow is normal, not exotic.

### `CartSummary` gains the delivery slot

Task 09 picked a slot in `validateTimeSlot` and threw the identifier away — nothing needed it yet.
`customer_order.delivery_slot` needs it, so `buildCart` now carries it into the summary. One field on
a record and one line in the builder, versus a caller re-running step 3 to learn what step 3 already
decided.

### Baseline history is kept

A confirmed basket sets `is_current = true`; any previous current row is flipped to `false` first and
flushed before the insert, because a partial unique index (`ux_baseline_basket_current`) allows one
current row per user and Hibernate would otherwise order the insert before the update.

### Checkout stays with the person

There is no MCP payment tool. The closing message carries `checkoutWebLink` as a link button and the
mobile link in the text, and says plainly that payment happens on Silpo's own page. The agent does
not pretend to have paid.

## What can go wrong, and what happens

| Failure | Behaviour |
|---|---|
| `buildCart` throws (no slot, MCP down) | no order row, one plain sentence to the user, state cleared |
| Nothing resolved — empty cart | no order row; the user is told the list matched no products |
| Bonus call fails | order confirmed without bonuses, message says the bonuses were not applied |
| Second confirm tap | callback answered, nothing written, no second message |
| Confirm after cancel | same guard: the row is `CANCELLED`, not `DRAFT` |
| Tap with no order in state | ignored with a debug log — a stale keyboard from a previous week |

## Out of scope

Recurring check-ins and reorders (#11 onward). This is the first order only: `type = INITIAL`, and
the reorder path will reuse `CartConfirmationService` with a different type later.
