# Group event ordering — consensus drinks cart for a Telegram group — design

Task 68 in *Комора — Development Plan*. Depends on 03 (webhook), 09 (`CartBuildingService`), 28/34
(checkout link + delivery configuration), 31 (intent router), 39 (price before approval), 49 (matcher
hardening). All Done or In review.

## Why

The target household — remote IT workers — organises gatherings, and today the bot understands one
household at a time. The task deliberately scopes the group case down to **drinks**: a "buy a bit of
what each person likes" problem, no single-dish compromise, no allergy data pulled into a group chat.
The product framing is *цифрова демократія для закупівлі компанією*: the bot proposes, the group
approves or revises, nothing is put in a cart without visible agreement.

Two platform facts shape everything below:

1. **Telegram gives a bot no member list.** The bot knows only people who interacted with it. Every
   count in this design is a count of explicit replies, never of an assumed roster.
2. **A bot in a group is, by default, in privacy mode:** it receives only commands, messages that
   mention it, and replies to its own messages. The design leans on that instead of fighting it — a
   preference is a *reply to the bot's greeting*, a revision is a *mention or a reply to the proposal*.
   If privacy mode is off (bot made admin), the same rule is enforced in code: an unaddressed message
   is dropped at debug level and never reaches a model.

## Flow

1. **Organizer adds the bot.** Telegram delivers `my_chat_member` (and, on some clients, a
   `new_chat_members` service message); `from` is the person who performed the add — the organizer,
   read from the event, never guessed. A later `/drinks` in the same chat starts a *new* event whose
   organizer is the sender (a group orders more than once, and the bot is added only once).
2. **Greeting** in the group, with one inline button «✅ Всі відповіли» and the rules: reply to this
   message with what you drink, or «.» for "на розсуд бота"; optionally the organizer sets
   `бюджет 2000`, `привід: новий рік`, `дата 31.12`. The greeting's `message_id` is stored.
3. **Replies** arrive at any pace, **no timeout**. Each reply to the greeting (or a mention while the
   event is collecting) upserts a `group_event_participant` row: latest text wins, raw text kept whole.
   The bot acknowledges each with a short reply naming the running count («Записав. Відповіли: 3»).
4. **Only the organizer's tap** on «Всі відповіли» closes the round; anyone else's tap gets a callback
   toast and nothing changes.
5. **Freeze.** At that tap every existing participant row gets `counted_in_denominator = true`,
   `frozen_at` is set, status → `PROPOSED`. A reply to the greeting after this is stored with
   `counted_in_denominator = false`, acknowledged with «цей раунд уже закрито», and never feeds the
   proposal.
6. **ReAct proposal** (below): gather signals algorithmically → Claude synthesises drink lines → resolve
   those lines against the real catalog through the organizer's Silpo session → post the proposal with
   real names, real prices, a total against the budget, a per-head split, and one button
   «👍 Погоджуюсь».
7. **Discussion is free.** Nothing unaddressed is read.
8. **Approvals** are `group_event_approval(event, telegram_user_id, proposal_version)` rows; only
   counted participants' taps count, a tap from anyone else gets a toast. Each counted tap answers
   «N з M погодились».
9. **Consensus** — approvals for the current version equal the frozen denominator — hands the resolved
   lines to `CartConfirmationService.present(organizer, items, AD_HOC)`: the same cart build, minimum-
   order top-up, slot choice, bonus question and checkout button every other flow uses, delivered to
   the **organizer's private chat**. The group gets a confirmation: what went in, the total, the
   informational split, and «організатор оформлює у приваті». Status → `APPROVED`; the lines are
   written to `group_event_item` — the history future events read.
10. **Revision.** A mention or a reply to the proposal while `PROPOSED` («менше пива, більше вина»)
    from anyone bumps `proposal_version`, appends the instruction to the event's revision notes,
    regenerates, and posts a new proposal. All approvals belong to the old version and no longer count —
    the reset the task asks for falls out of the version key, with no deletion.
11. **Ordered.** `CartConfirmationService` publishes `OrderConfirmedEvent` when the organizer confirms;
    a listener marks the newest `APPROVED` event of that organizer `ORDERED` and posts «організатор
    підтвердив замовлення» in the group. Payment itself is Silpo's page, as everywhere.

The checkout link never lands in the group: it is bound to the organizer's account.

## ReAct proposal

### Act: gather signals (plain queries, no model)

Per counted participant, in priority order, each tier only used when the stronger ones are empty *for
that person* — the prompt states the order and the code labels every fact with its tier:

