# Cart confirmation in Telegram + baseline persistence — plan

Design: [`../specs/2026-09-01-cart-confirmation-design.md`](../specs/2026-09-01-cart-confirmation-design.md).
Notion task 10. No schema change: `customer_order` and `baseline_basket` already exist (task 05).

## Step 1 — carry the delivery slot out of the cart build

- `model/CartSummary`: add `String deliverySlot` after `cartId`.
- `CartBuildingService.buildCart`: keep the value `validateTimeSlot` returns and pass it to
  `getVerifiedCart`, which gains a `deliverySlot` parameter.
- `CartBuildingIntegrationTest`: assert the slot lands on the summary.

## Step 2 — `service.telegram.CartMessageService`

Pure formatting, no repositories, no SDK types. Two methods:

- `String cartText(CartSummary)` — itemised lines `— назва — 0.5 кг — 25.50 грн`, the unresolved block
  (`Не знайшов: ...  можете додати вручну пізніше`), Silpo's validations, and `Разом: N грн`.
- `List<TelegramButton> cartButtons(CartSummary)` — confirm, confirm-with-bonus when
  `bonusDecisionPending()`, cancel.
- `String confirmedText(CartSummary, boolean bonusApplied)` and
  `List<TelegramButton> checkoutButtons(CartSummary)` for the closing message.

Callback constants live here next to the labels: `cart:confirm`, `cart:confirm-bonus`, `cart:cancel`.

Tests (`CartMessageServiceTest`, plain unit): every item appears with its quantity; the total appears;
unresolved names are flagged; the bonus button appears only when a decision is pending and names the
amount; the confirmed text carries the web link.

## Step 3 — `service.CartConfirmationService`

State keys in `context_json`: `orderId`, `cartId`.

- `present(User user, List<ShoppingListItem> items)` — build, guard the empty cart, save `DRAFT`
  order (`type = INITIAL`, items, slot, cart id), save conversation state
  `CART_CONFIRMATION` / step `AWAITING_DECISION`, send text + buttons.
- `handle(User user, TelegramIncomingUpdate incoming)` — answer the callback, dispatch on data.
  Text and voice while awaiting a decision get "Скористайся, будь ласка, кнопками вище."
- `confirm(user, order, boolean spendBonuses)` — bonus call (best effort), `CONFIRMED`,
  `confirmedAt`, baseline snapshot, closing message, clear state.
- `cancel(user, order)` — `CANCELLED`, clear state, one line back.
- Guard: order missing, or `status != DRAFT` → acknowledge and return.

Baseline write: `findByUserIdAndIsCurrentTrue` → set false → `saveAndFlush` → insert the new row with
`isCurrent = true` and `confirmedAt = now`.

## Step 4 — wire the triggers

- `MealPlanHandoffService.generateFirstPlan`: after the plan summary, call
  `cartConfirmationService.present(user, list)`; its own failure message already covers the whole block.
- `TelegramRoutingService.handle`: an onboarded user whose conversation flow is `CART_CONFIRMATION`
  goes to `cartConfirmationService.handle`; the existing fallback stays for everything else.

## Step 5 — `CartConfirmationIntegrationTest`

Stub MCP + stub Telegram in one test, updates delivered through `POST /telegram/webhook` so the path
is the real one from task 03.

1. presenting writes a `DRAFT` order and sends a message naming every item and the total;
2. confirm → `CONFIRMED`, one current baseline holding the same items, closing message has the link;
3. confirm-with-bonus → `silpo_update_shopping_cart` called with `bonusRequested`, then confirmed;
4. double confirm → still one `CONFIRMED` order, one baseline, one bonus call, no second message;
5. cancel → `CANCELLED`, no baseline row;
6. confirming a second basket supersedes the first baseline instead of deleting it;
7. a bonus call that errors still confirms the order and says the bonuses were not applied.

## Step 6 — close out

`make format`, `./gradlew test`, `./gradlew build`, commit, tick the Notion criteria, set status,
fast-forward into `main`.
