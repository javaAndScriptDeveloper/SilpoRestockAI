# Guarantee real checkout completion — design

Notion task 28. Depends on 09 (`CartBuildingService`), 10 (`CartConfirmationService`), 15
(`ReorderConfirmationService`).

## Problem

Per the official Silpo MCP docs there is no "place order"/"pay" tool. Every documented workflow ends the
same way: a fully configured cart exposes `checkoutWebLink`/`checkoutMobileLink` on the verified cart
object, and the guest completes payment themselves through that link. Nothing today guarantees these
links actually reach the user working, or that they lead to a completable purchase. A cart that is
correct in our database but never gets bought means the product doesn't close the loop it promises.

## Current state (verified by reading the code, not assumed)

- `CartBuildingService.getVerifiedCart` (`CartBuildingService.java:580-619`) reads both links via
  `McpResponses.findString(...).orElse(null)` with no assertion — a missing link silently becomes `null`
  in the returned `CartSummary`.
- `CartMessageService.checkoutButtons()` (flow #10, `CartMessageService.java:94-98`) builds a button on
  **`checkoutWebLink`** — the web link, not mobile — and returns `List.of()` when it's null.
  `confirmedText()` separately appends **`checkoutMobileLink`** as raw text (`"\nУ застосунку: " + link`).
  This is backwards from what the task asks for (mobile as the primary tappable action, web as fallback
  text) and leaves a dead confirmation with no button at all when the web link happens to be missing but
  the mobile one isn't (or vice versa).
- `ReorderConfirmationService` (flow #15, lines 425-429) has its own **private, independently
  duplicated** `checkoutButtons()` — identical web-link logic. `ReorderMessageService.confirmedText()`
  (lines 106-125) duplicates the same raw-mobile-link-as-text pattern.
- Flow #19 (blackout): `BlackoutModeService.java:46` calls `cartConfirmationService.present(...)` —
  fully reuses flow #10's code path. Fixing #10 fixes #19 with no separate change.
- Flow #24 (ad-hoc order): does not exist yet (no `AdHocOrderService` anywhere, Notion status "Not
  started"). Nothing to audit now.
- Both `CartConfirmationService` (flow #10) and `ReorderConfirmationService` (flow #15) already wrap
  their `buildCart`/build-adjacent calls in a generic `catch (RuntimeException e)` that reports "Кошик
  зібрати не вдалось" — so a new fatal exception from `getVerifiedCart` needs no new catch anywhere; it
  falls into the existing, already-correct failure UX.
- `TelegramButton.link(String label, String url)` already exists and is already used elsewhere (the
  Silpo OAuth connect button) — no new button-model work needed.
- No code anywhere handles in-app vs. system browser. Telegram's Bot API has no server-side way to force
  a URL button to open in the system browser instead of the client's in-app browser — that's a
  client-side Telegram setting, not something a bot can control. This is a genuine platform constraint,
  not a bug to fix. `checkoutMobileLink` deep-links straight into the Silpo app rather than opening any
  browser at all, which is exactly what routes around the in-app-browser session concern the task raises
  — making it the primary button *is* the fix for that concern, not a separate piece of work.

## Design

### 1. `CartBuildingService.getVerifiedCart` — assert, don't silently pass through

After reading `checkoutWebLink`/`checkoutMobileLink`, if either is `null` or blank, throw
`CartBuildException` (the same exception this method and its neighbors already throw for every other
"something is structurally wrong with this cart" case — no new exception type). Log the raw cart
response first, matching the existing convention elsewhere in this class ("logging the raw answer is
what turns this from a dead end into a one-line fix").

```java
String checkoutWebLink = McpResponses.findString(cart, McpResponses.CHECKOUT_WEB).orElse(null);
String checkoutMobileLink = McpResponses.findString(cart, McpResponses.CHECKOUT_MOBILE).orElse(null);
if (isBlank(checkoutWebLink) || isBlank(checkoutMobileLink)) {
    log.error("verified cart {} has no usable checkout link. Raw response: {}", context.cartId(), cart);
    throw new CartBuildException("Silpo gave no checkout link for cart " + context.cartId());
}
```

This makes "`CartSummary` exists" and "both checkout links are present" the same guarantee by
construction — no consuming flow can ever reach a confirmation message with a missing link, because
`buildCart` never returns one.

### 2. `CartMessageService` — single source of truth for checkout presentation

- `checkoutButtons(CartSummary)`: button targets `checkoutMobileLink` (primary, per the task — better
  mobile UX inside Telegram and it sidesteps the in-app-browser session concern). Label: **"Перейти до
  оплати"**. The null-check branch is removed — after fix (1), it's unreachable; a summary that reached
  this method always has both links.
- `confirmedText(...)`: keeps the "Оплата — на боці «Сільпо»." line, replaces the raw-mobile-link text
  with a fallback mention of `checkoutWebLink` instead: `"\nАбо в браузері: " + summary.checkoutWebLink()`.
  Mobile is now the button; web is the one place it still needs to appear, as the fallback.

### 3. `ReorderConfirmationService` / `ReorderMessageService` — delegate, don't duplicate

`CartMessageService` is injected into `ReorderConfirmationService` (constructor injection, matching the
`@RequiredArgsConstructor` convention already in use). The private `checkoutButtons` method is deleted;
its one call site (line 267) becomes `cartMessageService.checkoutButtons(cart)`. `ReorderMessageService`
gets the same `CartMessageService` dependency and its own raw-mobile-link line is replaced with a call
to whatever web-link-fallback text `CartMessageService` exposes (a small public method, e.g.
`checkoutFallbackLine(CartSummary)`, extracted from `confirmedText` so both message services can compose
it into their own otherwise-different confirmation text without duplicating the fallback line's wording).

This is the same "reuse the existing flow's component" pattern flow #19 already uses for the whole
confirmation step — here it's just the checkout-presentation slice of it, not the entire flow.

### 4. Flows #19 and #24

No code change for #19 — it already reuses flow #10 end to end. For #24 (not yet built): this spec
records, as a constraint on that future task, that it must call `CartMessageService` for checkout
presentation rather than reimplementing it a third time.

### 5. Manual verification (acceptance criteria 3-5, out of this agent's reach)

A real purchase, with a real Silpo guest account, end to end, screen-recorded, referenced in the
"Сценарій демо-запису" Notion doc. This cannot be performed by the coding agent — it needs a human with
a real account and phone/browser. The implementation plan produces exact manual steps for this; running
them and making the recording is a separate, human step after the code lands.

## Testing

- `CartBuildingIntegrationTest`: new test — a verified cart whose `silpo_get_shopping_cart_by_id`
  response omits `checkoutWebLink`/`checkoutMobileLink` makes `buildCart` throw `CartBuildException`,
  and `silpo_add_or_update_cart_products` is still called first (the assertion is about the *verified*
  read, not about skipping the add step) — matches the existing "step 6 verifies, doesn't gate step 5"
  ordering in the docstring on `buildCart`.
- `CartMessageService`: unit tests for `checkoutButtons` (button targets mobile link, correct label) and
  `confirmedText`/the new fallback-line method (mentions the web link, not the mobile one).
- `ReorderConfirmationService` / `ReorderMessageService`: existing tests updated for the same
  mobile-button/web-fallback shape; the private duplicate method's removal is covered by the fact that
  the delegated call produces identical output to `CartMessageService`'s own tests.
- Full regression run: existing `COOKS_DAILY`/reorder/blackout integration tests must stay green
  unmodified in behavior — this task changes presentation and a failure mode, not cart-building logic
  itself (explicitly out of scope per the Notion task).

## Out of scope

No custom payment/checkout UI — Silpo's own checkout handles payment, the whole point is reliably
getting the guest there with the right cart already configured. No changes to cart-building logic (#9)
beyond the link-presence assertion above. No build of flow #24 (ad-hoc order) — task 24 remains
unstarted; this spec only constrains what it must do when it is built.
