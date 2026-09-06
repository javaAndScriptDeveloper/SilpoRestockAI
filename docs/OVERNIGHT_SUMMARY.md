# Overnight autonomous session summary

**Session:** 2026-09-04 ~23:50 → 2026-09-05 ~07:25, autonomous, no user input after the initial queue.
**Queue as given:** 23 → 24 → 25 → 28 → 31 → 32 → 30 → 21 → 26 → 27. All ten addressed.

## Done

| Task | What | Notes |
|---|---|---|
| 25 | Special-mode switch commands | Already implemented before tonight's session started; verified against every acceptance criterion via its existing 18-test suite (all automatable, all green). No new code. |
| 24 | Ad-hoc single-order flow (discounted snacks) | New `AdHocOrderService`, promotion-filtered search, reuses task 9/10's cart pipeline. |
| 30 | In-bot Calendar view | New `CalendarViewService`, stateless day-selector + per-day render, triggered by a new `CALENDAR_VIEW` intent (not the word "Календар" — see Questions). |
| 21 | Delta summary on AI-triggered list regeneration | New `ShoppingListDiffService` (pure, unit tested) + `ShoppingListBuilderService.presentRegenerated`. |
| 26 | Graceful failure messaging | Found and fixed a real gap: `TelegramRoutingService.route()`'s `@Async` meant unhandled exceptions were silently swallowed, never reaching the user. New `TelegramFailureRecoveryService` closes it. |

## In review (code done, needs a human)

| Task | What's done | What needs you |
|---|---|---|
| 23 | Theme-var CSS, budget merged into form, ✕ button removed | Dark-theme screenshot, live −/+ tap test — Chrome tool disconnected mid-session |
| 28 | Checkout-link guarantee (all done *before* tonight, reconciled) | Real purchase + screen recording (inherently manual); criterion "all 4 flows have a checkout button" — #24 didn't exist until tonight, re-check now that it does |
| 32 | Hangover-relief quick-order | Real MCP catalog check — do the search terms (`сорбент`, `електроліти`, ...) actually match what Silpo's catalog exposes? |
| 27 | Silpo-brand redesign (`--silpo-primary: #FF8200`, verified from the real logo SVG) | Live `silpo.ua` computed-CSS cross-check (Cloudflare blocked `curl`), 360px/theme regression, before/after screenshots |

## In progress (partial, scoped down)

| Task | What's done | What's deferred |
|---|---|---|
| 31 | `IntentRouterService` core: 8 intents classified and dispatched, wired **additively** (existing slash commands untouched), scheduled-ad-hoc-purchase table + sweep, 3-button menu | "Анкета" reopen + regenerate-list confirmation (criterion 7); full deletion of the now-partly-redundant slash commands (criterion 6's strongest reading) — both deliberately left for a session with a human awake to watch the live classifier |

Every RUNBOOK.md checklist added tonight (tasks 23, 27, 28, 31, 32) is under the matching `### Task N:`
heading — read those before doing the manual passes above.

## Questions and decisions (full reasoning in `docs/OVERNIGHT_QUESTIONS.md`)

1. **Queue was stale vs. git reality** — tasks 25 and 28 were already built before tonight; verified rather
   than redone.
2. **Task 24's own design ambiguity** (search-then-filter vs. filter-then-search for "discounted snacks") —
   picked the simpler, more literal reading; no acceptance-criterion difference either way.
3. **Task 31 additive rollout** — built as a new fallback, not a replacement for the tested slash-command
   surface, to avoid unsupervised risk to already-working flows.
4. **Task 31 follow-up** — `SpecialModeService.detectGastritisIntent` is now dead code (superseded by the
   router). Left in place rather than touching a heavily-tested constructor for a pure cleanup with zero
   behavioral value.
5. **Task 30's "Календар" naming collision** — that label already means "connect Google Calendar" (task
   18, shipped). Built the new view under a different trigger phrase instead of colliding with it.
6. **Task 27's live-site color verification** — blocked by Cloudflare + no working browser tool all
   session. Used the one source that *did* verify cleanly (the logo SVG) and flagged the gap rather than
   guessing or pretending both sources were checked.

## Blockers encountered

