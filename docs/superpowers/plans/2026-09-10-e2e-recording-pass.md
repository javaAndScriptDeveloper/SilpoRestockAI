# Task 80 — E2E pass + operational recording script Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Walk the whole recording sequence live against the local stack, fix everything found (one commit each), give the social primitive its own metric and dashboard panels, then write the two Notion documents against prod names and sync the dashboards to Grafana Cloud once.

**Architecture:** The demo order in the spec is the test order. Each live beat is one task with an exact input, the expected chat reply, the log/DB/Grafana check, and the fix rule. Code changes are limited to what the pass finds plus one planned addition: social-channel gauges in `ObservabilityService` and a matching row in `build-dashboards.py`.

**Tech Stack:** Spring Boot 4 / Micrometer, PostgreSQL (`docker exec -i app-db psql -U app -d app`), Grafana 11 local (`:3000`) + Prometheus (`:9090`), Telegram Web K driven through the Chrome tool, ngrok session tunnel, Notion MCP.

**Spec:** `docs/superpowers/specs/2026-09-10-e2e-recording-pass-design.md`

## Global Constraints

- Local test bot «Батон Степанович» only; both output documents name «Смузі Геннадійович», `https://89.167.115.28.sslip.io`, `https://charmingaphid2632.grafana.net/d/komora-business`, `/d/komora-observability`.
- One fix = one commit; `scripts/restart-app.sh` after every code change before re-verifying.
- `make format` before every commit; `scripts/stop-app.sh` before any full `./gradlew test`, restart after.
- Never hand-edit `observability/grafana/*.json`; edit the generator, `make dashboards-json`, `docker restart komora-grafana`.
- Every multi-account step names `[Акаунт A]` (owner, chat `218196255`) or `[Акаунт B]` (second Web account).
- Metric numbers quoted in documents come from this pass, read back from SQL or Prometheus, never estimated.
- Grafana Cloud is touched exactly once, at Task 16.

---

### Task 1: Fresh local stack on a stable tunnel

**Files:** none (operations)

- [ ] **Step 1: Branch**

```bash
cd ~/petProjects/silpoRestockAI && git checkout -b feature/e2e-recording-pass
git add docs/superpowers && git commit -m "Design the E2E recording pass before running it"
```

- [ ] **Step 2: Demo-friendly timers in `.env`** (append/replace these lines; keep everything else)

```bash
CHECKIN_INTERVAL=10m
CHECKIN_SWEEP_CRON="0 * * * * *"
AD_HOC_SCHEDULE_SWEEP_CRON="0 * * * * *"
```

- [ ] **Step 3: Stable tunnel + fresh build**

```bash
scripts/session-tunnel.sh ngrok        # kills the supervisor, points .env, restarts the app
grep -E '^TELEGRAM_(WEBHOOK_URL|WEB_APP_BASE_URL)=' .env
```
Expected: `app is UP after Ns` and a `registered the Telegram webhook` line. Open the WebApp URL once in Chrome and click «Visit Site» (ngrok interstitial).

- [ ] **Step 4: Local observability up and scraping**

```bash
docker ps --format '{{.Names}}' | grep -E 'komora-(grafana|prometheus|alloy-local)'
curl -s 'localhost:9090/api/v1/query?query=up' | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["result"])'
```
Expected: three containers, `up` = 1 for the app target. If not: `make observability-local-up`.

- [ ] **Step 5: Baseline numbers, written down for the diff at the end**

```sql
SELECT count(*) confirmed, sum(total) gmv FROM customer_order WHERE status='CONFIRMED';
SELECT trigger_intent, count(*) FROM customer_order WHERE status='CONFIRMED' GROUP BY 1;
SELECT count(*) FROM mcp_tool_call; SELECT count(DISTINCT tool_name) FROM mcp_tool_call;
```

### Task 2: Social-channel gauges (primitive 4 gets a number)

