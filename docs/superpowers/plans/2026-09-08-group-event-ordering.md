# Group event ordering — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Telegram group can ask the bot for a drinks cart: replies are collected without a timeout,
the organizer freezes the set, the bot proposes from algorithmically gathered signals plus Claude,
grounded in the real catalog, the frozen set approves (a revision resets every approval), and consensus
hands the resolved lines to the organizer's ordinary cart confirmation.

**Architecture:** Group updates are split off in `TelegramRoutingService` before any `User` lookup and
handed to a new `GroupEventService`. Four Postgres tables hold the event, its repliers, its history
lines and its per-version approvals. `GroupSignalService` runs the four 1-hop queries,
`GroupProposalService` renders them into one `completeStructured` call and grounds the answer through
`CartBuildingService.resolve`; consensus calls `CartConfirmationService.present` for the organizer.

**Tech Stack:** Java 25 / Spring Boot 4, JPA + Liquibase (`ddl-auto: validate`), Lombok, telegrambots
9.0.0 (SDK types only in `controller.telegram` / `service.telegram`), Anthropic SDK behind
`ClaudeApiClient`, JUnit 5 + AssertJ, Testcontainers PostgreSQL, the repo's stub Telegram/MCP/Anthropic
servers, Spotless (palantir), ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-08-group-event-ordering-design.md`

> This plan is executed inline by the session that wrote it; code blocks show the interfaces and the
> non-obvious parts, and the test blocks name every assertion. Full bodies live in the commits.

## Global Constraints

- Liquibase owns the schema: changeset `031-group-event.yaml` under
  `src/main/resources/db/changelog/changes/`; without it every `@SpringBootTest` fails on validate.
- Constructor injection only; `Service` / `Repository` / `Controller` suffixes; `service` reachable only
  from `controller` and `job`; `@Scheduled` only in `job` (none here).
- Telegram SDK types stay in `controller.telegram` and `service.telegram` (ArchUnit
  `telegramSdkStaysBehindTheTelegramPackages`); `model` records depend on no framework.
- `@Slf4j`; Ukrainian informal «ти» in every user-facing string; no Markdown parse mode in group messages.
- Never create a `users` row for a group chat id.
- Explicit participation only: every denominator is a count of stored reply rows.
- `make format` before every commit; stop `make run` before `./gradlew test`.
- Money: honest arithmetic only — the per-head line always says it is a split, never a payment.

---

### Task 1: Schema, entities, repositories

**Files:**
- Create: `src/main/resources/db/changelog/changes/031-group-event.yaml`
- Create: `model/GroupEventStatus.java`
- Create: `entity/GroupEvent.java`, `entity/GroupEventParticipant.java`, `entity/GroupEventItem.java`,
  `entity/GroupEventApproval.java`
- Create: `repository/GroupEventRepository.java`, `GroupEventParticipantRepository.java`,
  `GroupEventItemRepository.java`, `GroupEventApprovalRepository.java`
- Test: `src/test/java/com/silporestockai/integration/GroupEventSchemaIntegrationTest.java`

**Interfaces (produces):**

```java
enum GroupEventStatus { COLLECTING_REPLIES, PROPOSED, APPROVED, ORDERED, CANCELLED }

@Entity @Table(name = "group_event") class GroupEvent {
  UUID id; Long telegramGroupChatId; String chatTitle; Long organizerTelegramUserId; UUID organizerUserId;
  String eventTag; LocalDate eventDate; BigDecimal budget; GroupEventStatus status; int proposalVersion;
  @JdbcTypeCode(SqlTypes.JSON) Map<String,Object> proposalJson;   // null until proposed
  @JdbcTypeCode(SqlTypes.JSON) List<String> revisionNotes;        // never null, default empty
  Integer greetingMessageId; Integer proposalMessageId; Instant frozenAt; Instant approvedAt; Instant createdAt;
}
@Entity @Table(name = "group_event_participant") class GroupEventParticipant {
  UUID id; UUID groupEventId; Long telegramUserId; String displayName; String rawReplyText;
  String preferenceSummary; Instant repliedAt; boolean countedInDenominator;
}
@Entity @Table(name = "group_event_item") class GroupEventItem {
  UUID id; UUID groupEventId; String resolvedSilpoProductId; String productName; String requestedName;
  BigDecimal quantity; String unit; BigDecimal unitPrice;
}
@Entity @Table(name = "group_event_approval") class GroupEventApproval {
  UUID id; UUID groupEventId; Long telegramUserId; int proposalVersion; Instant approvedAt;
}

