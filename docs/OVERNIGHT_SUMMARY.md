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

---

# Session 6 — full stabilisation walk-through on the live account, 2026-09-06 (evening)

**Mode:** autonomous, auto-approve. Full Notion read first (product page, all 47 tasks, demo script, Selling
Points), then the whole product driven as a household would use it — synthetic Telegram webhooks against the
**real** Silpo MCP and the real Claude API, every message the bot sent read back from the log (outbound
logging was the first thing added, because there was none). Every problem found was fixed the moment it was
found, one commit each, with tests for the class of bug rather than the instance. **14 code commits**, all on
`main`, unpushed (standing pattern). Final gate: full `./gradlew test` — see the bottom.

The three bugs in the brief were reproduced, and none of them was one bug:

| Reported | What it actually was | Status |
|---|---|---|
| «Замов усе для карбонари» → 2 kg DOP cheese at ₴2798, Shirataki, ₴3174 for one dish | Two layers that had already been fixed in session 5 (×10 weighted quantities; Silpo's top hit as the product), **plus** three that had not: a matcher whose failure silently fell back to that top hit, cart lines that showed the price per kilogram next to a fraction of one («0.1 — 1399.00 грн»), and Silpo's ₴799 minimum order refusing every small cart | Fixed. Live now: La Pasta spaghetti ₴43, pancetta, Grana Padano 100 g ₴140, eggs honestly unfound, cart presented in 16 s |
| «Замов сир з вином по знижці до п'ятниці» → weekly list | Session 5's state bug was real and fixed; what was left underneath was worse: the scheduled purchase **ignored its theme entirely** and searched Silpo's promotions for snack keywords — and the promotions tool answers with campaign codes, not products, and had been refusing our arguments on every run. Live result was «немає активних знижок на снеки — спробую пізніше», a promise nothing kept | Fixed. Live now: hard cheese, camembert, cabernet — all three on promotion, «Економія за акціями: 316.02 грн» |
| «Замов усе для карбонари» → weekly list | Same state bug (session 5), confirmed gone; but a **new** way to get the same symptom was found and fixed: an open check-in prompt swallowed any request typed over it («Не розібрав. Скажи коротко по цих: Хек Norven…») | Fixed |

## Found and fixed, in the order a household hits them

| # | What a person saw | Cause | Fix (commit) |
|---|---|---|---|
| 1 | **First plan never generated** for a two-adult household: «План скласти не вдалось» after six minutes, three times in a row | The recipe planner was asked for ~100 per-meal ingredient objects incl. invented `productId`/`price`; the answer ran past the 8192-token cap (JSON cut mid-string) and the 120 s timeout, and the transport retried the same oversized call twice | Planner answers with meal *names* by day plus **one shop-sized purchase list** (`RecipeWeek`); no id/price fields exist to fabricate; a cut-off answer now says `max_tokens`; the failure message has a «Спробувати ще раз» button instead of a promise. Plan in 31 s, 25 sane lines (`202b24d`) |
| 2 | List quantities were recipe-gram sums: «Мед 20 г», «Борошно 100 г», «Гранола 80 г» | Same cause — aggregation of per-meal grams | Same fix; the planner rounds to what the shop sells («Борошно 1 кг», «Яйця 20 шт», «Молоко 2 л») |
| 3 | «Замовити» dead forever after a restart | The double-tap guard is cleared in a `finally` a killed JVM never reaches | Guard expires after 3 min; an ignored tap gets a one-line reply; a failed build ends with «Спробувати ще раз / Змінити список» buttons (`fef91cb`) |
| 4 | The app restarted underneath live cart builds | Tunnel supervisor restarted the app on every reconnect, hostname changed or not | Restart only on a new hostname (`a481a70`) |
| 5 | Product choice took **88 s** (Sonnet), then the add-to-cart call timed out at 30 s and the whole cart died | Flagship model for a rules-driven choice; MCP timeout too short for a 25-line add | Matcher on the fast model (24 s); MCP timeout 60 s; add-to-cart retried once (idempotent by product id) (`7318e65`) |
| 6 | Matcher failure → ₴7549 cart of konjac, 17 jerky packets, ₴1399 cheese, with «Підтвердити» under it (seen for real during the credit outage) | Silent fallback to Silpo's own ranking | A failed match fails the cart with a retry offer, never a wrong cart (`7318e65`) |
| 7 | «Молоко 2.5%», «Яйця курячі», «Вівсянка», «Капуста білокачанна» unresolved | Silpo's search is plain text: the term was wrong, not the ranking | **Second search pass**: the fast model suggests other shelf names («Вівсяні пластівці», «Яйця», «Капуста»), one more Silpo call, matcher again. Recovered milk, cabbage, oats live; eggs are genuinely absent from this branch (`7318e65`) |
| 8 | «800 г» against a «1кг» pack, «2 шт» of a 600 г loaf, «4 шт» cucumbers — all silently the minimum step (one pack, one loaf, 100 g) | `кг` not parsed; count vs weight-labelled pack refused; count vs loose produce refused | `кг` parses; a count against a weight/volume-labelled pack is that many packs; a count against loose produce is ~150 g a piece, said in the log; litres relate to gram-labelled packs 1:1 (`7318e65`, `a074719`) |
| 9 | Gerber infant porridge (₴388) chosen for «Вівсянка» by the fast model | No floor under the model's judgement | Pet food, baby food, toys, kitchenware dropped before any model sees them; prompt says a «no raw meat here» reason must come with -1 (`a074719`) |
| 10 | No sanity check on price or amount | — | A line over ₴1500, 20 units or 6 kg is held back and named: «Не поклав, бо виглядає неправильно: …» (`7318e65`) |
| 11 | Carbonara / hangover / blackout / Friday-snack carts **cannot check out**: Silpo's ₴799 minimum delivery order (`order.cost.min`), shown to the household as a raw `order.payment_types.disabled` | Every small cart in the product | Shortfall filled from the household's own confirmed baseline, cheapest lines first, each named with the right to take it out; measured against `productsTotal` (goods, not goods+delivery — the first attempt was ₴95 short); no baseline → honest message naming the amount and the minimum; info-level notes from Silpo no longer shown (`7f35101`, `1530ed8`) |
| 12 | «Грана Падано — 0.1 — 1399.00 грн»; «Доставка: 2026-09-07T06:00:00+00:00» | Cart lines carry no unit; slot label is raw ISO | «0.1 кг — 139.90 грн»; «пн, 7 вер · 09:00–10:30» in Kyiv time, also on every «Інший час» button (`cd7283c`) |
| 13 | Hangover kit: ₴1514 — the same ₴329 electrolyte drink twice, two Atoxil gels, a ₴464 charcoal | Seven overlapping search terms merged into duplicate lines | Water ×2, isotonic ×2, one sorbent (`97bcfdb`) |
| 14 | Blackout kit at ₴742 got topped up with flour and raw carrots | One of everything sat under the minimum; the top-up drew from a cooking baseline | A no-cook stock-up that clears the minimum by itself; «готова страва» dropped (it is a soup in a pouch) (`97bcfdb`) |
| 15 | «Сир з вином по знижці» → «немає знижок на снеки — спробую пізніше» | Theme ignored; promotions tool misunderstood and mis-called since day one | Theme → 1–6 shop lines (fast model) → ordinary pipeline; «по знижці» tells the matcher to prefer candidates marked АКЦІЯ (from `oldPrice`); saving = Silpo's own `subDiscount` (`a2a6642`) |
| 16 | A request typed while a check-in was open was answered «Не розібрав…» | The open question owned the chat | Empty check-in parse → intent router first; a request wins, the check-in waits for the next sweep (`7b9ecc5`) |
| 17 | Delta reorder: 3 lines drafted with the **11 lines of a cancelled cart underneath**; no quantities, prices or total; could never clear the minimum | Reorder composed the cart steps by hand and never cleared the cart | Reorder goes through `buildCart` like every order; lines with cost, total, Silpo's saving (`0ac9d57`) |
| 18 | «Що їмо в середу?» opened the day picker; «додай яйця» added **30 eggs**; in-store order history refused every call | Classifier had no notion of today or a named day; list-edit prompt had no shop-size rule; `silpo_get_my_offline_orders` wants cart-context args | «Сьогодні: …» in every classification and a named day opens that day; shop-size rules in the list-edit prompt; offline tool called with the cart context (`07b6abd`) |

Plus the one that made the rest possible: **every outbound Telegram message is now in the log** next to the
MCP and Claude lines that produced it (`e9fbf6d`).

## Verified live, end to end, on the real account

Onboarding (Silpo enrichment → form) → weekly plan (31 s, 25 lines) → «Замовити» (36 s, 23/25 resolved,
₴2595 for two adults against a ₴2500 budget) → «Підтвердити» → baseline + checkout link → «замов усе для
карбонари» (16 s, dish lines right, topped up to the minimum, confirmed) → hangover kit → blackout kit →
scheduled «сир з вином по знижці» (fires on the sweep, all three lines on promotion) → check-in answer
(counters moved against the baseline's own spellings) → «що треба докупити?» (3-line delta, cart cleared
first) → gastritis → calendar (named day) → back to normal → mass-gain dialogue → UA-only toggle → list edit
→ past-order seed (honest: the account has no orders) → Анкета re-edit with a changed answer → Фідбек →
/start → Інструкція. Not driven: photo paths (no image on this box) and the WebApp form's own UI.

## Product decisions taken without asking (all reversible, all in `OVERNIGHT_QUESTIONS.md` → Session 6)

1. **A too-small cart is topped up from the household's own baseline**, cheapest lines first, every line
   named. The alternative — «менше 799 грн, докинь щось сам» — is a dead end on a demo and in life.
2. **A failed product match fails the cart.** A wrong cart with a confirm button is worse than no cart.
3. **The matcher runs on the fast model.** 24 s instead of 88; quality held on the live weekly list once pet/baby
   food was filtered out deterministically.
4. **Hangover and blackout kits are fixed short lists**, sized to stand on their own.
5. **The promotions tool is not used.** It returns campaign codes for `silpo_get_products`, never products;
   Silpo prices promotions into the cart itself, so «знижка» is a matcher preference plus Silpo's own saving.
6. **Reorders clear the cart first.** A household's Silpo cart holds whatever the last cancelled attempt left.

## Requires your decision

- ~~**How much top-up is too much?**~~ **Decided by the owner the same evening** («тут забагато лишнього для
  карбонари», on a 15-line cart): a cart under the minimum is now shown as built, with the shortfall and a
  «Докласти з мого набору (~N грн)» button; nothing is added unasked. Only a delta reorder still tops itself
  up, since restocking staples with more staples is what a reorder is. See the addendum at the end of this
  section.
- **Eggs are not in this branch's delivery catalog** (the search for «Яйця» returns chocolate eggs, then
  dairy). Every list will report them unfound. Worth checking on your own account/branch.