- **Chrome browser tool disconnected** partway through task 23 and never reconnected for the rest of the
  night — this is the single blocker behind every "In review" status above that mentions a screenshot or
  live-theme check.
- **This machine's Gradle/Testcontainers contention** was severe most of the night (multiple concurrent
  Claude Code sessions sharing the box) — full-suite runs regularly took 20+ minutes instead of the
  ~5 minutes seen earlier in the day, and one stale background run was killed after running for over five
  hours without finishing. Every merge decision tonight was validated with a *targeted* test run first
  (fast, reliable) before a full-suite confirmation; no code was merged on faith.
- **No blocker stopped the queue itself** — every item got real attention; the ones marked In review/In
  progress are genuine scope limits (a real payment, a real screenshot, a real live-catalog check, live
  browser access), not things left undone out of time pressure alone.

## Repository state

10 commits added tonight, one per logical change (code) plus doc/checklist commits, all on `main`, all
**unpushed** — matches this session's standing pattern of never pushing without an explicit yes, and the
still-open "push?" question from earlier in the evening (before this overnight run started) remains open
too. `git log --oneline` from tonight's start (`5c436df`) to now shows the full sequence if you want the
blow-by-blow; each commit message explains what was done and, where relevant, what still needs a human.

Final full-suite regression is running as this file is written — if you're reading this and want to
confirm it finished clean, check `git log` for anything after this commit, or just re-run `make test`.

---

# Session 2 (daytime continuation), 2026-09-05

**Session:** 2026-09-05, autonomous, continuing directly from the overnight session above.
**Queue as given:** 31 → 29 → 12 → 33 → 34, then stretch tasks 17/18/19 if time allowed. All eight
addressed. 19 commits, all on `main`, all unpushed (same standing pattern as session 1).

## Done

| Task | What | Notes |
|---|---|---|
| 12 | Free-text check-in parsing (text path) | Already fully implemented before this session reached it (`CheckinParsingService`, `CheckinFlowService`, `SpeechToTextClient` all pre-existing); Notion Status just hadn't caught up. Re-verified against the full suite, every text-path acceptance criterion automated and green. Voice path left exactly as found (needs a live `STT_API_KEY`, explicitly out of scope per this session's instructions). |

## In review (code done, needs a human)

| Task | What's done | What needs you |
|---|---|---|
| 31 | Closed the one gap left open overnight: tapping «🧾 Анкета» now reopens the WebApp form pre-filled from the saved profile, saves a resubmission immediately, and asks before regenerating the list only when an answer changed (`docs/superpowers/plans/2026-09-05-profile-reedit.md`). All 8 acceptance criteria now code-complete. | Live-Claude classification accuracy for every intent phrase (stubbed-classifier tests prove dispatch, not real-model accuracy) — checklist in `docs/RUNBOOK.md` → "Task 31" |
| 29 | Menu already at task 31's 3-button end state; found and closed a real gap — `BLACKOUT` had no chat-intent equivalent at all (only `/blackout` worked), added as a ninth `IntentRouterService` intent | Same live-classification check as above, now also covering "світло вимкнули" |
| 33 | New `ScheduledTaskManagementService`: list/edit/cancel over `scheduled_ad_hoc_task` rows, fourth persistent-menu button ("🗓 Заплановані") | Manual end-to-end: schedule → edit time → confirm sweep respects it; schedule → cancel → confirm it never fires (`docs/RUNBOOK.md` → "Task 33") |
| 34 | **Notion page was completely empty** (title + dependencies only, no Context/Goal/criteria). Built from code evidence + the already-shipped `ReorderConfirmationService` slot-menu precedent: cart confirmation now shows the delivery window and lets it be changed before checkout, booked lazily at confirm time, never eagerly. Full reasoning and explicit scope cuts in `docs/OVERNIGHT_QUESTIONS.md`. | No acceptance criteria ever existed to verify against — please confirm this reading of "actively configure delivery" matches intent, then a live check that Silpo's checkout page reflects the picked slot |
| 17, 18, 19 (stretch) | All three already fully implemented before this session reached them, every automatable criterion checked and green (re-verified: `CheckinParsingIntegrationTest`, `CalendarIntegrationIntegrationTest`, `BlackoutModeIntegrationTest`). Notion Status was stale on all three. | Each needs one real external thing this session cannot get: a live `ANTHROPIC_API_KEY` + real fridge photos (17), a real Google Cloud OAuth client + account (18), a live Silpo catalog query check (19) |