interface GroupEventRepository extends JpaRepository<GroupEvent, UUID> {
  Optional<GroupEvent> findFirstByTelegramGroupChatIdAndStatusInOrderByCreatedAtDesc(long chatId, Collection<GroupEventStatus> statuses);
  List<GroupEvent> findByStatusIn(Collection<GroupEventStatus> statuses);
  Optional<GroupEvent> findFirstByOrganizerUserIdAndStatusOrderByApprovedAtDesc(UUID organizerUserId, GroupEventStatus status);
}
interface GroupEventParticipantRepository extends JpaRepository<GroupEventParticipant, UUID> {
  List<GroupEventParticipant> findByGroupEventId(UUID eventId);
  Optional<GroupEventParticipant> findByGroupEventIdAndTelegramUserId(UUID eventId, long telegramUserId);
  List<GroupEventParticipant> findByTelegramUserIdAndCountedInDenominatorTrueAndGroupEventIdNot(long telegramUserId, UUID eventId);
  long countByGroupEventIdAndCountedInDenominatorTrue(UUID eventId);
}
interface GroupEventItemRepository extends JpaRepository<GroupEventItem, UUID> { List<GroupEventItem> findByGroupEventId(UUID eventId); List<GroupEventItem> findByGroupEventIdIn(Collection<UUID> ids); }
interface GroupEventApprovalRepository extends JpaRepository<GroupEventApproval, UUID> {
  long countByGroupEventIdAndProposalVersion(UUID eventId, int version);
  boolean existsByGroupEventIdAndTelegramUserIdAndProposalVersion(UUID eventId, long telegramUserId, int version);
  List<GroupEventApproval> findByGroupEventIdAndProposalVersion(UUID eventId, int version);
}
```

- [ ] **Step 1: Write the failing test** — `GroupEventSchemaIntegrationTest extends AbstractIntegrationTest`:
  save one event with two participants, one item, one approval; read them back; assert the participant
  unique key rejects a duplicate `(event, user)` (`DataIntegrityViolationException`); assert
  `revisionNotes` round-trips as a list of strings; assert deleting the event cascades to all three
  child tables.
- [ ] **Step 2: Run** `./gradlew test --tests '*GroupEventSchemaIntegrationTest*'` → fails (no entity / validate error).
- [ ] **Step 3: Write the changeset** exactly as the spec's tables (types, defaults, FKs `ON DELETE CASCADE`,
  unique `ux_group_event_participant_user (group_event_id, telegram_user_id)`,
  `ux_group_event_approval_vote (group_event_id, telegram_user_id, proposal_version)`, indexes
  `ix_group_event_chat_status (telegram_group_chat_id, status)`, `ix_group_event_organizer`), the
  entities and repositories above.
- [ ] **Step 4: Run** the test → PASS. Run `*ArchitectureTest*` → PASS.
- [ ] **Step 5: Commit** `feat: add group event tables and entities`.

---

### Task 2: Group updates reach a group handler, never a household

**Files:**
- Modify: `model/TelegramIncomingUpdate.java` — add records
- Modify: `config/TelegramProperties.java` — `botUsername` (optional), `botId()` derived from the token
- Modify: `application.yml` `telegram.bot-username: ${TELEGRAM_BOT_USERNAME:}`; `.env.example`
- Modify: `service/telegram/TelegramOutboundService.java` — `botUsername()` lazy `GetMe`,
  `int sendMessageWithButtons(...)` returning `message_id`, `int sendReply(long chatId, int replyTo, String text)`
- Modify: `service/telegram/TelegramRoutingService.java` — group split before `handle`
- Modify: `src/test/java/com/silporestockai/support/StubTelegramServer.java` — incrementing `message_id`,
  `getme` answers `{id, is_bot, username}`
- Create: `service/GroupEventService.java` (skeleton: `handle(TelegramIncomingUpdate)` logs only)
- Test: `integration/GroupEventRoutingIntegrationTest.java`

**Interfaces (produces):**

```java
// model/TelegramIncomingUpdate
record GroupText(long chatId, long telegramUserId, String displayName, int messageId, String text,
                 Integer replyToBotMessageId, boolean mentionsBot, String command) implements TelegramIncomingUpdate {
  boolean addressedToBot() { return replyToBotMessageId != null || mentionsBot || command != null; }
  /** text with the @bot mention and a /command@bot prefix removed, trimmed */
  String bodyWithoutAddress(String botUsername);
}
record GroupButtonTap(long chatId, long telegramUserId, String displayName, String callbackQueryId, String data, int messageId) implements TelegramIncomingUpdate {}
record BotAddedToGroup(long chatId, String chatTitle, long byTelegramUserId, String byDisplayName) implements TelegramIncomingUpdate {}
record BotRemovedFromGroup(long chatId) implements TelegramIncomingUpdate {}