| tier | fact | query |
|---|---|---|
| 1 | explicit reply, this event | `raw_reply_text` of the counted row |
| 2 | what this group took last time | past `group_event` in `APPROVED`/`ORDERED` whose counted participant set has Jaccard ≥ 0.5 with this one (best match wins); its `group_event_item` rows. Group-level, passed once |
| 3 | what this person drinks elsewhere | `preference_summary` (fallback: raw text) of this person's counted rows in other approved/ordered events, newest first, up to 5 |
| 4 | seasonal / size average | items of approved/ordered events whose `coalesce(event_date, created_at)` day-of-year is within ±14 days of this event's, with a participant count within ±50 %; aggregated by product name, quantity per head. Only shown to the model for people with nothing in tiers 1–3, i.e. a «.» with no history |

All four are 1-hop `JOIN`/`GROUP BY` on Postgres. Neo4j stays out — see the task page; nothing here
traverses.

Tier 3 reads a **summary**, not the raw sentence: «сьогодні не п'ю віскі» is an exception for one
event. After each synthesis Claude also returns a short durable preference per participant
(«віскі», «безалкогольне»); it is stored on **this event's** participant row only. Older rows are never
rewritten, so a per-event exception can never edit anyone's long-term pattern.

### Reason: synthesis

`ClaudeApiClient.completeStructured` (flagship model — the judgement is the product) with
`prompts/group-drinks-system.txt`. Input: the event (budget, tag, date, headcount), then one block per
participant with the tiered facts and the raw reply verbatim, then the group-level tier-2 block, the
seasonal block where applicable, and the revision notes in order. Output:

```java
record GroupDrinksProposal(List<GroupDrinkLine> lines, List<ParticipantPreference> participants, String note)
record GroupDrinkLine(String name, BigDecimal quantity, String unit, String forWhom, String reason)
record ParticipantPreference(long telegramUserId, String preferenceSummary)
```

Prompt rules: drinks only (soft drinks and water count; someone who is not drinking gets a
non-alcoholic line, not nothing); plain catalog names, no brands unless history names one; one to
twelve lines; quantity by a stated per-head heuristic (≈0.5 л beer, ⅓ bottle of wine, 100 мл spirits
per person per evening), rounded to packages; the raw reply outranks every history fact; a revision
note outranks the previous proposal.

Java sanity guard after the model: a line over `3 × headcount` units is clamped to that and logged;
`CartBuildingService`'s own per-line guard (₴1500 / 20 pcs / 6 kg) still applies at cart time. The
message says «орієнтовна кількість під компанію, не точний розрахунок».

### Act again: ground in the catalog before anyone approves