- **Task 32's kit** is now water/isotonic/sorbent. Atoxil is in the catalog at ₴114; whether a grocery bot
  should carry a sorbent at all is your call — the order is honest either way.

## Left standing

- Photo paths (dish photo, fridge photo) and the WebApp form UI — unchanged, still In review, need a phone.
- Task 46 partner placement — untouched, still In review.
- `ReorderService` no longer offers promoted variants (it never worked live); replacements via
  `silpo_get_replacements` stay.
- `.env` carries three session-only knobs (`AD_HOC_SCHEDULE_SWEEP_CRON`, `CHECKIN_INTERVAL`,
  `CHECKIN_SWEEP_CRON`) — **reverted at the end of this session**; the tunnel supervisor was stopped for the
  live run and restarted afterwards.

## Addendum, same evening: three things the owner saw on the phone

| What they saw | Cause | Fix (commit) |
|---|---|---|
| «План на тиждень готовий, 7 днів. Понеділок: … » — «почему нету раціона на другие дни?» | The announcement showed Monday alone by design («the only part anyone reads immediately») | All seven days, one line each, then the purchase-list count (`e2de986`) |
| Carbonara: «Кошик зібрати не вдалось — «Сільпо» або каталог не відповіли вчасно» | Not a timeout. The top-up's add-to-cart came one second after the first and Silpo answered «Rate limit exceeded» as an ordinary tool error, which neither retry path saw | Every cart tool call waits 2 s, then 4 s, and asks again on a rate-limit reply; any other tool error stays fatal (`80ada32`) |
| Carbonara again, now with 12 baseline lines of vegetables under it — «тут забагато лишнього» | The automatic top-up, working as designed | **Ask, don't add.** Below the minimum the cart is shown as built, with «бракує N грн» and a «Докласти з мого набору (~N грн)» button; the tap adds the baseline lines and brings «Підтвердити». No baseline → cancel only and a pointer to the Silpo app. Reorders keep the automatic fill |
| Carbonara a third time: «не відповіли вчасно» with no tool call in the log | The MCP session handshake hung for the full 60 s on a fresh JVM while Silpo answered a probe in 0.1 s; nothing retried a hung handshake | Handshake gets its own 20 s timeout and one retry on a fresh session (`0ee94ce`) |
| «Шукай тільки українського виробника» → «Прибрав обмеження…»; the same sentence again → on | The intent toggled the flag instead of setting it | Set from the sentence; negations («не тільки», «прибери», «будь-якого») switch it off; a repeat answers «Уже шукаю…» (`toggle→set` commit) |
| With the flag on: «не знайшлось жодної позиції зі списку» for a 24-line list | Every term got « українського виробництва» appended and Silpo's plain-text search found nothing; the second pass then sent 48 terms in one call, over the tool's limit of 30, and was refused | Plain search terms everywhere; the preference is a note to the matcher with the Ukrainian brands to prefer; the second pass searches 30 at a time |