// TelegramProperties
public long botId()                 // Long.parseLong(botToken.substring(0, botToken.indexOf(':'))), 0 when blank
public boolean botUsernameConfigured()

// TelegramOutboundService
public Optional<String> botUsername()   // property first, else one cached GetMe, empty on failure
public int sendMessageWithButtons(long chatId, String text, List<TelegramButton> buttons) // now returns message_id
public int sendReply(long chatId, int replyToMessageId, String text)
```

`displayName` = `@username` when present, else first name (+ last name), else `"учасник"`.

Routing rule in `toIncoming`: `chat.getType()` is `"group"` or `"supergroup"` → group record;
`update.hasMyChatMember()` → `BotAddedToGroup` when `newChatMember.status ∈ {member, administrator}` and
`getUser().getId() == botId`, `BotRemovedFromGroup` when `status ∈ {left, kicked}`; a `new_chat_members`
service message listing the bot → `BotAddedToGroup` too (`GroupEventService` is idempotent about it).
`route` then: `if (incoming is a group record) { groupEventService.handle(incoming); return; }` before
`handle(incoming)`.

- [ ] **Step 1: Write the failing tests** (`GroupEventRoutingIntegrationTest`, webhook JSON through
  MockMvc, `@MockitoSpyBean GroupEventService`):
  1. `my_chat_member` add (chat `-100777`, `from.id 41`, new status `member`, user id = bot id `2020`)
     → `handle` received `BotAddedToGroup(-100777, "Пʼятниця", 41, "@olena")`; `userRepository.findByTelegramChatId(-100777)` empty.
  2. A group text with no entities and no reply → `GroupText.addressedToBot()` false; still delivered
     to the group handler (the handler decides), and **no `users` row**.
  3. A reply to message 7 whose `reply_to_message.from.id == 2020` → `replyToBotMessageId == 7`.
  4. `"@komora_test_bot пиво"` with a `mention` entity → `mentionsBot == true`,
     `bodyWithoutAddress("komora_test_bot") == "пиво"`.
  5. `"/drinks@komora_test_bot"` with a `bot_command` entity → `command == "/drinks"`.
  6. A callback query on a group message → `GroupButtonTap`.
  7. A private text still goes down the old path (the onboarding greeting is sent).
- [ ] **Step 2: Run** → fails to compile.
- [ ] **Step 3: Implement** records, properties, outbound additions, stub changes, routing split, skeleton service.
- [ ] **Step 4: Run** the new test + `*TelegramRouting*`/`*Onboarding*`/`*IntentRouter*` tests + ArchUnit → PASS.
- [ ] **Step 5: Commit** `feat: route group chat updates apart from households`.

---

### Task 3: Greeting, replies, freeze — the collecting round

**Files:**
- Create: `utils/GroupEventSettings.java` (`record Parsed(BigDecimal budget, String eventTag, LocalDate eventDate)`, `static Optional<Parsed> parse(String text, LocalDate today)`)
- Create: `service/telegram/GroupEventMessageService.java`
- Modify: `service/GroupEventService.java`
- Test: `unit/GroupEventSettingsTest.java`, `integration/GroupEventIntegrationTest.java` (collecting part)

**Interfaces (produces):**

```java
// GroupEventMessageService — constants + text
public static final String CALLBACK_FREEZE_PREFIX = "grp:freeze:";   // + event id
public static final String CALLBACK_APPROVE_PREFIX = "grp:ok:";      // + event id + ":" + version
String greeting(String organizerName, String botUsername);
String replyAck(String name, long count);            // «Записав, {name}. Відповіли: N.»
String lateReplyAck(String name);                    // «Записав, {name}, але цей раунд уже закрито…»
String settingsAck(GroupEvent e);                    // «Бюджет: 2000 грн. Привід: …»
String freezeNotOrganizer();                         // toast
String nobodyReplied();                              // «Поки ніхто не відповів…»
String connectHint(String organizerName, String botUsername);