## Also fixed along the way (not asked for, found while working)

- **Two pre-existing test-suite breaks**, unrelated to any queued task, discovered when the required
  "full `make test` before every task" gate failed before task 31's remaining work even started: Gradle's
  test-executor heap (512m default) was dying outright under this many Spring contexts (bumped to 2g), and
  `ArchitectureTest.servicesAreNamedProperly` was failing on 7 classes — private nested records/enums that
  are implementation details of their owning service, plus `MainMenuKeyboard`, none of which are actually
  services. Rescoped the rule to `@Service`-annotated classes, which is what "is a service" actually means.
- **A stale test** (`TelegramOutboundServiceIntegrationTest`) still asserting the old six-button keyboard
  task 31 had already replaced.
- **A real locale bug**, found while building task 34: `DateTimeFormatter.ofPattern("d MMMM, HH:mm")` with
  no explicit `Locale` renders under this process's `-Duser.language=en` JVM arg, so
  `AdHocScheduleService`'s own "Заплановано на ..." confirmation message has been showing English month
  names ("September") instead of Ukrainian ("вересня") since task 31 shipped it — never caught because no
  test asserted on the actual month text. Fixed in both the existing formatter and the new one this session
  added.
- **`CartBuildingService` now clears the cart before rebuilding it**, not just before adding to it — carried
  over as uncommitted WIP from before this session started, verified against its existing tests, and
  committed as its own change. `silpo_add_or_update_cart_products` never removes a line, so a rebuild that
  re-searches the catalogue could accumulate stale lines from earlier attempts.

## Questions and decisions (full reasoning in `docs/OVERNIGHT_QUESTIONS.md`)

1. **Task 29's own spec assumes task 33 already exists**, even though this session's queue ran 29 before
   33 — implemented 29 against task 31's still-current three-button target instead of blocking on 33; the
   fourth button was added as part of task 33 itself, when the feature it points at actually existed.
2. **Task 34's Notion page was entirely empty** — no Context, Goal, or acceptance criteria, only a title.
   Scoped from code evidence and an already-proven pattern rather than guessing UX from nothing; explicitly
   did not build delivery-type switching or full-address display, since neither has any supporting
   precedent anywhere in this codebase.

## Repository state

19 commits added this session, on top of session 1's 10 — all on `main`, all unpushed, same standing
pattern. `git log --oneline ca4345a^..HEAD` shows this session's full sequence. Full `make test` confirmed
green after every task before its commit, not just at the end.

---

# Session 3 — static product audit, 2026-09-06

**Mode:** read-only audit of the Telegram surface (`controller.telegram`, `service.telegram`, every
service that builds a keyboard or handles an intent) against the Notion source of truth — the
Development Plan database (tasks 01–42), «Сценарій демо-запису» and «Selling Points та Пітч-аргументи»
— then small, separately committed fixes. No live run, no integration tests against real Silpo/Claude.
Every change has automated coverage; the full suite result is at the bottom.

**Commits:** 11 code commits + this docs commit, all on `main`, all unpushed (same standing pattern).

## Found and fixed