The lines go through `CartBuildingService.getOrCreateCartContext` + `resolve` for the organizer —
search, second pass, matcher, stock filter — and the proposal shows what the catalog answered:
«Пиво «Львівське» світле 0.5 л — 10 шт — 389.00 грн». Unresolved names are listed as «Не знайшов: …».
The resolved product ids are stored in the event's `proposal_json`, so consensus adds exactly the
products people approved: `buildCart` treats a line with a UUID product id as pre-resolved and skips
the search (task 22's path).

**Deviation from the task text, step 5.** The task says "reuse #39's price aggregation". That code
prices a list from the household's baseline basket, and a baseline has no drinks in it — for this
flow it would say «—» for every line, which defeats "total against the budget before approval". Real
resolution costs one `silpo_find_products_batch` batch and one fast-model matcher call per proposal,
both already paid for by every other cart; in exchange the group approves real SKUs at real prices,
which is the same search-first discipline tasks 22 and 49 established. If the organizer has no Silpo
session yet, the proposal is posted without prices and says so; consensus is still collected, and the
cart is built once they connect.

## Data model

Four tables, changeset `031-group-event.yaml`. Columns beyond the task's list are marked *added*.

### `group_event`

| column | type | notes |
|---|---|---|
| `id` | UUID PK | |
| `telegram_group_chat_id` | BIGINT NOT NULL | negative for groups |
| `chat_title` | VARCHAR(256) | *added* — for the organizer's private messages |
| `organizer_telegram_user_id` | BIGINT NOT NULL | from the add event / command sender |
| `organizer_user_id` | UUID FK users NULL | *added* — null until the organizer is a Komora user (private chat id equals user id on Telegram) |
| `event_tag` | VARCHAR(256) | |
| `event_date` | DATE | |
| `budget` | NUMERIC(10,2) | |
| `status` | VARCHAR(24) NOT NULL | `COLLECTING_REPLIES`, `PROPOSED`, `APPROVED`, `ORDERED`, `CANCELLED` (*added*: bot removed / superseded) |
| `proposal_version` | INTEGER NOT NULL default 0 | *added* — bumps per revision |
| `proposal_json` | JSONB | *added* — the current resolved proposal |
| `revision_notes` | JSONB | *added* — list of strings, in order |
| `greeting_message_id` | INTEGER | *added* — what a preference reply replies to |
| `proposal_message_id` | INTEGER | *added* — what a revision reply replies to |
| `frozen_at` | TIMESTAMPTZ | *added* |
| `approved_at` | TIMESTAMPTZ | *added* |
| `created_at` | TIMESTAMPTZ NOT NULL | |

Index on `(telegram_group_chat_id, status)`, on `organizer_user_id`.

### `group_event_participant`

| column | type | notes |
|---|---|---|
| `id` | UUID PK | |
| `group_event_id` | UUID FK ON DELETE CASCADE | |
| `telegram_user_id` | BIGINT NOT NULL | |
| `display_name` | VARCHAR(128) | *added* — first name / @username for messages |
| `raw_reply_text` | TEXT NOT NULL | the sentence as typed, «.» included |
| `preference_summary` | VARCHAR(256) | *added* — durable preference the synthesis extracted for this event |
| `replied_at` | TIMESTAMPTZ NOT NULL | last reply |
| `counted_in_denominator` | BOOLEAN NOT NULL default false | set at freeze |

Unique `(group_event_id, telegram_user_id)`.

### `group_event_item`

| column | type | notes |
|---|---|---|
| `id` | UUID PK | |
| `group_event_id` | UUID FK ON DELETE CASCADE | |
| `resolved_silpo_product_id` | VARCHAR(64) | null for a line the catalog lacked |
| `product_name` | VARCHAR(256) NOT NULL | *added* — catalog name, or the requested name when unresolved |
| `requested_name` | VARCHAR(256) | *added* — the plain name the model proposed |
| `quantity` | NUMERIC(10,3) NOT NULL | |
| `unit` | VARCHAR(32) | *added* |
| `unit_price` | NUMERIC(10,2) | *added* — snapshot |

Written once, at consensus. Tiers 2 and 4 read this table.

### `group_event_approval`

| column | type | notes |
|---|---|---|
| `id` | UUID PK | |
| `group_event_id` | UUID FK ON DELETE CASCADE | |
| `telegram_user_id` | BIGINT NOT NULL | |
| `proposal_version` | INTEGER NOT NULL | |
| `approved_at` | TIMESTAMPTZ NOT NULL | |

Unique `(group_event_id, telegram_user_id, proposal_version)`.

## Routing

`TelegramRoutingService` today resolves a `User` from every chat id, which for a group would create a
fake household and start onboarding in the group. Group updates are split off **before** that:

- `toIncoming` recognises `chat.type ∈ {group, supergroup}` on messages and callback queries, and
  `my_chat_member` updates, and produces new `TelegramIncomingUpdate` records: `GroupText`,
  `GroupButtonTap`, `BotAddedToGroup`, `BotRemovedFromGroup`. `GroupText` carries `messageId`,
  `replyToBotMessageId` (when the reply target was sent by this bot — bot id is the numeric prefix of
  the token), `mentionsBot` and `command` (a `bot_command` entity aimed at this bot or at nobody).
- `route` dispatches those to `GroupEventService.handle` and returns; the private-chat path is
  untouched.

The bot's username for mention detection comes from `telegram.bot-username` when set, otherwise one
lazy `getMe` cached for the process; if neither is available, mentions are not recognised and the
flow still works through replies and commands.

`TelegramOutboundService` gains `sendMessageWithButtons` returning the sent `message_id`, and
`sendReply(chatId, replyToMessageId, text)` for the acknowledgements (a reply keeps the thread readable
in a busy chat). Both stay in `service.telegram`.

## Components

| class | package | role |
|---|---|---|
| `GroupEvent`, `GroupEventParticipant`, `GroupEventItem`, `GroupEventApproval` | entity | JPA |
| `GroupEventStatus` | model | enum |
| `GroupDrinksProposal`, `GroupDrinkLine`, `ParticipantPreference` | model | Claude output |
| `GroupProposal`, `GroupProposalLine` | model | what is stored in `proposal_json` and rendered |
| `GroupSignals`, `ParticipantSignals`, `HistoricalLine` | model | the gathered facts |
| `GroupEventRepository` (+ participant, item, approval) | repository | queries for tiers 2–4 |
| `GroupEventService` | service | the flow: add/greet/replies/freeze/approvals/revision/consensus |
| `GroupSignalService` | service | tiers 1–4 as repository queries |
| `GroupProposalService` | service | prompt rendering, synthesis, sanity guard, catalog grounding |
| `GroupEventMessageService` | service.telegram | every string the group reads |
| `GroupEventSettings` | utils | regex parser for `бюджет / привід / дата` |

`GroupEventService` is reached only from `TelegramRoutingService` (service layer) and from an
`@EventListener` on `OrderConfirmedEvent` inside itself. No `job` — nothing is scheduled; the round
waits as long as it takes.

## Messages (Ukrainian, informal «ти» as everywhere)

- Greeting: what to do, the «.» rule, the organizer's optional settings, «✅ Всі відповіли».
- Ack: «Записав, {name}. Відповіли: N.» — as a reply.
- Freeze by a non-organizer: toast «Це кнопка організатора».
- Proposal: «Пропозиція №v на N людей (кількості орієнтовні)» + lines «— {catalog name} — {qty} {unit} — {cost}
  грн» + «Не знайшов: …» + «Разом орієнтовно ~X грн» + budget line («бюджет 2000 — вкладаємось» / «на 300 грн
  більше за бюджет — скажи, що прибрати») + one line of rules «👍 — згоден. Змінити — тегни @bot і напиши, що
  прибрати чи додати (усі 👍 обнуляться)» + «👍 Погоджуюсь». Deliberately short (product review after the live
  run): the per-head split is said once, in the consensus message, where it describes a real cart; the model's
  note goes to the log; the revision hint names no drink, so a non-alcoholic round reads the same.
- Approval ack: toast «N з M погодились».
- Consensus: «✅ Усі M погодились. Поклав у кошик «Сільпо» {organizer}: … Разом ~X грн (~Y з людини).
  {organizer}, кошик і оплата — у нас у приваті.»
- Organizer not connected: «{organizer}, щоб я зібрав кошик, підключи «Сільпо» у приваті зі мною:
  t.me/{bot}» — posted at freeze (proposal without prices) and again at consensus.
- Bot removed: event `CANCELLED`, nothing sent.

No Markdown parse mode: names come from users and could break it.

## Error handling

- Claude failure at synthesis → «Не зміг скласти пропозицію — тегни мене «спробуй ще»» and the event
  stays `PROPOSED` with no proposal; the mention regenerates.
- Cart resolution failure → proposal posted with the model's plain names and no prices, marked so.
- `present` returning false at consensus → group message «Кошик не зібрався: {reason to the organizer
  in private}»; the event stays `APPROVED` so a repeated «👍» is not needed; the organizer's «Список»
  path can retry as usual.
- Every outbound call to the group is inside the same `@Async` route as today; an exception reaches
  `TelegramFailureRecoveryService.recover(chatId)` which posts the generic recovery line — acceptable.

## Testing

Integration tests on the Testcontainers Postgres with the existing Telegram/MCP/Anthropic stubs
(`GroupEventIntegrationTest`), driven through `POST /telegram/webhook` with real Bot API JSON shapes:

1. `my_chat_member` add → greeting in the group, organizer = `from.id`, **no `users` row for the group
   chat**.
2. An unaddressed group message → zero outbound calls, zero Claude calls. A reply to the greeting → one
   participant row; a second reply from the same person updates it; a mention → a row too.
3. Freeze by a non-organizer → toast only. Freeze by the organizer → every row counted; a reply after
   it → stored, `counted_in_denominator = false`, acknowledged as late; the proposal prompt does not
   contain it.
4. The proposal prompt carries each counted person's raw text verbatim, tier labels in order, and the
   seasonal block only for the «.» participant with no history.
5. Context «сьогодні не п'ю віскі» → the prompt has it; after synthesis the *previous* event's
   `preference_summary` «віскі» for that person is unchanged and this event's row carries the new
   summary.
6. Signal tiers against fixtures: a matching past group (Jaccard ≥ 0.5) supplies tier 2; a person's other
   events supply tier 3; a seasonal event of similar size and date supplies tier 4; `GroupSignalService`
   returns each in the right slot and nothing else.
7. Approvals: 3 counted, 2 taps → no cart; a mention with a revision → version 2, a new proposal, the
   count for version 2 is 0 even for the two who tapped; 3 taps on version 2 → `silpo_add_or_update_cart_products`
   called with the resolved ids, the organizer's private chat gets the cart message with «Підтвердити»,
   the group gets the confirmation with the split, items written. A tap from an uncounted user → toast,
   not counted.
8. Organizer without a Silpo token → proposal without prices and the connect hint; consensus posts the
   hint again and builds nothing.
9. `OrderConfirmedEvent` for the organizer → status `ORDERED`, one message in the group.

Unit tests: `GroupEventSettings` parsing; per-head split rounding; the quantity clamp; Jaccard match.

## Live verification

Real group in Telegram Web via the Chrome extension: create a group with the test bot, watch the real
`my_chat_member` arrive through the tunnel, reply as the owner, drive two more participants as
synthetic webhook posts carrying the real group chat id (their replies are synthetic; every bot message
lands in the real group), freeze, revise once, approve three times, and confirm the cart in the
organizer's private chat against the real Silpo MCP. One real account is the honest limit of this
environment; the RUNBOOK says so.

## Out of scope

Food; real payment splitting; timeouts or majority votes; Neo4j; editing the greeting or removing
buttons after consensus; per-person allergies in a group.
