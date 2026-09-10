# Send-as-gift: an order delivered to a friend's address (task 81)

A household orders a themed package and Silpo delivers it to somebody else's door. The sender pays; the
recipient receives. Nothing about the household's own weekly cycle changes.

## What the live API actually allows

Probed against `https://mcp.silpo.ua/mcp` on 2026-09-10 (`silpo-mcp-service 1.110.0`, 40 tools). The task
page asked for this before anything was promised, so it came first.

| Question | Live answer |
|---|---|
| Is there an address search? | Yes — `silpo_find_address(address)` returns `city / street / houseNumber / district / latitude / longitude`. No apartment. |
| Can a cart be pointed at an address that is not the account holder's? | **Yes.** The live cart was moved Урлівська 4 → Хрещатик 22 (branch `1edb6b38…`), read back, and restored. |
| What does repointing cost? | The branch changes with the address, and every product already in the cart is invalidated — three `product.offer.not_found` validations. |
| Does the address keep contact details? | **Yes.** `phone`, `flat`, `entrance`, `floor` and `courrierComment` were written and read back intact. |
| How many carts does an account have? | One. `silpo_create_shopping_cart` is documented idempotent per user. |
| Can an order be handed to someone else to collect? | **No.** Nothing in the 40 schemas names a recipient. |

Two consequences drive the whole design. **The address must be set before products are resolved**, because
resolution is branch-bound. And **the gift shares the household's only cart**, so whatever the gift does to
the cart has to be undone before the household's next order — but not before the sender has paid.

## Scope

`DeliveryHome` only.

`SelfPickup` builds its address from `silpo_list_branches` data and `NovaPoshta` from
`silpo_find_nova_poshta_offices` data; neither `silpo_create_shopping_cart` nor `silpo_update_shopping_cart`
has a field for who may collect the order, and no tool among the 40 creates an order at all — checkout is a
Silpo web link the sender opens and pays through. "Your friend collects it himself" cannot be expressed
through this API, so it is not built, not promised in any user-facing string, and not in the pitch.

No split payment, for the same reason task 68's group round has none: one account builds the cart, that
account pays.

One thing this design assumes rather than proves: that Silpo's courier dials `address.phone` rather than the
account's profile phone. The field is stored — that much is verified — but only a real paid delivery shows
which number rings. Product owner's call to proceed on that assumption (2026-09-10).

## Data

Three changesets.

**`035` — `users.telegram_username`.** Nullable, indexed, *not* unique: Telegram usernames are transferable,
and a unique constraint would reject a legitimate second owner. Written on every private-chat update, so it
tracks renames. Lookups take the most recent match.

**`036` — three columns on `user_profile`:** `gift_delivery_address` (text), `gift_delivery_phone` (text),
`gift_address_shareable` (boolean `NOT NULL DEFAULT false`). Every existing row gets null/null/false and
nothing backfills them. They change only when the person acts — the onboarding section below, or an explicit
chat request.

**`037` — `gift_order`**, one row per gift, shaped like task 68's `group_event`: a state machine that spans
two chats and therefore cannot live in `conversation_state`, which is keyed by a single chat.

| Column | Holds |
|---|---|
| `sender_user_id` | who is paying |
| `recipient_username` | the `@нік` as typed, lower-cased |
| `recipient_user_id`, `recipient_chat_id` | filled once the recipient is known to the bot |
| `status` | `AWAITING_ADDRESS`, `RESOLVED`, `CART_PRESENTED`, `CONFIRMED`, `EXPIRED`, `UNREACHABLE`, `CANCELLED` |
| `resolution` | `DIRECT`, `CONSENTED`, `ASKED` — which of the three paths produced the address |
| `theme` | what to buy, in the sender's own words |
| `gift_address_text`, `gift_flat`, `gift_phone` | never rendered into anything the sender sees |
| `own_delivery_json` | the household's delivery block, snapshotted before the cart was repointed |
| `silpo_cart_id` | the cart this gift is holding |
| `created_at`, `updated_at`, `expires_at` | `expires_at` is 24 h out for `AWAITING_ADDRESS` |

## The three paths

A new `GIFT_ORDER` intent classifies «відправ подарунок @olena», «замов другу на Хрещатик 22 щось до кави».
A small structured call extracts recipient, address and theme. `GiftOrderService` then resolves the address:

**(a) The sender typed an address.** Used directly. If the sentence carried no phone, the sender is asked for
the friend's number before anything is built — the courier has to reach somebody, and it will not be the
sender, who does not know the address he is sending to. «Не знаю номера» is accepted and proceeds, saying
plainly that the courier will then ring the sender instead.