// GroupEventService
public void handle(TelegramIncomingUpdate incoming);  // dispatches on record type + event status
```

Behaviour:
- `BotAddedToGroup` / `/drinks` command: cancel any active event in this chat (`CANCELLED`), create a
  `COLLECTING_REPLIES` event (organizer = `byTelegramUserId` / sender; `organizerUserId` =
  `userRepository.findByTelegramChatId(organizerTelegramUserId)` id or null), send the greeting with
  the freeze button, store `greetingMessageId`.
- `GroupText` while `COLLECTING_REPLIES`: ignore unless `addressedToBot()`. If sender is the organizer
  and `GroupEventSettings.parse` finds something → set fields, `settingsAck`, return. Otherwise upsert
  the participant (`rawReplyText = bodyWithoutAddress`, blank → «.»), reply `replyAck(count)`.
- `GroupButtonTap` `grp:freeze:<id>`: `answerCallback`; not organizer → toast; zero participants →
  `nobodyReplied`; else mark every row counted, `frozenAt`, status `PROPOSED`, then call
  `proposalService.propose(event)` (Task 5; in this task a stub that sends «Рахую пропозицію…»).
- `GroupText` reply to the greeting while not collecting → upsert with `countedInDenominator=false`,
  `lateReplyAck`.
- `BotRemovedFromGroup` → active event `CANCELLED`.

- [ ] **Step 1: Unit test** `GroupEventSettingsTest`: «бюджет 2000» → 2000; «бюджет 1 500 грн, привід: новий
  рік, дата 31.12» → all three, year = this year or next if the date already passed; «пиво світле» → empty.
- [ ] **Step 2: Integration test** (collecting): add → greeting text contains «Всі відповіли» button and
  «.»; three replies (41 «вино червоне», 42 «пиво світле, це на ДР», 43 «.») → three rows, acks «Відповіли: 1/2/3»;
  42 replies again «темне пиво» → still three rows, text updated; an unaddressed «хто бере торт?» → no
  new outbound; freeze tap by 42 → toast, still `COLLECTING_REPLIES`; freeze tap by 41 → all three counted,
  `frozenAt` set, status `PROPOSED`; reply from 44 after → row with `counted=false`, late ack; organizer
  «@bot бюджет 1500» before freeze → `budget == 1500`, ack.
- [ ] **Step 3: Run** → fail. **Step 4: Implement.** **Step 5: Run** → PASS. **Step 6: Commit**
  `feat: collect drink preferences in a group and freeze on the organizer's tap`.

---

### Task 4: Signals — the four tiers as queries