**Files:**
- Modify: `src/main/java/com/silporestockai/utils/MeterNames.java`
- Modify: `src/main/java/com/silporestockai/model/ObservabilitySnapshot.java`
- Modify: `src/main/java/com/silporestockai/service/ObservabilityService.java`
- Modify: `src/main/java/com/silporestockai/repository/GroupEventRepository.java`, `GiftOrderRepository.java`, `GroupEventParticipantRepository.java`
- Test: `src/test/java/com/silporestockai/integration/ObservabilityMetricsIntegrationTest.java`

**Interfaces:**
- Produces Prometheus series `komora_group_rounds{status}`, `komora_group_participants`, `komora_gift_orders{status}` — used by Task 3's panels.

- [ ] **Step 1: Failing test** — append to `ObservabilityMetricsIntegrationTest`:

```java
@Test
void publishesSocialChannelGaugesForTheViralSection() throws Exception {
    GroupEvent round = new GroupEvent();
    round.setTelegramGroupChatId(-1001L);
    round.setOrganizerTelegramUserId(1L);
    round.setStatus(GroupEventStatus.ORDERED);
    round.setCreatedAt(Instant.now());
    round = groupEventRepository.save(round);
    GroupEventParticipant p = new GroupEventParticipant();
    p.setGroupEventId(round.getId());
    p.setTelegramUserId(2L);
    p.setCountedInDenominator(true);
    p.setCreatedAt(Instant.now());
    groupEventParticipantRepository.save(p);
    GiftOrder gift = new GiftOrder();
    gift.setSenderUserId(user.getId());
    gift.setStatus(GiftOrderStatus.CONFIRMED);
    gift.setCreatedAt(Instant.now());
    giftOrderRepository.save(gift);

    observabilityService.refresh();
    String body = mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

    assertThat(body).contains("komora_group_rounds{status=\"ORDERED\"} 1.0");
    assertThat(body).contains("komora_group_participants 1.0");
    assertThat(body).contains("komora_gift_orders{status=\"CONFIRMED\"} 1.0");
}
```
(adjust entity setters to the real field names in `GroupEvent`, `GroupEventParticipant`, `GiftOrder` — read the entities first; use the test class's existing `user` fixture or create one the way `servesTheDatabaseDerivedGaugesOnTheActuatorEndpoint` does).

- [ ] **Step 2: Run it, expect failure** — `scripts/stop-app.sh && ./gradlew test --tests '*ObservabilityMetricsIntegrationTest*'` → FAIL on the missing series.

- [ ] **Step 3: Implement**

`MeterNames`: `GROUP_ROUNDS = "komora.group.rounds"`, `GROUP_PARTICIPANTS = "komora.group.participants"`, `GIFT_ORDERS = "komora.gift.orders"`, `TAG_STATUS = "status"`.

Repositories:
```java
// GroupEventRepository
@Query("select e.status as status, count(e) as count from GroupEvent e group by e.status")
List<StatusCount> countByStatusGrouped();
// GiftOrderRepository
@Query("select g.status as status, count(g) as count from GiftOrder g group by g.status")
List<StatusCount> countByStatusGrouped();
// GroupEventParticipantRepository
long countByCountedInDenominatorTrue();
```
with `model/StatusCount.java` as a projection interface `{ Enum<?> getStatus(); long getCount(); }` — or two record-typed `@Query("select new …")`; pick whichever the existing repositories already use.

`ObservabilitySnapshot`: add `Map<String,Long> groupRounds, long groupParticipants, Map<String,Long> giftOrders` (update `empty()`).

`ObservabilityService`: two `MultiGauge`s (`groupRoundsGauge`, `giftOrdersGauge`) registered in `registerGauges()`, re-registered in `refresh()` with `Tags.of(MeterNames.TAG_STATUS, status)`; one plain gauge `komora.group.participants`.

- [ ] **Step 4: Run the test class and `DashboardJsonTest`** → PASS. `make format`.

- [ ] **Step 5: Commit** — `git commit -m "Count group rounds and gift orders so the viral primitive has a number"`.

### Task 3: Business dashboard — social section, cut the restart-bound tile

**Files:** Modify `observability/grafana/build-dashboards.py`; regenerate both JSON files.

- [ ] **Step 1: Generator edits**
  - Remove the «Нових анкет за період» stat; widen «Активних за 7 днів» to x=8,w=8.
  - New row after the guest section (y=13): `row("🤝  A2 · Люди, яких бот привів сам — групові збори і подарунки", 13)` with: stat «Групових зборів → замовлено» (`sum(komora_group_rounds{ENV, status="ORDERED"})`), stat «Групових зборів усього» (`sum(komora_group_rounds{ENV})`), stat «Людей відповіли у зборах» (`sum(komora_group_participants{ENV})`), stat «Подарунків підтверджено» (`sum(komora_gift_orders{ENV, status="CONFIRMED"})`), bargauge «Подарунки за станом» (`sum by (status)(komora_gift_orders{ENV})`). Colour `GUEST`. Descriptions name «примітив 4 — людина → агент → людина» and the viral note. Shift every later `y` by 7.
- [ ] **Step 2:** `make dashboards-json && docker restart komora-grafana`; `./gradlew test --tests '*DashboardJsonTest*'` → PASS (add the three names to the test's known-series list if it is explicit).
- [ ] **Step 3:** Open `http://localhost:3000/d/komora-business` in Chrome, screenshot, confirm the new row renders (zeros are fine before Task 11).
- [ ] **Step 4: Commit** — `git commit -m "Give the social channel its own row on the business dashboard"`.

### Task 4: Beat 2 — onboarding from a fresh profile [Акаунт A]

- [ ] **Step 1: Reset profile only** (orders and token stay):
```sql
DELETE FROM user_profile WHERE user_id=(SELECT id FROM users WHERE telegram_chat_id=218196255);
UPDATE conversation_state SET current_flow='NONE', context_json='{}' WHERE chat_id=218196255;
```
`scripts/restart-app.sh`.
- [ ] **Step 2:** In Telegram Web, chat «Батон Степанович»: `/start`. Expect greeting + «Під'єднати Сільпо» (if the token is present the greeting says so and skips). Then «Заповнити анкету» → form → «Готово». Expect: «Зазирнув у твій акаунт «Сільпо»…», enrichment summary, the capability reveal (#70), the calendar offer (#71, «Підключити / Пізніше»).
- [ ] **Step 3: Check** — `logs/app.log` shows `silpo_get_my_family`, `silpo_get_my_food_restrictions`, `silpo_get_my_favorites`, `silpo_get_my_online_orders`; `SELECT capability_reveal_sent_at, cooking_time_preference, weekly_budget FROM user_profile` filled.
- [ ] **Step 4:** Any copy or flow defect → fix, commit, restart, redo. Record the final input and reply text in `docs/superpowers/plans/session-25-notes.md` (scratch file, not committed) for the operational script.

### Task 5: Beat 3 — plan, list, price, budget warning

- [ ] **Step 1:** Wait for the plan («Що беремо на цей тиждень?»). Tap «📝 Список». Expect categories, «Всього N позицій», «Орієнтовно ~X грн», and — with a budget under the estimate — the #66 line «на … грн більше». If the budget from the form is above the estimate, set `weekly_budget` lower via SQL and press «Список» again to show the warning once; note which budget the recording should enter.
- [ ] **Step 2:** «що їмо в середу?» → Wednesday view (#30).
- [ ] **Step 3: Check** `SELECT count(*) FROM shopping_list_item WHERE user_id=…`. Fix/commit/restart if anything is off.

### Task 6: Beat 4 — cart build with console and RED wave, confirm

- [ ] **Step 1:** Open a terminal `tail -f logs/mcp-calls.log` (or note that `make demo` is the recording profile). `: > logs/mcp-calls.log`. Tap «Замовити».
- [ ] **Step 2:** Expect «Збираю кошик…», then the cart with line costs, slot, «Не знайшов: …», benefits block absent (account holds nothing), budget line if over, buttons «Підтвердити / Інший час / Скасувати». Watch `http://localhost:3000/d/komora-observability` — the «Rate за інструментом» bars rise.
- [ ] **Step 3:** «Інший час» → slot rows (#34) → pick one → «Підтвердити». Expect «Підтвердив… Перейти до оплати» with `checkoutWebLink`. Do **not** pay.
- [ ] **Step 4: Check** — `SELECT id,type,status,total,delivery_slot FROM customer_order ORDER BY created_at DESC LIMIT 1;` and after ≤30 s `curl -s localhost:8080/actuator/prometheus | grep komora_orders_gmv_uah`. Business B GMV moved. Fix/commit/restart if not.
- [ ] **Step 5:** Cross-check #76: if Silpo answered `timeslot.not_available`, the message carries «(попередній час уже зайняли — підібрав найближчий вільний)».

### Task 7: Beat 5 — check-in and reorder delta

- [ ] **Step 1:** Wait for the check-in prompt (`until grep -q "check-in prompt sent" …` on `logs/app.log`; interval 10 m — or `UPDATE users SET last_checkin_prompt_at = NULL …` per RUNBOOK «Redo the check-in cycle» to force it on the next sweep). Answer «молоко закінчилося, хліб є, яйця закінчуються».
- [ ] **Step 2:** «що треба докупити?» → delta cart: «Додано / Прибрано», line costs, «Разом», «Економія за акціями», top-up block if under ₴799. «Підтвердити».
- [ ] **Step 3: Check** `SELECT trigger_intent, edited_before_confirm, total, confirmed_at - requested_at FROM customer_order WHERE trigger_intent='REORDER' ORDER BY created_at DESC LIMIT 1;`; Business A «Дозамовлень без правок» and «Намір → замовлення» rows.

### Task 8: Beat 6 — chat-only intents, one after another

For each: send, wait, read the reply, run the check, fix/commit/restart if needed, then confirm the cart so the intent clock has a row.

| Input | Expect | Check |
|---|---|---|
| «голова після вчорашнього, привезіть мінералку і щось від інтоксикації якнайшвидше» | 3-line kit (Миргородська ×2, ізотонік, Атоксіл) ≈ ₴215 goods + top-up, fastest slot | `trigger_intent='HANGOVER_RELIEF'`; goods_total < 400 |
| «замов усе для гречаної каші на молоці» | ingredients incl. milk → «Премія», buckwheat → «Ситий двір» | `SELECT event_type,occurred_at FROM partner_promotion_event ORDER BY occurred_at DESC LIMIT 6` shows IMPRESSION + ADDED_TO_CART for both placements; CONFIRMED_ORDER after confirm |
| «світло вимкнули» | no-cook/no-fridge cart (#73), no flour top-up | `trigger_intent='BLACKOUT'` |
| «цей тиждень нема часу готувати, запара на роботі» | «До DD місяця готую як для тих, хто не готує» (#67), plan regenerated as ready meals | `SELECT special_mode, special_mode_expires_at, cooking_time_preference FROM user_profile` — preference unchanged |
| «вже не запара, повертай як було» | revert message | `special_mode` NULL |
| «я захворів, гастрит, два тижні дієтичного раціону» | gastritis mode with an end date (#25) | `special_mode='MEDICAL_GASTRITIS'` |
| «я в порядку, повертай звичайний раціон» | back to normal | `special_mode` NULL |

If the placements are missing locally, seed them per RUNBOOK §21 before the dish line (`SELECT partner_name, category_or_query, status FROM partner_promotion`).

### Task 9: Beat 7 — scheduled one-off purchase

- [ ] **Step 1:** «замов до п'ятниці вино та сир зі знижкою». Expect «Зроблю це найближчим часом: …» (or a dated line). Tap «📅 Заплановані» → the row.
- [ ] **Step 2:** For the demo the sweep is every minute: expect within ~90 s «На «…» беру: …» → cart with «Економія за акціями». Confirm.
- [ ] **Step 3: Check** `SELECT theme_description,status,trigger_at FROM scheduled_ad_hoc_task ORDER BY created_at DESC LIMIT 1;` = DONE; `trigger_intent='AD_HOC_SCHEDULED_PURCHASE'`.

### Task 10: Beat 8 — group round with two accounts

- [ ] **Step 1:** [Акаунт A] open group «Комора — тест напоїв» (bot is admin there). Send «@baton_stepanovych_bot збери напої на п'ятницю, бюджет 2000». Expect greeting + «✅ Всі відповіли».
- [ ] **Step 2:** [Акаунт A] reply to the greeting: «червоне вино сухе». Switch account. [Акаунт B] reply to the greeting: «пиво світле, це на ДР». Expect «Записав, …. Відповіли: 2.» each.
- [ ] **Step 3:** [Акаунт B] tap «Всі відповіли» → toast «Це кнопка організатора». [Акаунт A] tap it → «Закрив список: 2 людей…», then «Пропозиція №1 на 2 людей» with real prices and one «👍 Погоджуюсь».
- [ ] **Step 4:** [Акаунт B] reply to the proposal «менше пива, більше вина» → «Пропозиція №2», approvals reset. [Акаунт A] 👍, [Акаунт B] 👍 → «✅ Усі 2 погодились. Поклав у кошик «Сільпо» @A…». [Акаунт A] private chat: cart → «Підтвердити» → group «🎉 … підтвердив».
- [ ] **Step 5: Check** `SELECT status, proposal_version, budget FROM group_event ORDER BY created_at DESC LIMIT 1;` = ORDERED/2; Business A2 tiles move. Fix/commit/restart on any dead end.

### Task 11: Beat 9 — gift, path (c), two accounts

- [ ] **Step 1:** Make sure B has spoken to the bot once (`/start` from [Акаунт B]) and B's `telegram_username` is stored: `SELECT telegram_chat_id, telegram_username FROM users;`.
- [ ] **Step 2:** [Акаунт A]: «відправ подарунок @<B username>, щось до кави». Expect A: «Запитав у @B адресу…»; B: «🎁 @A хоче надіслати тобі подарунок… Куди привезти?».
- [ ] **Step 3:** [Акаунт B]: «Київ, вулиця Хрещатик 22, кв. 42, +380671234567». Expect B «Записав, дякую»; A «Адресу для @B маю — збираю кошик» → «🎁 Подарунок для друга» cart with no address. [Акаунт A] «Підтвердити».
- [ ] **Step 4: Check** `SELECT status,resolution,recipient_username,gift_address_text,gift_flat,gift_phone FROM gift_order ORDER BY created_at DESC LIMIT 1;`; log order `silpo_find_address → … → silpo_update_shopping_cart → silpo_find_products_batch`.
- [ ] **Step 5: Restore check (cross-feature)** — [Акаунт A] «що треба докупити?» → `logs/app.log` carries `restoring the household's own delivery on cart … : true`; the gift row keeps status CONFIRMED (custody is released, not the order); the reorder cart's `silpo_get_shopping_cart_by_id` read-back shows the household's own street, not Хрещатик.

### Task 12: Beat 10 — metrics that prove value (read-back)

- [ ] **Step 1:** Read every number the script will quote:
```sql
SELECT trigger_intent, count(*), floor(extract(epoch FROM percentile_cont(0.5) WITHIN GROUP (ORDER BY confirmed_at - requested_at))) AS median_s
FROM customer_order WHERE status='CONFIRMED' AND trigger_intent IS NOT NULL AND requested_at IS NOT NULL GROUP BY 1;
SELECT count(*) confirmed, sum(total) gmv, round(avg(total),2) avg_cart FROM customer_order WHERE status='CONFIRMED' AND total IS NOT NULL;
SELECT count(*) FILTER (WHERE edited_before_confirm=false) unedited, count(*) FILTER (WHERE edited_before_confirm IS NOT NULL) reorders FROM customer_order WHERE status='CONFIRMED';
SELECT count(DISTINCT tool_name) tools, count(*) calls FROM mcp_tool_call;
```
plus `make promotions` and `make metrics`.
- [ ] **Step 2:** Compare with `curl -s localhost:8080/actuator/prometheus | grep -E 'komora_(intent_order_median|orders_gmv|group_rounds|gift_orders)'` — identical.
- [ ] **Step 3:** Screenshot Business and Technical locally; note which panels the script cuts to and in what order.

### Task 13: Beat 11 — technical words check

- [ ] **Step 1:** `make demo` profile: `PROFILE=demo scripts/restart-app.sh`; send «замов молока» → one 🧠 and a handful of 🔧 lines, no stack trace, `wc -L logs/mcp-calls.log` < 120. Restart with the normal profile after.
- [ ] **Step 2:** Confirm `/pitch.html` serves locally and on prod (`curl -s -o /dev/null -w '%{http_code}' https://89.167.115.28.sslip.io/pitch.html` = 200).

### Task 14: Update «Сценарій демо-запису» in place (Notion)

- [ ] Keep the acts; add a cross-link line at the top to the new operational page; rewrite the statuses of steps 1–13.8 from this pass (date 2026-09-10/11, session 25); add the new beats (social B = gift, metrics beat, technical-words beat, opening beat with the name/market context); changelog bullet at the bottom. Prod names only.

### Task 15: Create «🎬 Операційний сценарій запису» (Notion, under the project page)

- [ ] Sections in order: how to read this page + cross-link; pre-flight checklist (prod set-up per beat: placements seed, `/setprivacy`, timers, `make demo`, OBS windows, QR codes); then one table row per step with columns **Таймкод · Дія · Озвучка · Що показати · Продакшн-нотатки**, beats 0–12 from the spec; intro and close marked «🎞 АНІМАЦІЯ — без бота»; Claude Design prompt for the intro frame; DB-proof sub-steps as code blocks with the exact SQL and the expected row; the «Метрики, що доводять цінність» step with the numbers from Task 12; the «Пара слів про технічну реалізацію» step naming the four primitives and the viral callback; every social step labelled [Акаунт A]/[Акаунт B]. Spot-check: the page text contains none of «Батон», `lhr.life`, `ngrok`, `localhost`.

### Task 16: Sync dashboards to Grafana Cloud once

- [ ] `make dashboard` → two `pushed: …` lines. Open both hosted URLs with `env=prod` and confirm the new A2 row exists (zeros on prod are expected until the owner drives a group round there — say so in the script's set-up notes).

### Task 17: Dry run of the operational script

- [ ] Read the operational page top to bottom and replay every interactive Дія against the local bot in order (fresh profile again, Task 4 Step 1). Any Дія that does not produce the written reply → fix the code (commit) or the script, whichever is wrong. Record the outcome in the page's «Сухий прогін» footer with the date.

### Task 18: Summary, RUNBOOK, Notion status, parking lot, commit, push

- [ ] `docs/OVERNIGHT_SUMMARY.md` → `# Session 25 — the recording pass (task 80)`: what was walked, every fix with its commit, numbers, what needs the owner's eyes (payment click, prod group round, prod placements seed).
- [ ] `docs/RUNBOOK.md` → `### Session 25: the recording pass` live list.
- [ ] «Selling Points» → append «Ідеї на потім (parking lot, сесія 25)» with anything that did not fit, plus the new social numbers.
- [ ] Notion task 80 → Status «In review» + a dated write-up section at the bottom of its page.
- [ ] `make format && scripts/stop-app.sh && ./gradlew build` green; `git checkout main && git merge --no-ff feature/e2e-recording-pass && git push origin main` (deploys via Watchtower). `scripts/restore-supervisor.sh` at the very end.