---

# Session 8 — production observability: real metrics behind a Grafana dashboard, 2026-09-07 (evening)

Task 54. The pitch needed a live dashboard rather than a claim about one, and the honest version of that
turned out to start with a schema change, not with Micrometer.

## The thing the task could not have known

**`customer_order` had no money column at all.** Silpo's totals (`total`, `productsTotal`, `subDiscount`)
lived only on the transient `CartSummary` record and inside `conversation_state.context_json`; nothing ever
reached a column. So GMV and average cart value were not "a query away" — they were **uncomputable**, and
the task's own acceptance criterion asks for them to be cross-checked against the order table.

`028-order-cart-value.yaml` adds `total`, `goods_total`, `savings`, `topped_up_count`, all nullable. Nullable
is the honest choice: orders confirmed before this change genuinely have no known total, and a `0` there
would quietly drag the average down. Every aggregate filters on `NOT NULL`, and a gauge
(`komora_orders_value_missing`) publishes how many confirmed orders lack a value, so the dashboard states the
coverage of its own GMV number.

Four write sites, not the two that looked obvious:

| Where | Why it is there |
|---|---|
| `CartConfirmationService:134` draft builder | The only point where the first order's `CartSummary` exists |
| `CartConfirmationService:198` top-up re-save | The basket grew; without this the stored total is the pre-top-up one |
| `ReorderConfirmationService:119` draft builder | Same as the first, for a reorder |
| `ReorderConfirmationService:270` confirm | The **only** confirm that re-reads the cart, after accepted replacements |