**Files:**
- Create: `model/HistoricalLine.java` (`record HistoricalLine(String productName, BigDecimal quantity, String unit, BigDecimal perHead)`)
- Create: `model/ParticipantSignals.java` (`record ParticipantSignals(long telegramUserId, String displayName, String rawReply, List<String> personalHistory, boolean needsSeasonal)`)
- Create: `model/GroupSignals.java` (`record GroupSignals(List<ParticipantSignals> participants, List<HistoricalLine> sameGroupLastTime, String sameGroupLastTimeTag, List<HistoricalLine> seasonal)`)
- Create: `service/GroupSignalService.java` — `GroupSignals gather(GroupEvent event, List<GroupEventParticipant> counted)`
- Test: `integration/GroupSignalIntegrationTest.java`

Rules (spec §ReAct): tier 2 = best Jaccard ≥ 0.5 among other `APPROVED|ORDERED` events' counted sets
(`static double jaccard(Set<Long>, Set<Long>)` public for the unit test); tier 3 = up to 5 newest
`preferenceSummary` (fallback raw text) of this person's counted rows in other approved/ordered events;
tier 4 = items of approved/ordered events within ±14 days of day-of-year of `coalesce(eventDate,
createdAt.toLocalDate())` and headcount within ±50 %, grouped by product name, `perHead = sum(quantity)
/ sum(headcount)`, top 8; `needsSeasonal = rawReply is «.» && personalHistory.isEmpty() &&
sameGroupLastTime.isEmpty()`. `seasonal` is empty when no participant needs it.

- [ ] **Step 1: Test** fixtures: event A (chat X, 41/42/43, ORDERED, items beer 10 / wine 2, date 2025-12-30),
  event B (chat Y, 41/55, APPROVED, 41's summary «віскі»), event C (chat Z, 61/62/63, ORDERED, 2025-12-28,
  items «Шампанське» 3). Current event: chat X, 41/42/43/44 counted, date 2025-12-31, 41 «вино», 42 «.», 43 «.», 44 «.».
  Assert: `sameGroupLastTime` = A's items (Jaccard 3/4); 41's `personalHistory == ["віскі"]`; 42 and 43
  have history through A? — no: tier 3 reads *summaries of the person*, A has none stored for them, so
  empty; 44 `needsSeasonal`, `seasonal` contains «Шампанське» with perHead 1 and A's lines; event B is not
  tier 2 (Jaccard 1/5). `jaccard({1,2,3},{1,2,4}) == 0.5`.
- [ ] **Step 2–5:** run/fail, implement, run/pass, commit `feat: gather group drink signals in priority order`.

---

### Task 5: Proposal — synthesis, sanity guard, catalog grounding, the message

**Files:**
- Create: `src/main/resources/prompts/group-drinks-system.txt`
- Create: `model/GroupDrinksProposal.java`, `model/GroupDrinkLine.java`, `model/ParticipantPreference.java`
- Create: `model/GroupProposal.java` (`record GroupProposal(int version, List<GroupProposalLine> lines, List<String> unresolved, BigDecimal estimatedTotal, boolean priced, String note)`),
  `model/GroupProposalLine.java` (`record GroupProposalLine(String requestedName, String silpoProductId, String catalogName, BigDecimal quantity, String unit, BigDecimal unitPrice, String forWhom)` with `BigDecimal lineCost()`)
- Create: `service/GroupProposalService.java`
- Modify: `service/telegram/GroupEventMessageService.java` — `proposal(GroupEvent, GroupProposal, int headcount, String botUsername)`, `proposalFailed()`
- Modify: `service/GroupEventService.java` — call `propose` at freeze and on revision
- Test: `integration/GroupEventIntegrationTest.java` (proposal part), `unit/GroupProposalServiceTest.java` (clamp + split)

**Interfaces:**

```java
public class GroupProposalService {
  /** gather → render prompt → completeStructured → clamp → resolve via organizer → GroupProposal */
  public GroupProposal propose(GroupEvent event, List<GroupEventParticipant> counted, Optional<User> organizer);
  static BigDecimal clampQuantity(BigDecimal quantity, int headcount);   // min(q, 3*headcount), q ≥ 1
  static BigDecimal perHead(BigDecimal total, int headcount);           // HALF_UP, 2 dp
  String renderPrompt(GroupEvent e, GroupSignals s, List<String> revisionNotes); // package-private for tests
}
```

