# Reorder confirmation — plan

Design: [`../specs/2026-09-01-reorder-confirmation-design.md`](../specs/2026-09-01-reorder-confirmation-design.md).
Notion task 15. No schema change.

## Step 1 — offered slots become readable

- `model/OfferedSlot(String id, String label, Instant startsAt)` — `startsAt` null when the server's
  shape defeats parsing.
- `utils/McpResponses` gains `SLOT_START`.
- `CartBuildingService.offeredTimeSlots(userId, context)` returns them all; `validateTimeSlot` is
  rewritten to call it and keep its "first one, or fail" behaviour.

## Step 2 — the flow value

`ConversationFlow.REORDER_CONFIRMATION`, routed in `TelegramRoutingService` next to the other two.

## Step 3 — `service/telegram/ReorderMessageService`

Callback constants (`re:acc:<i>`, `re:rej:<i>`, `re:slot`, `re:slot:<i>`, `re:confirm`, `re:cancel`),
`orderText(DeltaOrder, OfferedSlot chosen, Map<Integer,Boolean> decisions)`,
`orderButtons(DeltaOrder, decisions)`, `slotButtons(List<OfferedSlot>)`,
`confirmedText(CartSummary, BigDecimal savings, boolean slotFixed)` and the reused checkout buttons.

## Step 4 — `service/ReorderConfirmationService`

- `present(User, DeltaOrder)` — pick the slot, write the `DRAFT` order, park the state, send.
- `chooseSlot(userId, List<OfferedSlot>)` — public: most frequent confirmed-order weekday in Kyiv,
  else the earliest offered.
- `handle(User, TelegramIncomingUpdate)` — accept, reject, slot menu, slot pick, confirm, cancel.
- `confirm(...)` — add accepted substitutes, `silpo_update_shopping_cart` for the slot, verify,
  persist `CONFIRMED`, apply the baseline rule, move the trust counter, send the closing message.
- `cancel(...)` — `CANCELLED`, state cleared, baseline untouched.

## Step 5 — `ReorderConfirmationIntegrationTest`

1. the slot matching the household's usual weekday is chosen;
2. with no history, the earliest offered slot is;
3. a zero-edit confirm leaves the baseline alone and increments the trust counter;
4. rejecting a substitute and confirming supersedes the baseline and resets the counter;
5. accepting a substitute adds that product id to the cart and is not an edit;
6. accept and reject are per item — one of each in the same order;
7. cancel marks the order cancelled and touches neither baseline nor counter;
8. a second confirm tap changes nothing;
9. the savings figure from #14 appears in the message.

## Step 6 — close out

`make format`, `./gradlew test`, `./gradlew build`, commit, tick Notion, ff-merge into `main`.