| # | Finding | Where | Fix |
|---|---|---|---|
| 1 | Yesterday's last commit over-trimmed the scheduled-purchase confirmation: the bot answered «замов вино до п'ятниці» with just "вино" — the person's words echoed back with nothing decided. RUNBOOK still expected "Зроблю це найближчим часом: …". | `AdHocScheduleService` | Sentence restored, still with no date. |
| 2 | **«📝 Список» never showed the list.** The button (and the LIST_VIEW intent) always opened "Що беремо на цей тиждень? — фото / чек / напиши", even seconds after a weekly plan had put a full list on screen. Demo step 4 ("кнопка «Список» → список за категоріями") would not have worked as written. | `ShoppingListBuilderService`, `TelegramRoutingService`, `IntentRouterService` | New `showCurrentOrAsk`: an ACTIVE list is shown with its Замовити/Змінити buttons (no model call); the question only when there is nothing to show. |
| 3 | **Every cart confirmation lied about the baseline.** "Зберіг цей кошик як еталонний набір" was said for blackout, hangover and Friday-snack orders too, but `CartConfirmationService` only stores a baseline for `INITIAL`. Ad-hoc carts also opened with "на тиждень". | `CartMessageService`, `CartConfirmationService` | `cartText`/`confirmedText` take the `OrderType`; only INITIAL claims the baseline, the rest say it is untouched. |
| 4 | **Check-in prompts wiped any newer flow's state.** `isBusyElsewhere` knew only ONBOARDING and CART_CONFIRMATION; a sweep landing mid-`REORDER_CONFIRMATION` (or SPECIAL_MODE_SETUP, SCHEDULED_TASK_EDIT, LIST_BUILDING, PROFILE_REEDIT) overwrote `conversation_state` and the buttons on screen went silently dead — the same bug shape as the two live-test fixes from 05.09, from the other side. | `CheckinPromptService` | Busy = any flow other than NONE / CHECK_IN. Cost: a check-in waits for the next hourly sweep while a flow is open. |
| 5 | Dead code flagged for daylight deletion in `OVERNIGHT_QUESTIONS.md`: `detectGastritisIntent`, its record, its prompt, and the `ClaudeApiClient` dependency it kept alive. | `SpecialModeService` | Deleted; constructor collapses to `@RequiredArgsConstructor`. |
| 6 | **Four capabilities reachable only by slash commands** nobody would type unprompted — the exact gap task 29 found for `/blackout`: return to normal mode (`/normal`, the brief's flow-9 last step), start a reorder (`/reorder`, which demo step 8 depended on), connect Google Calendar (`/calendar`), and a concrete list edit («прибери молоко зі списку», which task 31 names but which was folded into LIST_VIEW and asked the person to repeat themselves). | `IntentRouterService`, `intent-router-system.txt` | Intents `SPECIAL_MODE_END`, `REORDER`, `LIST_MODIFY`, `CALENDAR_CONNECT`. Command bodies moved out of the routing service into `ReorderConfirmationService.startNow` / `CalendarIntegrationService.offerConnection`; slash commands call the same methods. |
| 7 | `/start` after onboarding went to the classifier → "Не зовсім зрозумів", to the one word every Telegram user knows and the thing the failure-recovery message tells people to type. | `TelegramRoutingService` | Re-sends the keyboard with "Я тут…", no model call. |
| 8 | **Voice notes and photos outside a flow got "Скористайся кнопками нижче".** The brief puts text and voice on equal footing; the STT client already existed for check-ins. | `IntentRouterService.routeVoice`, `TelegramRoutingService` | Voice → transcribe → route as text (honest "не розбираю" without an STT key). Photo → opens the list builder with it, list shown for approval, nothing ordered. |
| 9 | Four reply-keyboard labels in one row get truncated on a phone ("Заплан…"). | `MainMenuKeyboard` | 2×2. |
| 10 | **«🗓 Заплановані» was empty almost every time.** Since the deadline fix a task is PENDING for one sweep at most, so "Немає запланованих замовлень" right after «замов вино» read as "I lost your request". | `ScheduledTaskManagementService`, repository | "Нещодавно виконав: ✅ …" tail (last 5 FIRED; CANCELLED stay out) — task 33's optional point 7, made necessary by ASAP scheduling. |
| 11 | Copy: register slipped to «ви» in six places (failure recovery, special-mode refusals, reorder confirmation, ready-meals caveat, unresolved-items line); refusals said "type /normal" / "type /masgain"; list-cancel said "напиши /list"; reorder had «Інший слот» vs the cart's «Інший час»; the mass-gain cross-sell ended in "можу підказати, якщо цікаво" with nothing handling "цікаво". | see commit `Speak in one voice…` | Unified to «ти», pointed at buttons/sentences the router understands, cross-sell now names the «додай протеїн» edit that works (via LIST_MODIFY). |

Also: `SPECIAL_MODE_LEANER` now passes the person's own sentence to the planner, so «мінус 200 ккал на
день» (brief flow 6) keeps its number; the «Інструкція» text was rewritten to open with what the four
buttons do and give one example per intent; the clarifying question points at the «Інструкція» button.

## Added beyond the backlog (and why) — documented post-factum in Notion

- **#43** «Chat-first gaps» — items 6, 7, 8 above. The pitch's central claim ("кожна нова фіча — новий
  обробник намірів, не нова кнопка") was untrue for four capabilities; a jury member typing any of
  those sentences would have hit a clarifying question.
- **#44** «Copy audit» — items 1, 3, 11. One voice, and no confirmation text that contradicts what the
  code does.
- **#45** «Product-audit fixes» — items 2, 4, 5, 9, 10. All either a button that did not do what its
  name says, or a silent dead-buttons bug.

Docs synced: RUNBOOK's task-31 checklist gained rows for every new phrase and now says four buttons in
two rows; the Notion demo script's step 2 (menu), step 8 (reorder by chat, not `/reorder`), step 13
(«повертай звичайний раціон», voice variant) and its changelog were updated.

## Left as larger, deliberately untouched work

- **Not-started tasks #35–#42** — untouched, per the session rules.
- **Deadline vs. trigger-time model** for one-off purchases: everything fires on the next sweep, so
  `trigger_at` (and the «Редагувати» → "нова дата" path of task 33) is effectively decorative. A real
  "by Friday, but wait for the promo" model needs a product decision, not a patch.
- **Slash commands** remain as a second entry point (task 31's additive-rollout decision stands).
  Deleting them is safe once the live-classification checklist in RUNBOOK has been walked.
- **Check-in while a list awaits approval**: fix 4 means a household that never taps Замовити on a
  regenerated list gets no check-in prompt until they do. Visible and recoverable, unlike the dead
  buttons it replaces — but if `LIST_BUILDING/AWAITING_APPROVAL` should not count as busy, that is a
  one-line change in `CheckinPromptService.isBusyElsewhere`.
- **Live classification accuracy** for the new phrases is, as for every intent, a human-in-Telegram
  check (RUNBOOK → Task 31).

## Verification

Every commit was compiled and its own test classes run before landing (targeted runs, all green:
IntentRouter 14, ScheduledTaskManagement 11, ShoppingListBuilder 11, CartConfirmation, CartMessage 12,
CheckinPrompt, SpecialMode 15, ReorderConfirmation 13, Calendar, FailureRecovery, Webhook 8,
Outbound 6, ArchUnit 14).

Final gate after the last code commit: full `./gradlew test` — **58 classes, 386 tests, 0 failures,
0 errors, 0 skipped** (`make check` formatting included via `spotlessApply` before every commit).

---

# Session 4 — the rest of the backlog, 2026-09-06

**Mode:** autonomous, auto-approve, full read of the Notion workspace first (product page, all 47 tasks
incl. Done/In review/Dropped, demo script, Selling Points, setup notes). Queue as given:
38 → 39 → 47 → 37 → 35 → 36 → 46. All seven built. Each ran brainstorm → plan
(`docs/superpowers/plans/2026-09-06-*.md`) → execute, with a targeted test run per commit and a full
`make test` after each task. **20 code+doc commits**, all on `main`, all unpushed (standing pattern).

## Everything is In review — what each one waits for

| Task | Built | What needs you (RUNBOOK section) |
|---|---|---|
| 38 | Cooking-time question first in the WebApp form (legend «Як у тебе з готуванням?», segment-shaped options) **and** in the chat fallback, which had never asked it at all — `cooking_time_preference` stayed NULL there, so task 22's ready-meals fork never fired for anyone who tapped «Заповнити вручну». | Form order on a real phone; fallback buttons. (Task 38) |
| 39 | «Орієнтовно ~X грн — точну суму покажу в кошику» on the list before any cart exists: catalog price carried from the ready-meals plan (`shopping_list_item.estimated_price`), baseline-basket line prices by name for everything else, partial sums say «за K з N позицій». The cart total (criterion 1) was already there since task 10. | Does Silpo's search response carry `price` on a real account. (Task 39) |
| 47 | Fifth button «💬 Фідбек» (own keyboard row), `feedback` table, `FEEDBACK` flow that snapshots and restores whatever flow it interrupted; works before onboarding ends; a menu tap abandons an open prompt instead of filing the next list edit as feedback. | The "back where I was" feel in live Telegram. (Task 47) |
| 37 | Instrumented the three gaps (check-in prompts sent, unresolved lines + edited-before-confirm per order, an `mcp_tool_call` log fed by an event from the MCP client), `MetricsService`, `GET /internal/metrics/pitch` + `make metrics` behind `METRICS_TOKEN`. Five metrics, each printed with numerator and denominator. | **Criterion 4 is honestly unmet:** the local DB has 1 user and 0 confirmed orders, so no real number exists yet. Run the demo once with the token set, `make metrics`, paste into the empty «Реальні цифри» table on the Selling Points page. (Task 37) |
| 35 | «Зроби список як минулого разу» → recent orders from both history tools as buttons → the picked order's real product ids, quantities and prices become the live list → ordinary Замовити → confirm → baseline; zero `silpo_find_products_batch` calls (asserted). Case B (receipt from another shop) already existed as the list builder's «фото чека» path; its copy now says it is approximate. | Pick a real order and compare item-for-item — **no order-history JSON has ever been observed in this repo**, the key guesses are exactly that until you see real buttons. (Task 35) |
| 36 | «Замов усе для карбонари» → `scheduled_ad_hoc_task` row of kind `DISH_INGREDIENTS`, fired through the same `fire` the sweep uses → Claude's one-dish shopping list → ordinary AD_HOC cart via task 09's name search. Captioned dish photo → vision identify → «Схоже на «X». Замовляти?» → Так/Ні. | One real text dish and one real photo dish against live Claude and catalog. (Task 36) |
| 46 | `partner_promotion` / `partner_promotion_event`; `CartBuildingService.resolveProducts` prefers a placement for a line whose name contains the category word, after restrictions clear it, verified live by adding the partner's exact catalog name to the **same** batch search; IMPRESSION → ADDED_TO_CART → CONFIRMED_ORDER; ★ on the line with one honest footer; `POST /internal/promotions` (catalog-verified through a connected user) and `GET /internal/promotions/report` / `make promotions`. | Create a «молоко» placement through your session, walk plan → cart → confirm, read the report. (Task 46) |

## Where I deviated from the task text, and why (full reasoning in `docs/OVERNIGHT_QUESTIONS.md` → Session 4)

- **38:** the spec says "purely field ordering, no new fields". The chat fallback had no cooking-time
  question to reorder, so I added one (three inline buttons). The spec's own criterion 1 requires it.
- **39:** criterion 1 ("cart total") was already met; the real gap was the list preview, one stage
  earlier. Added a baseline-price fallback the spec did not ask for, so cooking households get an estimate
  from their second list on; a first list has no price line at all rather than a fake one.
- **47:** a persistent-menu tap while the prompt is open **abandons** it (spec silent). Otherwise «Фідбек»
  → «Список» → "прибери молоко" would have filed the list edit as feedback.
- **37:** "reorder confirmed unedited" keys on the flag's presence, not `OrderType.REORDER` — that value
  does not exist (`SCHEDULED_REORDER` / early-trigger `AD_HOC`). The report endpoint is token-gated because
  the demo box sits behind a public tunnel.
- **35:** built only the chat entry, not an onboarding fork — an extra question for every new household
  for a path most won't take on day one, and the product's pitch is "just say it".
- **36:** the row is fired **immediately** through the same `fire` the 15-minute sweep uses, not left for
  the cron. "Run now" through a sweep is a dead screen on a demo; the architectural point (one scheduling
  concept, one row, visible in «Заплановані») is kept. Photos need a caption to mean "a dish" — a bare
  photo stays the fridge/receipt list builder (tasks 20/43), because nothing on the image says which it is.
- **46:** liveness rides on the existing search (no second call); restrictions are keyword stems (the
  catalog has no allergen data) and the RUNBOOK says so; ★ disclosure added although the spec left it open.

## Notion changes outside code

- Tasks 35, 36, 37, 38, 39, 46, 47 → **In review**, each with a status note at the top (what was built,
  what needs you, where the checklist is). Task 29 got a note that its five-button target now ships.
- **Demo script:** step 2 (five buttons, new first question), new optional step 3.5 (past-order seed),
  step 13.5 (partner placement, code done), changelog entry for this session.
- **Selling Points:** new section «Реальні цифри для «Валідації» (задача #37)» with the exact command and an
  empty table marked "fill after the rehearsal"; jury-criteria row for MCP quality names #35; the partner
  placement section gained a "state + what to show live + two mechanical pitch arguments" paragraph.
- **Product page:** pitch point 5 now says how to get the numbers and that the table is empty on purpose.
- No new tasks created; no statuses touched on 17/18/19/23/27/28/29/31/32/33/34; 40 untouched; 41/42 stay
  dropped.

## New questions for you (none blocking)

1. **Task 37's numbers only exist after a live run.** Everything else in this session is "In review
   pending your eyes"; this one is "In review pending your run" — same checklist, different reason.
2. **Task 35's response shape.** If the buttons come out as «Замовлення» with no date, the fix is one
   key array in `McpResponses`; the RUNBOOK tells you which log line to paste.
3. **Task 46 disclosure.** I put a ★ and a one-line footer in the cart. If you'd rather not disclose in the
   bot at all, that is two lines in `CartMessageService` — but I'd argue against it in front of this jury.
4. **Allergen honesty (46).** The guard is keyword-based; the RUNBOOK and the Notion note both say so.
   Decide whether that sentence belongs in the pitch or only in the Q&A pocket.

## Verification

Every commit: `spotlessApply` + the touched test classes green before landing. After every task: full
`./gradlew test` green (exit 0) — 38, 39+47, 37, 35, 36 each had their own run. Final gate on the committed
tree after task 46: **66 classes, 420 tests, 0 failures, 0 errors, 0 skipped** (`./gradlew test`, exit 0; session 3 ended at 58 classes / 386 tests).

# Session 5 — the «every message comes back as the same weekly list» bug, 2026-09-06 (evening)

Not a backlog session. One report, investigated from the foundation up rather than at the symptom, as
asked: neither «замов сир з вином на п'ятницю» nor «замов усе для карбонари» produced a narrow result,
*and* an ordinary weekly list could not be pushed through to a real cart. The suspected cause was a
silent catch-all fallback returning a cached list instead of an honest error.

**There is no such fallback.** It was four defects. Full reasoning in `docs/OVERNIGHT_QUESTIONS.md`.

## What was actually wrong

1. **`LIST_BUILDING/AWAITING_APPROVAL` swallowed every free-text message, forever.** Showing a list
   parks `conversation_state` there and nothing clears it; the routing layer gated the whole flow on it
   and rewrote any sentence as a list edit. From a household's first weekly plan onwards
   `IntentRouterService` was never called at all. Confirmed against the live database before any code
   was touched — the account was sitting in exactly that state, with «Вино» and «Сир твердий» folded
   into its list from a swallowed message. The suite stayed green because every intent test deletes
   `conversation_state` first.
2. **A failed callback acknowledgment destroyed the tap's work** — `[400] query is too old` threw, and
   every flow acknowledges the tap first, so the handler died before building anything.
3. **Fabricated `productId`s made a list permanently unorderable** — model-invented ids `1..32` counted
   as "pre-resolved", the product search was skipped entirely, and Silpo refused the whole cart with
   `Invalid UUID` on every retry.
4. **Two Silpo refusal codes reached the household as raw machine text.**

## Verified on the live account, not only in tests

Driven through the real webhook against the real Silpo MCP with the account's own OAuth token: both
reported phrases now route correctly from the exact parked state, and the dish order runs end to end —
4 of 4 lines resolved live → cart → «Підтвердити» → `order … confirmed` with the checkout link. The
32-line weekly list now also builds a real cart (31 of 32 resolved, 30 products added).

## Deviations and judgement calls

- **Went wider than the two repro phrases, deliberately.** The instruction was to fix a silent
  swallowing at the root rather than patch two cases. That meant hoisting the list keyboard to global
  dispatch and stopping a list awaiting approval from counting as "busy" for check-ins — the same root
  cause, silently ending check-ins for anyone shown a list they did not order.
- **Rewrote two existing tests rather than preserving them.** `typingInsteadOfTappingIsTreatedAsAnEdit`
  encoded the bug itself; it now asserts the sentence is classified and a genuine edit still edits.
  Test fixtures using `"p-1"`-style product ids were changed to UUIDs — no live Silpo response has ever
  contained a non-UUID product id, and the live `Invalid UUID` refusal is the evidence.
- **Did not invent a policy for a too-heavy weekly cart.** Silpo's 40 kg cap, age confirmation for
  alcohol and per-branch stock limits genuinely block a full week's order. The household is now told
  why in plain Ukrainian, but *what the agent should do next* is a product decision (split the order,
  cap the plan's weight at generation, trim to stock, or ask which lines to drop) and is the one thing
  left between «Список» and a completed weekly order.
- **Did not chase a quantity-conversion bug found in the same live run** — «Куряче філе 850 г» became
  `quantity=15.4` of a 100 г-ratio product (₴4996 for one line). Distinct bug in task 09's
  `displayRatio`/`step` handling, very likely the main reason the weight cap fires at all, and it needs
  its own reproduction against live catalog data. Recommended as the next task.
- **Two suite runs showed an unrelated flake** (`VoiceReplyIntegrationTest`,
  `TelegramWebhookRegistrationIntegrationTest` failing on a missing `telegramUpdateDedupCache` bean);
  both pass in isolation and the final `make build` is fully green. Same flake noted in session 2.

## Follow-up in the same session: the 40 kg cap was our bug

Pushed back on the "product decision" framing with the right instinct — an order heavier than 40 kg is
strange. It was. Every `weighted: true` line was **exactly ten times too large**: Silpo's `quantity` for a
weighted product is the weight in kilograms (its `price` is per kg and `quantity * price == subTotal`),
while `displayRatio` ("100г") is only a pricing-display hint. `cartQuantity` divided by it anyway.
«Картопля 2000 г» was ordered as 20 kg from a branch holding 7; chicken as 15.4 kg at ₴4996. Weighted
lines summed to ~58 kg instead of ~5.8 kg — which is also where all three stock errors came from.

Fixed, and re-verified live on the same 32-line list: ~8 kg, `presented cart … as draft order`, checkout
link issued. **The weekly path now reaches a checkout link end to end.** The weight message still names
the number for the day a genuinely large week hits the cap.

Left standing, and recommended next: **product-match quality**, not quantities. That cart matched
«Яловичина 850 г» to 34 packets of 25 g beef jerky (₴3246), «Рис» to a black-truffle rice (₴949). The
arithmetic is right, the product is wrong — task 09's fuzzy name search, and most of a ₴7668 weekly total.

## Third piece: choosing the product, not Silpo's top hit

`resolveProducts` took `products[0]`, and Silpo does not rank the ordinary version of a thing first. The
live candidate lists show why no stop-word list would have worked: «Яловичина» returns 26 products whose
top three are jerky snacks and whose tail holds cat food and dog treats; «Спагеті» puts a **serving spoon**
four places above the pasta; «Банан» returns thirty processed products and two anti-stress toys with no
fresh banana at all.

The model now chooses among the candidates Silpo really returned, one call per cart, answering with a
*position* — never an id it could invent — and `-1` for "none of these". That last part is the point:
«Банан» becomes an honest «Не знайшов» instead of banana chips. Fallbacks are loud (INFO with no API key,
ERROR on a failed call), never a silent success.

Verified live on the same 32-line list: checkout link issued, 27 of 32 resolved, 5 honestly unresolved,
₴7668 → ₴3525 — not thrift, but the cart finally holding what the list asked for.

**Next, and a different problem:** «Яйця курячі» and «Йогурт натуральний» came back with *zero* candidates —
Silpo's search found nothing for those terms. Same class as «Банан». That is the search *term*, not the
ranking, and fixing it needs a second query pass. This change makes those lines honest and countable first.

## Notion edits

- **09** Done → **In review**, with the fabricated-`productId` root cause, the live verification and the
  open quantity-conversion question.
- **24** Done → **In review** — the flow was unreachable from chat despite being Done; the scheduling
  half is now verified live, the ad-hoc cart build is not.
- **26** Done → **In review** — no silent fallback exists, but two error-handling defects did.
- **28**, **31**, **36** stay **In review**, each with what the live run proved and what it did not.

## Checked in

One commit, unpushed: `Stop a finished list from swallowing every message a household types`.
RUNBOOK gained three session-5 checklists.