`CartConfirmationService.confirm` deliberately gets **no** money write: it flips a status on a row rebuilt
from `conversation_state` and never asks Silpo again, so there is nothing newer to write. The stored total is
therefore the cart as the household saw and approved it, before any loyalty-bonus spend — which is the right
definition of GMV anyway, and avoids an extra MCP round-trip on the confirmation path.

## The shape rule everything else follows

*If a panel needs `rate()`, the meter is a counter or timer at the call site. If it shows an absolute level,
it is a gauge fed from the database.*

A Micrometer `Counter` is monotonic only within one JVM, and this app restarts constantly. "Households
registered" and "GMV" are facts about the database, not about this process — after a `make run` they must
still read what the tables say, not zero. So `ObservabilityService` registers gauges over an
`AtomicReference<ObservabilitySnapshot>` that `ObservabilityRefreshScheduler` rebuilds every 30 s: a
Prometheus scrape reads memory and never costs a query.

The scheduler placement is not decoration. ArchUnit lets only `Controller` and `Job` reach a `Service`, and
only `Service` reach a `Repository` — so the natural Spring idiom, a `MeterBinder` bean in `config`, could not
have done this. `job/ObservabilityRefreshScheduler` is the layer that is allowed to.

Task 37's derivation is reused rather than reimplemented: `MetricsService.onboardingToFirstOrder` and
`median` became public statics, and both the markdown report and the gauge call them. `compute()`'s five
`findAll()` stayed where they are — it is a once-per-rehearsal human action, and running it on a 30-second
loop forever would pull five whole tables into heap to extract two fields.