**(b) `@нік`, and that person has `gift_address_shareable = true`.** Their stored address *and phone* are
used with no exchange at send time — that is what storing both at consent time buys. The recipient is then
told a gift is coming and when: they consented to an address, not to this particular order, and somebody has
to be home for a courier. Product owner's call (2026-09-10) over keeping it a surprise.

**(c) `@нік`, known to the bot, no consent on file.** A `gift_order` opens in `AWAITING_ADDRESS`, and the
recipient is asked in *their own* chat for an address, apartment and phone; their `conversation_state` goes
to `GIFT_ADDRESS_REQUEST`. When they answer, the sender is told the address is in hand — never what it is —
and the cart is built. No answer inside 24 h expires the row and tells the sender so.

**(d) `@нік` the bot has never seen.** There is no chat to ask in, so the sender is told exactly that and
offered path (a): «@нік ще не користувався ботом, тому не можу його спитати — назви адресу сам, якщо знаєш».
Never a silent failure.

Paths (b) and (c) run in two chats and two webhook calls; the `gift_order` row is the only thing joining
them, which is why it exists.

## Building the cart

`GiftOrderService` hands `CartBuildingService` a resolved destination, and a new `repointCart` step runs
before anything else:

1. `silpo_find_address(text)` → first candidate. Nothing found is reported to the sender as a request for a
   more precise address, not as a failure.
2. `silpo_get_available_delivery_types(lat, lon)` → `DeliveryHome` or a loud stop. A friend outside the
   delivery polygon is a real answer, not an error to swallow.
3. `silpo_get_time_slots(branchId, [DeliveryHome])` → the first available window.
4. Snapshot the cart's current `deliveryType` / `timeslot` / `address` / `shipments` into
   `gift_order.own_delivery_json`.
5. `silpo_update_shopping_cart` with the friend's address, `flat` / `entrance` / `floor` where given,
   `phone`, a `courrierComment` naming it a gift, the new `branchId` and the slot.

Only then does the existing pipeline run — clear, resolve, add, present — and because the cart is already
bound to the friend's branch, every product is resolved against the shelf that will actually be picked from.
This ordering is not a preference; step 5 invalidates anything added before it, as the probe showed.

## Giving the household its cart back

The sender pays on a Silpo web link that reads the live cart. Restoring the household's address at
confirmation time would therefore deliver the gift to the sender — the one failure that would be discovered
only after the money moved.

So the restore is lazy. `CartBuildingService.getOrCreateCartContext` is the single door every non-gift build
already goes through; it first asks whether a gift order is still holding the cart, and if so writes
`own_delivery_json` back and closes the row. The household's next order is theirs again, whether or not the
sender ever paid. «Скасувати» under a gift cart restores immediately, because there is nothing to protect.

No scheduler, no timer, no window in which a stale gift address can be used by accident.

## Never showing the sender the address

`CartMessageService` prints `Доставка: <slot>` and no address at all, so non-disclosure is the existing
behaviour rather than something added. The gift variant reads `Доставка: <slot> — на адресу @нік`.

`gift_address_text`, `gift_flat` and `gift_phone` have exactly one consumer: the argument map handed to
`silpo_update_shopping_cart`. A test asserts that no outbound message built by the gift flow contains them.

Path (a) is the exception by construction — the sender typed the address himself.

## Consent

A new optional section closes onboarding, after the budget question:

> 🎁 Подарунки від друзів — за бажанням
> Якщо залишиш адресу й телефон, друзі зможуть замовити тобі подарунок у «Сільпо» просто за твоїм ніком —
> і я привезу його сюди, не питаючи тебе щоразу. Твоєї адреси ніхто з них не побачить.
> [Залишити адресу] [Пропустити]

«Пропустити» leaves all three columns at their defaults, which is also where every pre-existing profile
stays. The same section is reachable later through `/anketa` and through a `GIFT_ADDRESS_CONSENT` chat
intent, and is revocable — «більше не хочу подарунки» clears the address, the phone and the flag.

## Order type

A new `OrderType.GIFT`. A gift is not evidence about how this household eats, so it never becomes a baseline
and never feeds the inventory trend — the same reasoning that already keeps a blackout kit out of both.

## Testing

Unit tests cover the intent extraction, the username lookup, the consent defaults (a fresh profile is
null/null/false), each of the four resolution paths, the redaction guard, and the lazy restore — including
the case where the household's next order arrives before a gift was ever paid for.

`ArchitectureTest` constraints hold unchanged: constructor injection, the `Service` suffix, services reached
only from controllers and jobs.

Live, all three paths end to end: a typed address, a `@нік` with consent, and a `@нік` without it answered
from a second real chat. The cart's address is read back from `silpo_get_shopping_cart_by_id` after each,
and the household's own address is confirmed restored on the next ordinary order.