Prompt user-message layout (labels are what the tests assert on):

```
Подія: {tag or «без приводу»}, дата {date or «не вказана»}, бюджет {n грн or «не вказано»}, людей: {N}.
Правки від компанії (кожна наступна важливіша за попередню): 1) … 2) …
[Тир 2] Ця ж компанія минулого разу брала: — Пиво … — 10 шт …
Учасники:
• {name} (id {id})
  [Тир 1] Відповідь зараз: «{raw}»
  [Тир 3] В інших компаніях пив: віскі; вино
  [Тир 4] (тільки коли тирів 1–3 немає) Сезонний середній для компаній такого розміру: …
```

Grounding: `ShoppingListItem` per line (`userId = organizer`, name, quantity, unit, category «Напої»),
`cartBuildingService.getOrCreateCartContext(organizerId)` + `resolve(organizerId, ctx, items, false)`;
resolved → `GroupProposalLine` with product id, catalog name, unit price; unresolved names listed;
`priced = organizer present && resolution succeeded`; any `RuntimeException` from resolution → unpriced
proposal with the model's names, logged at WARN. Store `proposalJson` (Jackson map of `GroupProposal`),
bump nothing here (the caller sets the version), post the message with the «👍 Погоджуюсь» button,
store `proposalMessageId`, write each `ParticipantPreference` summary onto **this event's** participant rows.

- [ ] **Step 1: Unit tests**: `clampQuantity(40, 3) == 9`, `clampQuantity(0.5, 3) == 1`, `perHead(1000, 3) == 333.33`.
- [ ] **Step 2: Integration test** (continuing Task 3's scenario, stubs scripted: Anthropic answers the
  proposal JSON `{"lines":[{"name":"Вино червоне сухе","quantity":2,"unit":"шт","forWhom":"Олена","reason":"…"},{"name":"Пиво світле","quantity":6,"unit":"шт","forWhom":"Ігор, Марко","reason":"…"}],"participants":[{"telegramUserId":41,"preferenceSummary":"червоне вино"},{"telegramUserId":42,"preferenceSummary":"світле пиво"},{"telegramUserId":43,"preferenceSummary":"без вподобань"}],"note":"…"}`
  then the matcher JSON; MCP answers the cart context and a `find_products_batch` with two candidates):
  after the organizer's freeze —
  - the Anthropic request contains «Відповідь зараз: «пиво світле, це на ДР»» verbatim and the tier labels
    in order «[Тир 1]» before «[Тир 3]»;
  - `find_products_batch` was called with the two names;
  - the group message has «Пропозиція №1 на 3 людей», both catalog names with «грн», «Разом орієнтовно»,
    «бюджет 1500», «з людини», «просто арифметика», a «👍 Погоджуюсь» button;
  - `proposalJson.lines[0].silpoProductId` is the stub's id; participant 41's `preferenceSummary == "червоне вино"`.
  - Second scenario: organizer has no token → no MCP call, message contains «підключи «Сільпо»» and no «грн».
  - Third: a prior event where 41's summary is «віскі»; now 41 replies «сьогодні не п'ю віскі» → prompt has
    both «[Тир 3] … віскі» and the raw sentence; after synthesis the prior row still reads «віскі».
- [ ] **Step 3–5:** fail, implement (prompt file, services, message), pass, commit
  `feat: propose a group drinks cart from gathered signals, grounded in the catalog`.

---

### Task 6: Approvals, revision, consensus, ordered

**Files:**
- Modify: `service/GroupEventService.java`
- Modify: `service/telegram/GroupEventMessageService.java` — `approvalToast(long n, long m)`,
  `notCounted()`, `consensus(GroupEvent, GroupProposal, CartSummary-free facts, String organizerName)`,
  `cartFailed(String organizerName)`, `ordered(String organizerName)`
- Modify: `service/CartConfirmationService.java` — none (uses `present(user, items, OrderType.AD_HOC)`)
- Test: `integration/GroupEventIntegrationTest.java` (approval part)