## Instrumented at the call sites

`komora.cart.build` (timer, outcome), `komora.cart.lines`, `komora.cart.minimum`, `komora.cart.topup`,
`komora.mcp.call` (timer, tool + outcome), `komora.claude.call` (timer, call + model + outcome),
`komora.failure.message`, `komora.onboarding.started`/`.completed`, `komora.orders.confirmations`,
`komora.cart.value`, `komora.cart.size`, `komora.intent.classified`.

Three decisions worth keeping:

- **MCP latency is timed inside `callTool`, not off `McpToolCalledEvent`.** That event is published inside the
  lambda and so never fires when the call throws — a transport failure, a 401 the refresh dance could not fix,
  an exhausted retry. Those are precisely the failures a reliability panel exists to show.
- **`image(...)` was folded into the same timing helper as the text calls.** It bypassed both private funnels
  in `ClaudeApiClientImpl`, which is exactly how a call ends up being the one nobody has latency for.
- **`IntentRouterService` gets only `routed | unclassified | failed`, with no per-intent tag.** Intent
  distribution is task 55's artefact, a plain list rather than a Grafana panel; keeping `IntentType` private is
  what stops the two from colliding.

SLO buckets (`management.metrics.distribution.slo`) rather than full percentile histograms: ~40 buckets per
timer × 15 tools × 3 outcomes would spend a quarter of Grafana Cloud free's 10 000 series on one meter.

## Push, not pull

`observability/alloy/config.alloy` scrapes `/actuator/prometheus` and remote-writes to Grafana Cloud's Mimir.
Alloy runs as a container behind a new `observability` compose profile — profile-gated so `bootRun`'s
docker-compose integration never starts it, since without a token it would only crash-loop on every dev run.
On Linux it needs `extra_hosts: host.docker.internal:host-gateway` to reach an app running on the host.

`observability/grafana/komora-dashboard.json` is the dashboard, committed: six rows — зростання, воронка,
замовлення і GMV, якість кошика, партнерські розміщення, надійність. It binds its datasource through a
template variable, which is what lets **one file** render against a throwaway local Prometheus and against
Grafana Cloud. `make dashboard` pushes it; `observability/local/` brings up the local pair that renders it
with no cloud account at all.

`unit/DashboardJsonTest` parses the committed JSON, pulls every `komora_*` series out of the PromQL, and
asserts each maps back to a `utils/MeterNames` constant. That is the cheap guard against the standard way a
checked-in dashboard rots: a meter is renamed, nothing fails, and the panel shows «No data» until somebody
notices it during a pitch.

## Verified live

`MANAGEMENT_PORT` defaults to the app's own port so nothing changes locally; setting it to 8081 on the
tunnelled demo box is what stops one public tunnel from handing GMV and household counts to whoever finds
the URL — the concern `METRICS_TOKEN` already exists for.

## Addendum, same evening: the dashboard went live in Grafana Cloud

Token arrived, `make alloy-up` + `make dashboard`. Proof it is the hosted stack and not the local harness:
Alloy reported **212 samples sent, 0 failed, 0 retried**; querying the stack's own Prometheus through the
Grafana API returns **24 `komora_*` series** and `up{job="komora"} = 1`; and the dashboard's datasource
variable bound itself to `grafanacloud-charmingaphid2632-prom` on load — which is exactly why it is a variable
rather than a hardcoded uid.

Live: <https://charmingaphid2632.grafana.net/d/komora-observability/komora-e28094-observability>

Still ₴0.00 in GMV, honestly: no order has been confirmed *since* the money columns landed, and the panel
next to it says «Замовлень без збереженої суми: 1». Three cart builds were attempted that evening and all
three failed on the environment rather than the code — the branch answered «на складі лишилось 0» for nearly
every line, and by 20:15 Kyiv there was also `timeslot.not_found`, no delivery slot left for the day. One
confirmed order in daylight fills both money panels within 30 seconds, which is also the strongest shot in
the demo (step 13.7).

---

# Session 9 — a console a camera can read, 2026-09-07 (night)

**Task 58 — demo-ready MCP call logging.** Step 6 of the demo script puts the console next to the chat
and calls it the proof of agency. What was actually there: `MCP -> silpo_find_products_batch {branchId=…,
deliveryType=…, products=[…]}` followed by up to 2000 characters of the response, in the same grey as
Hibernate's. Technically complete since task 09, unusable on a recording.

## What it is now

A dedicated channel — the class `client.AgentCallLog`, which exists to *be* a logback category (the
project bans manual `LoggerFactory`, and `@Slf4j` on a class gives the same dedicated logger). One line
per call, fixed columns, colour by outcome:

```
00:12:30 🔗 Silpo MCP session opened — 40 tools available
00:12:31 🔧 silpo_get_time_slots               deliveryTypes=1 item branchId=1edddb40…   263ms  ✅ 27 items
00:12:31 🔧 silpo_find_products_batch          products=11 items branchId=1edddb40… +3 more   429ms  ✅ 11 items
00:12:45 🧠 completeStructuredFast             claude-haiku-4-5-20251001        13.3s  ✅
00:12:49 🔧 silpo_add_or_update_cart_products  products=10 items shoppingCartId=87c8e168…   149ms  ✅ 10 items
```

`logback-spring.xml` (the project's first) binds two appenders to that one category with additivity off:
the console, coloured by level through Boot's `%clr` (green worked, yellow did not), and
`logs/mcp-calls.log` with no escape byte in it, so it stays greppable — **that file is what task 55
should parse instead of collecting the same data a second time.**

`make demo` is `make run` on a `demo` profile that changes nothing but logging: root at WARN, the app's
own lines dim and short, ANSI forced on (Boot's DETECT turns colour off behind the `tee` that `make run`
has always piped through). `make mcp-log` is the second window. Startup is seven faint lines.

## What the rehearsal changed

Three live passes against the real Silpo MCP (blackout, hangover, order history, ad-hoc). Everything
below was invisible until the lines were on a screen:

- **The arguments column ran to 206 characters.** Now it has a budget, and spends it on the interesting
  arguments first: collections before scalars, so `products=11 items` leads and the branch id every tool
  wants falls into `+N more`. Ids collapse to their first block. Widest live line: 112 characters.
- **Every console line ended in a literal `%n`** — `%clr(%m)` without an options block swallows the token
  after it. Boot's own default pattern writes `{}` for exactly this reason.
- **The same sentence appeared twice, once dim and once bright.** The four `MCP -> {tool} {args}` dumps
  and «connected to Silpo MCP — 40 tools available» are DEBUG now; `AgentCallLog` says both in one
  redacted line. Liquibase's `includeAll` also stopped handing `.gitkeep` and `CLAUDE.md` to a parser —
  two stack traces at every startup, and they were the opening shot of the recording.
- **A failed call gets a line too** (yellow, with the reason). The old logging could not: the event that
  carried it is published inside the successful branch.

## Deviations worth knowing

- **Claude calls are on the same channel**, which the task did not ask for. On a recording «🧠 thinking
  13.3s» followed by four «🔧 silpo_…» lines reads as one story about an agent; the MCP half alone reads
  as an HTTP log. Easy to drop — one line in `ClaudeApiClientImpl.timed`.
- **Task 09's test survived, repointed.** `logsEveryToolCallAtInfoSoADemoCanBeRecorded` still asserts the
  same promise, now against the channel the promise lives on.
- **Tokens:** arguments and results both pass through `SecretRedactor` before formatting, and there is a
  test that a bearer header and an `access_token` in a response never reach the line. `grep -Ei
  'bearer|access_token|refresh_token|eyJ'` over both logs after the rehearsal: no hits.
- **Nothing here is production observability.** Task 54's Grafana path is untouched; this is one console
  for one screencast.

## What the first rehearsal attempt taught, separately

Firing nine messages at once (the sandbox turns a foreground `sleep` into a no-op) tripped the Claude
circuit breaker: one 80-second `ClaudeUnavailableException`, then instant failures at 7–14 ms until it
reset. Worth knowing before a live take — the bot cannot be driven faster than it thinks. It also made
the yellow ❌ lines legible on screen for the first time, which was not the plan but was useful.