Behaviour:
- `GroupButtonTap` `grp:ok:<id>:<v>` while `PROPOSED`: `answerCallback`; version mismatch → toast «Це стара
  пропозиція»; tapper not a counted participant → `notCounted` toast; `existsBy…` → toast with the current
  count; else insert, count → toast `approvalToast(n, m)`; `n == m` → `consensus(event)`.
- `GroupText` addressed while `PROPOSED` and not a reply to the greeting → revision: append to
  `revisionNotes`, `proposalVersion++`, `propose(...)` again, post; old approvals stay but no longer match.
- `consensus`: status `APPROVED`, `approvedAt`, write `group_event_item` from the proposal lines; organizer
  `User` present with a Silpo token → build `ShoppingListItem`s with `silpoProductId` from the proposal
  and `cartConfirmationService.present(organizer, items, OrderType.AD_HOC)`; `true` → group message
  `consensus(...)` with lines, total, per-head split; `false` → `cartFailed`. No organizer/token →
  `connectHint`.
- `@EventListener(OrderConfirmedEvent)`: `findFirstByOrganizerUserIdAndStatusOrderByApprovedAtDesc(userId, APPROVED)`
  → `ORDERED`, group message `ordered`.

- [ ] **Step 1: Integration test** (continuing): taps by 41 and 42 → toasts «1 з 3», «2 з 3», no
  `add_or_update_cart_products`; tap by 44 (uncounted) → `notCounted`, count still 2; mention «@bot менше
  пива, більше вина» → Anthropic called again with «Правки … 1) менше пива, більше вина», new message
  «Пропозиція №2», `countByGroupEventIdAndProposalVersion(id, 2) == 0`; 41's tap on the v1 button → «стара
  пропозиція»; taps by 41, 42, 43 on v2 → `silpo_add_or_update_cart_products` called once with the
  resolved ids, the organizer's private chat (`41`) received a message with a «Підтвердити» button, the
  group received «Усі 3 погодились» with «з людини», `group_event_item` has the lines, status `APPROVED`;
  publish `OrderConfirmedEvent(organizerUserId, …)` → `ORDERED`, group message with «підтвердив».
- [ ] **Step 2–5:** fail, implement, pass, commit `feat: approve, revise and hand a group cart to the organizer`.

---

### Task 7: Help text, docs, RUNBOOK, session notes

**Files:**
- Modify: `service/IntentRouterService.java` HELP_TEXT — one line: «Додай мене в груповий чат — зберу напої на компанію за спільною згодою».
- Modify: `docs/RUNBOOK.md` — `### Task 68: drive a group drinks round` (real group + synthetic participants recipe, what to read in the log).
- Modify: `docs/OVERNIGHT_SUMMARY.md` — `# Session 15`; `docs/OVERNIGHT_QUESTIONS.md` — deviations (price via catalog not baseline; `/drinks` organizer; extra columns; private-chat checkout).
- Modify: `docs/PRODUCT_BRIEF.md` — flow 11 «Групове замовлення напоїв» (mirror of the Notion task).
- Modify: `CLAUDE.md` — one invariant line: group chats never create a `users` row; group updates bypass the household router.
- Run: `make format`, full `./gradlew test`.
- Commit `docs: document the group drinks round`.

---

### Task 8: Live verification and Notion

- Real group through Telegram Web (spec §Live verification), two synthetic participants via webhook,
  one revision, full consensus, real cart in the organizer's private chat; noise message → silence.
- Task 68 → In review (or Done) with a dated note; demo script step 13.6 + selling-points section if warranted.
- Merge to `main`, commit.

## Self-review

- Spec coverage: routing (T2), collecting/freeze/late replies (T3), tiers (T4), synthesis + grounding +
  price/budget/split (T5), approvals/revision reset/consensus/ordered (T6), messages (T3/T5/T6), docs (T7),
  live (T8). Error handling: proposal failure and cart failure in T5/T6 messages.
- Types: `GroupProposal`/`GroupProposalLine` used in T5 and T6 with the same fields; callback prefixes
  defined once in `GroupEventMessageService`.
