# Комора — manual testing runbook

A step-by-step path from an empty database to every feature in the product, in the order the features
depend on each other. Each step says **what to send**, **what you should see**, and **how to verify it
in the data** — because a bot that answers politely and writes nothing is the failure mode worth
catching.

Work through it in order the first time. After that, the [Cleanup](#cleanup) section resets you to any
starting point you want.

---

## 0. What you need before starting

| Thing | Needed for | Where to get it |
|---|---|---|
| Docker | everything (Postgres, tests) | must be running |
| JDK 25 | nothing manual — Gradle provisions it | — |
| A Telegram bot token | everything | [@BotFather](https://t.me/BotFather) → `/newbot` |
| An HTTPS tunnel | everything | `ngrok http 8080`, or Cloudflare Tunnel |
| `ANTHROPIC_API_KEY` | meal plans, check-in parsing, fridge photos | [console.anthropic.com](https://console.anthropic.com/settings/keys) |
| A Silpo account | carts, orders, reorders | your own; connected through the bot |
| `RESPEECHER_API_KEY` | spoken replies only (step 14a) | [space.respeecher.com](https://space.respeecher.com) playground |
| `STT_API_KEY` | voice check-ins only | OpenAI, or any OpenAI-compatible endpoint (Groq) |
| Google OAuth client | calendar events only | [console.cloud.google.com](https://console.cloud.google.com/apis/credentials) |

Everything except the bot token is optional. Features whose key is missing say so and get out of the
way — that behaviour is itself worth testing, and step 9 does.

### Set up the environment file

```bash
cp .env.example .env
```

Fill in, at minimum:

```bash
TELEGRAM_BOT_TOKEN=123456:AA...            # from BotFather
TELEGRAM_WEBHOOK_URL=https://<your-tunnel>.ngrok-free.app/telegram/webhook
TELEGRAM_WEBHOOK_SECRET=$(openssl rand -hex 32)
SILPO_TOKEN_ENCRYPTION_KEY=$(openssl rand -base64 32)
ANTHROPIC_API_KEY=sk-ant-...
```

> **Set the encryption key.** Without it the app generates an ephemeral one at startup, and every
> stored Silpo token becomes unreadable the moment you restart — which looks exactly like a broken
> login three steps later.

Optional, add when you reach the steps that use them:

```bash
STT_API_KEY=sk-...                          # step 10
GOOGLE_CLIENT_ID=...apps.googleusercontent.com   # step 13
GOOGLE_CLIENT_SECRET=GOCSPX-...
```

### Speed the clock up for testing

The check-in cycle is three days wide by default. For a test session, put these in `.env` too:

```bash
CHECKIN_INTERVAL=2m                 # ask again 2 minutes after the last contact
CHECKIN_SWEEP_CRON=0 * * * * *      # look for someone to ask every minute
CHECKIN_REMOVAL_THRESHOLD=2         # "never eaten" after 2 check-ins instead of 3
```

Remember you did this. A two-minute check-in interval is delightful for testing and unbearable in real
life.

---

## 1. Start everything

Three terminals.

**Terminal 1 — the tunnel:**

```bash
ngrok http 8080
```

Copy the `https://...` URL into `TELEGRAM_WEBHOOK_URL` in `.env`, keeping the `/telegram/webhook`
suffix. The URL changes every time ngrok restarts on the free plan; when it does, update `.env` and
restart the app.

**Terminal 2 — the app:**

```bash
make run
```

`make run` loads `.env` itself, inside its own throwaway subshell, and starts Postgres through
docker-compose automatically. It deliberately does **not** ask you to `source .env` in your own
terminal first — an exported secret lives in a shell for as long as that shell stays open, and
anything else you later launch from the same terminal (an editor, a background tool, another
script) inherits it. That is exactly how a real API key ended up loaded into an unrelated
background process and ran up a bill nobody could explain. `make dev` is the alternative — a
throwaway Testcontainers database that vanishes on exit, which is the fastest possible clean slate.

**Terminal 3 — the database**, kept open for verification:

```bash
docker exec -it app-db psql -U app -d app
```

### Did it start correctly?

```bash
curl -s localhost:8080/actuator/health
# {"status":"UP",...}
```

In the app log, look for:

- `Registered the Telegram webhook at https://...` — the tunnel is reachable and Telegram accepted it.
- Liquibase applying 16 changesets on the first run.
- `ANTHROPIC_API_KEY is not set` / `STT_API_KEY is not set` — warnings, not errors. They tell you which
  optional features will decline politely.

If the webhook line is missing, Telegram could not reach your tunnel. Check it by hand:

```bash
curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getWebhookInfo" | jq
# "last_error_message" is the honest answer to what went wrong
```

---

## 2. Onboarding — the simplest possible thing

**Send the bot:** `привіт` (any text works — the first message from an unknown chat starts onboarding).

**Expect:** a greeting with two buttons — «Під'єднати Сільпо» and «Пропустити».

**Verify:**

```sql
SELECT telegram_chat_id, created_at FROM users;
SELECT telegram_chat_id, current_flow, current_step FROM conversation_state;
-- ONBOARDING / AWAITING_CONNECT
```

This one step proves the whole spine: tunnel → webhook → routing → conversation state → outbound
message. If it works, everything else is a variation on it.

### 2a. The state really is in the database

Restart the app (`Ctrl+C`, `make run` again), then send `2`. The bot continues where it left off rather
than greeting you again — Telegram delivers every message as an independent request, and the
conversation lives in `conversation_state`, not in memory.

---

## 3. Connect Silpo

**Tap** «Під'єднати Сільпо». Your browser opens Silpo's login. Sign in and approve.

**Expect:** a page saying «Акаунт «Сільпо» підключено», then in Telegram either «Ось що знайшов…» with
detected household details, or «Нічого не знайшов у профілі «Сільпо». Запитаю сам.» Both are correct —
it depends on what your Silpo profile actually holds.

**Verify:**

```sql
SELECT user_id, length(access_token) AS ciphertext_len, expires_at FROM mcp_oauth_token;
```

The token column is AES-GCM ciphertext — a base64 blob, never a readable JWT. If it looks like a JWT,
something is very wrong; stop and say so.

> To test the skip path instead, tap «Пропустити» and the bot asks everything itself. You can come back
> and connect Silpo later, but the cart steps (6 onward) need it.

---

## 4. Finish the profile

The primary path is the WebApp form («Заповнити анкету») — one screen, and since task 38 its first section
is «Як у тебе з готуванням?», because that answer picks the planner (recipes vs ready meals). Tap
«Заповнити вручну» instead to walk the chat fallback, which asks the same question first, as buttons:

| Question | Send | Note |
|---|---|---|
| «Спершу головне: як у тебе з готуванням?» | tap one of the three buttons | typed text re-shows the buttons, it is not parsed |
| «Скільки вас удома?» | `2` | «двоє» works too — Ukrainian numerals are parsed |
| «Є алергії чи дієтичні обмеження?» | `нема` | or `лактоза, горіхи` |
| «Що вдома точно не їдять?» | `печінка` | or `нема` |
| «Який бюджет на тиждень, у гривнях?» | `2500` | |

**Expect:** «Записав. Готую перший план на тиждень.» — and, under the text box, the persistent keyboard:
📝 Список / 🗓 Заплановані, 🧾 Анкета / ❓ Інструкція, and 💬 Фідбек on its own row (task 47). It stays
there for the rest of the chat.
Every `/command` this runbook tells you to **Send** from here on still works if typed; the buttons and
plain sentences (see Task 31 below) are the intended way in.

**Verify:**

```sql
SELECT household_size, cooking_time_preference, dietary_restrictions, disliked_foods, weekly_budget
FROM user_profile;                              -- cooking_time_preference is never NULL after task 38
SELECT current_flow FROM conversation_state;   -- NONE
```

### Task 38: verify the cooking-time question comes first

- [ ] Open «Заповнити анкету» on a phone: the first section is «Як у тебе з готуванням?» with three chips,
      «Готую потроху щодня» pre-selected; the rest of the form is unchanged below it.
- [ ] Tap 🧾 Анкета after onboarding: the saved choice is pre-selected in that first section.
- [ ] Chat fallback: «Заповнити вручну» → three buttons; type `готую щодня` → the buttons come back
      unchanged; tap «Не готую — лише готова їжа» → «Скільки вас удома?».
- [ ] Finish the fallback and check `cooking_time_preference = 'READY_MEALS_ONLY'`; the first plan is the
      ready-meals one (product names, no recipes — see «READY_MEALS_ONLY» under section 6).

**Try the error path too:** answer the household question with `багато`. The bot should re-ask rather
than store nonsense.

---

## 4a. Build a list from a photo, a receipt, or a sentence

The fastest way to see the product work, and the one that does not depend on the weekly planner.

**Send:** `/list`

**Expect:** an offer of three ways in — a photo of your fridge, a photo of a receipt, or a description.

**Then send any one of:**

- a photo of an open fridge or a shelf,
- a photo of a supermarket receipt,
- a sentence: `звичайна їжа на тиждень, без молочки`.

**Expect:** a list of twelve to twenty-five items with quantities, and three buttons — «Замовити»,
«Змінити», «Скасувати».

**Verify nothing was ordered yet** — this gate is the point of the step:

```sql
SELECT name, quantity, unit FROM shopping_list_item WHERE meal_plan_id IS NULL ORDER BY name;
SELECT count(*) FROM customer_order;   -- 0
```

**Editing is a sentence, not a keyboard.** Tap «Змінити» (or just type) and say
`прибери банани, додай хліб і яйця`. The list is rebuilt and shown again; the old one is replaced, not
added to.

**Then tap «Замовити»** — from here it is the ordinary cart confirmation of step 6 and 7.

## 5. The weekly plan and the shopping list

Needs `ANTHROPIC_API_KEY`. This happens automatically, seconds after step 4 — plan generation is a long
call and runs off the webhook thread.

**Expect:** «План на тиждень готовий, 7 днів. Понеділок: … Список покупок: N позицій.» — and then the list
itself, grouped by category, ending in «Всього N позицій.» and, when anything could be priced, «Орієнтовно
~X грн — точну суму покажу в кошику.» (task 39).

### Task 39: verify the price estimate on the list

- [ ] `READY_MEALS_ONLY` profile: both the plan summary and the list carry «Орієнтовно ~X грн» right away —
      every line has a catalog price. `SELECT name, estimated_price FROM shopping_list_item WHERE status='ACTIVE'`
      is non-null on every row. If it is null everywhere, Silpo's search response carried no `price` field for
      this account — say so, the stub can't prove that either way.
- [ ] Cooking profile, first week: **no** «Орієнтовно» line (nothing to price from yet — honest, not a bug).
- [ ] Cooking profile after the first confirmed order: tap «Список» (or regenerate) — the line appears, with
      «за K з N позицій» when the new list has items the baseline never had.
- [ ] The number is in the same ballpark as the cart's «Разом» that follows «Замовити».

**Verify:**

```sql
SELECT week_start_date, jsonb_array_length(plan_json->'days') AS days FROM meal_plan;
SELECT name, quantity, unit FROM shopping_list_item ORDER BY name;
```

`days` must be 7. The shopping list is the *collapsed* form: an onion named in four meals is one line
with the total, not four lines.

**If it says «План скласти не вдалось»:** the API key is missing or invalid. Check the log for the
Claude error; the message is deliberate rather than a silent failure.

---

## 6. The first cart

Immediately after the plan, the agent builds a real Silpo cart. This is the six-call MCP sequence, and
the log is the best part — watch terminal 2:

```
MCP -> silpo_get_my_shopping_cart {}
MCP <- cart ... branch ... company ... delivery ...
MCP -> silpo_get_time_slots {...}
MCP -> silpo_find_products_batch {...}
MCP -> silpo_add_or_update_cart_products {...}
MCP -> silpo_get_shopping_cart_by_id {...}
MCP <- cart ... verified: N items, total ...
```

**Expect** in Telegram: an itemised cart with quantities and prices, a total, possibly «Не знайшов: …»
for items Silpo has no match for, and buttons — «Підтвердити», «Скасувати», and «Підтвердити + N
бонусів» when your Silpo account actually has bonuses.

**Verify before tapping anything:**

```sql
SELECT id, type, status, silpo_cart_id, delivery_slot, jsonb_array_length(items_json) AS lines
FROM customer_order;
-- INITIAL / DRAFT
```

The draft exists *before* you answer. That is what makes a double tap safe.

### READY_MEALS_ONLY: verify the search-first fix (task 22)

For a household with `cookingTimePreference = READY_MEALS_ONLY`, the generated plan's every ingredient
must already carry a real `productId` — check `meal_plan.plan_json` directly:

```bash
docker exec -i app-db psql -U app -d app <<'SQL'
SELECT plan_json -> 'days' -> 0 -> 'meals' -> 0 -> 'ingredients' -> 0 ->> 'productId'
FROM meal_plan WHERE user_id = (SELECT id FROM users WHERE telegram_chat_id = <CHAT_ID>)
ORDER BY created_at DESC LIMIT 1;
SQL
```

A non-null value here, followed by a cart that actually builds with 0 `unresolved` (see
`CartSummary.unresolved()` / the bot's own cart message), confirms the fix. The log line
`Silpo matched no product for N of M items` should read `0 of M` — the exact line the original bug
report quoted at `16 of 16`.

---

## 7. Confirm, and the baseline

**Tap** «Підтвердити».

**Expect:** «Підтвердив. Зберіг цей кошик як еталонний набір…» plus a checkout button. Payment is on
Silpo's own page — the agent never pretends to have paid.

**Verify:**

```sql
SELECT type, status, confirmed_at FROM customer_order;          -- CONFIRMED
SELECT is_current, jsonb_array_length(items_json) AS lines FROM baseline_basket;  -- one row, true
SELECT current_flow FROM conversation_state;                     -- NONE
```

**Now tap «Підтвердити» a second time** on the same message. Nothing should change — no second order,
no second baseline, no new message. Re-run the two queries above to prove it.

```sql
SELECT count(*) FROM customer_order WHERE status = 'CONFIRMED';  -- still 1
SELECT count(*) FROM baseline_basket;                            -- still 1
```

> **Cancel path:** to test it instead, run through steps 5–6 again (see [Cleanup → Redo the first
> order](#redo-the-first-order)) and tap «Скасувати». The order goes to `CANCELLED` and no baseline row
> appears.

### Task 27: verify the Silpo-brand redesign live, and cross-check the color

`--silpo-primary` (`#FF8200`) is verified from the logo SVG only — the live `silpo.ua` site's own computed
CSS could not be reached this session (Cloudflare bot challenge blocked `curl`; the Chrome browser tool
was disconnected all night). Before treating this redesign as final:

1. Open `silpo.ua` in a real browser, inspect a primary button/highlight with devtools, and confirm its
   computed color matches (or note the discrepancy — the task's own instructions say prefer the live shop
   app's color if the two disagree).
2. Open the onboarding form (`/webapp/onboarding.html`) in Telegram, at a narrow (~360px) width, in both
   light and dark Telegram themes — same check as task 23, now with the chips/segmented controls added.
3. Confirm the chips (restrictions) and segmented diet-type control read clearly against both themes —
   `--silpo-primary` is a fixed orange regardless of theme, so check it doesn't clash in dark mode.
4. Run a full onboarding through the form and confirm the submitted profile still gets the same fields as
   before this redesign (the JSON payload shape is unchanged by inspection, but this is the real proof).
5. Take before/after screenshots for the PR/demo record.

### Task 28: verify the checkout link actually completes a real purchase

This is the one step in this runbook that cannot be automated — it needs a real Silpo guest account, a
phone with Telegram installed, and ends in an actual payment. Do this once per significant change to
`CartMessageService`/`CartBuildingService`'s checkout-link handling, and definitely once before any
hackathon jury demo.

1. Run the full flow above (steps 1-7) with a real, OAuth-connected Silpo guest account through to the
   confirmed-order message.
2. **Confirm the button, not the text.** The confirmation message must show one tappable inline button
   labeled "Перейти до оплати" — not raw URL text. Tap it.
3. Record your screen from this tap onward (any screen recorder — this becomes hackathon demo evidence,
   referenced from the "Сценарій демо-запису" Notion page).
4. Confirm, while recording:
   - The tap opens the Silpo app directly (or its checkout page) — not a dead link, not a 404.
   - The cart shown on Silpo's side has the same items and total the bot's own confirmation message had.
   - No re-login/re-auth prompt appears — the guest's session carries through. If one does appear, this
     is exactly the friction task 28 calls out; document it in the recording's description and decide
     with the team whether it's fixable or an inherent platform constraint (Telegram's Bot API has no
     server-side control over in-app vs. system browser, so `checkoutMobileLink` is the intended
     workaround — if friction still appears even via the mobile link, that's new information worth
     escalating, not something to silently route around again).
   - Complete a real payment for a small real order.
5. Save the recording somewhere durable and link it from the "Сценарій демо-запису" Notion page and from
   Notion task 28 itself, then mark task 28's acceptance criteria checked off there.

### Task 32: verify the real Silpo catalog actually has rehydration/sorbent products

`AdHocOrderService.buildHangoverReliefOrder` searches for "вода мінеральна", "електроліти", "регідрон",
"ізотонік", "сорбент", "активоване вугілля", "ентеросгель" — a guess at what a grocery retailer's catalog
taxonomy might expose, not a verified one. Before this feature is demo-ready:

1. With a real, OAuth-connected Silpo guest account, trigger it live: «голова після вчорашнього, привезіть
   мінералку і щось від інтоксикації якнайшвидше».
2. Check the resulting cart against `docs/RUNBOOK.md`'s own MCP console log — how many of the 7 terms
   actually resolved to a real product? If most come back empty, the search terms need adjusting to match
   Silpo's real category names, not this list's guesses.
3. Confirm the "Не знайшов: ..." line only appears for genuinely-missing terms, and the delivery slot
   picked really is the earliest one offered (compare against `silpo_get_time_slots`'s raw response in the
   console log).

### Task 31: verify free-text intents live in Telegram

The integration tests script every intent against a stubbed classifier response — they prove the dispatch
logic is correct, not that Claude's real, live classification actually picks the right intent for a real,
possibly-ambiguous Ukrainian sentence. That needs a human reading the bot's actual reply. For each row
below, type the phrase into a real chat with an onboarded profile and confirm the "expected" outcome:

| Type this | Expect |
|---|---|
| «закажи до п'ятниці вино та сир по знижці» | "Зроблю це найближчим часом: ..." confirmation; no cart yet. The mentioned date is a deadline, not a trigger time — this fires on the next sweep, not near Friday (see docs/OVERNIGHT_QUESTIONS.md's follow-up entry, caught in live testing) |
| «я захворів, гастрит» | "Перемикаю на щадне харчування" + a new plan |
| «зроби менш калорійним» | A new plan, without switching to a named special mode |
| «хочу набрати масу» / «більше протеїну» | A cross-sell line about protein/gainer, then "Яка зараз вага?" |
| «шукай тільки український виробник» | A confirmation the UA-only filter is on |
| «голова після вчорашнього, привезіть мінералку» | A small cart, rehydration/sorbent items only |
| «покажи список» | The current shopping list |
| «що ти вмієш?» | The static Інструкція text |
| something genuinely ambiguous, e.g. «зроби щось» | A clarifying question, not a guess |
| «/blackout» (typed, not tapped) | Still works exactly as before — no classification call happens |
| «світло вимкнули, немає струму» | A small cart, no-cooking-needed items only (task 29's chat-only blackout) |
| «що треба докупити?» | "Дивлюсь, що треба докупити." then a delta reorder (or "Поки нічого докуповувати") — same as typed /reorder (task 43) |
| «я в порядку, повертай звичайний раціон» | Special mode ends and a normal plan is regenerated; with no mode active, "Звичайний режим і так активний" (task 43) |
| «прибери молоко зі списку, додай яйця» | The current list is edited straight away and shown for approval — no "Що беремо на цей тиждень?" question in between (task 43) |
| «підключи гугл календар» | The Google Calendar consent link (or "уже підключено" / "не налаштований на сервері") — same as typed /calendar (task 43) |
| a voice note saying any of the above, with STT_API_KEY set | Transcribed and handled exactly like the typed sentence; without the key, "Голосові поки не розбираю" (task 43) |
| a photo of a fridge or shelf, with no conversation open | "Хвилинку, складаю список." then a list built from the photo, shown for approval (task 43) |
| «/start» after onboarding | "Я тут…" plus the keyboard; no clarifying question, no model call (task 43) |

Also confirm the persistent keyboard shows exactly five buttons in three rows (Список / Заплановані, then
Анкета / Інструкція, then Фідбек alone — task 33 added the fourth, task 45 split the rows, task 47 added
the fifth) after onboarding finishes, that
tapping «📝 Список» while a list is on screen shows that list again (not the "Що беремо на цей тиждень?"
question — task 45), and:

| Type this | Expect |
|---|---|
| Tap «🧾 Анкета» after onboarding | The WebApp form opens with every field already filled in — adults, kids' age brackets, allergy chips, diet-type and cooking-time selection, and the weekly budget — matching what onboarding originally collected |
| Submit that form unchanged | "Змін немає — залишаю все як є.", no confirm buttons, list untouched |
| Submit it with one answer changed | Profile updates immediately, then "Оновити поточний список під нові відповіді?" with Так/Ні buttons |
| Tap «Так, оновити» | A new plan and list are generated and presented for approval |
| Tap «Ні, залишити» | "Гаразд, залишаю поточний список.", nothing regenerated |

(the Анкета-reopen flow, criterion 7 of task 31, was implemented per
`docs/superpowers/plans/2026-09-05-profile-reedit.md`; the prefill's *browser-side* field population has no
automated test — see that plan's Task 5 — so this table is the only verification of it).

### Task 35: seed the list from a real past order

Needs a connected Silpo account with at least one past order.

| Say / do | Expect |
|---|---|
| «зроби список як минулого разу» | «Ось твої останні замовлення в «Сільпо». Яке взяти за основу?» with up to five buttons — «1 вер · 12 позицій · 1234.50 грн», in-store ones marked «магазин» — plus «Скасувати» |
| Tap one | «Взяв за основу замовлення … — N позицій, ті самі товари, що й тоді.» then the list, with «Орієнтовно ~X грн» (prices come from the order) |
| Compare the list with the order in the Silpo app | Item for item, same quantities. Anything missing → note the real JSON: `SELECT ... FROM mcp_tool_call` won't have it, so grep `logs/app.log` for `silpo_get_my_online_orders answered:` |
| Tap «Замовити» | The cart builds **without** a `silpo_find_products_batch` call (check the log: only cart/slot/add calls) |
| If the buttons show «Замовлення» with no date/count, or a tap says «без переліку позицій» | Silpo's listing tool returned a shape the key guesses in `McpResponses` (`ORDERS`, `ORDER_ID`, `ORDER_DATE`, `ITEMS`) do not cover, or summaries without lines — paste the logged JSON into `docs/OVERNIGHT_QUESTIONS.md` and the fix is one key array |

### Task 36: order the ingredients for a dish

| Say / do | Expect |
|---|---|
| «замов усе для карбонари» | «Зберу все для «карбонара» — секунду.» → «Інгредієнти для «карбонара» на N порцій — збираю кошик.» → the usual cart with Підтвердити, and «Не знайшов: …» for anything the catalog lacks |
| Tap «🗓 Заплановані» | «Нещодавно виконав: ✅ інгредієнти для «карбонара»» |
| Confirm the cart | «Еталонний набір лишаю як був.» — AD_HOC, baseline untouched |
| Send a photo of a plated dish **with the caption** «замов все для цього» | «Схоже на «X». Замовляти інгредієнти для неї?» with Так/Ні — the vision guess is the thing to check here |
| Same photo, no caption | The list builder as before (it reads it as a fridge/receipt) — expected |
| «хочу щось приготувати, замов інгредієнти» | «Яку страву готуємо?» → type a name → same as row 1 |

Watch `logs/app.log` for `Страва: …` / `Порцій: …` in the ingredients prompt and confirm quantities are
shop-sized (a pack of pasta, a dozen eggs), not recipe grams.

### Task 37: pull the pitch numbers after a rehearsal

Set `METRICS_TOKEN` in `.env` (`openssl rand -hex 16`), restart, walk the demo once (onboarding → first
confirmed order → a check-in answer → a reorder), then:

```bash
make metrics
```

You get a markdown table: onboarding→first order (median, fastest), check-in response rate, reorders
confirmed unedited, catalog resolve rate per cart, distinct MCP tools of 39 — each with its sample size.
Paste it into the Notion «Selling Points» table. Rows are only as real as the run: a fresh DB prints
dashes, not zeros dressed up as results.

```sql
SELECT tool_name, count(*) FROM mcp_tool_call GROUP BY tool_name ORDER BY 2 DESC;
```

### Task 46: paid partner placement, end to end

Needs `METRICS_TOKEN` in `.env` and a connected user (yours). Find your user id:
`SELECT id FROM users WHERE telegram_chat_id = <your chat>;`

1. Create a placement for a category your list will contain — the product is verified against the
   catalog through your session, and the response shows the real id and name that will be featured:

```bash
curl -s -X POST -H "X-Metrics-Token: $METRICS_TOKEN" -H "Content-Type: application/json" \
  localhost:8080/internal/promotions -d '{
    "partnerName": "Яготинське", "categoryOrQuery": "молоко",
    "productQuery": "Молоко Яготинське 2.5% 900г", "verifyAsUserId": "<your user id>" }'
```

2. Run a normal flow that needs milk — «зроби список», «замов усе для омлету», or the weekly plan — and tap
   «Замовити».

| Expect | Where |
|---|---|
| The cart line is the partner's product, marked «★», and the footer says «★ — партнерська пропозиція…» | the cart message |
| `partner placement … answered «молоко» with product …` | `logs/app.log` |
| `IMPRESSION` then `ADDED_TO_CART` rows | `SELECT event_type, occurred_at FROM partner_promotion_event ORDER BY occurred_at;` |
| After Підтвердити: a `CONFIRMED_ORDER` row | same query |
| `make promotions` → one table row with 1 / 1 / 1 and two «100 %» conversions | terminal |

3. Set «Лактоза» in the Анкета and rebuild the list: the ordinary milk comes back, no ★, no new events,
   and the log says `skipped … conflicts with the household's «lactose»`. The guard is keyword-based
   (product name / category vs. restriction stems) — not an allergen database; say so if asked.

4. Pause it: `UPDATE partner_promotion SET status='PAUSED';` — the next cart resolves normally.

### Tasks 56 and 57: verify «де моє замовлення» and the Замовлення button

The account must be connected to Silpo; everything here is read-only, so nothing in the database changes.

| Do | Expect |
|---|---|
| Type `де моє замовлення?` | One message: «Останнє замовлення: …» with the date, sum, and — only if Silpo sent them — a «Статус:» and a «Доставка:» line |
| Type `коли приїде доставка?` and `що там із замовленням` | The same facts as above; the three phrasings must not diverge |
| Tap «📦 Замовлення» | The same newest order plus «Раніше:» with the previous ones, and the note that the status is pulled on request |
| Type `зроби список як минулого разу` | Still the past-order **picker** (task 35), not the status view — the two intents must not swallow each other |
| Look at the keyboard | Three rows of two: Список/Замовлення, Заплановані/Анкета, Інструкція/Фідбек — no label truncated with «…» |
| Tap «📦 Замовлення» on an account with no orders | «Не бачу замовлень в акаунті «Сільпо» — ні активних, ні минулих.» — never silence |

```sql
-- Nothing may have been written by any of the above.
SELECT count(*) FROM conversation_state WHERE current_flow <> 'NONE';
```

In `logs/app.log` the whole interaction is two tool calls and no more:
`grep -c 'silpo_get_my_online_orders' logs/app.log` grows by exactly one per request.

### Task 47: verify the Фідбек button

| Do | Expect |
|---|---|
| Tap «💬 Фідбек» on an idle chat, type `кнопка Список не там` | «Що не так або що покращити?» with a «Скасувати» button, then «Дякую, врахуємо.» — and nothing else changes |
| Tap «Замовити» on a list, then «💬 Фідбек» mid-cart, type anything | «Дякую, врахуємо.», and the cart's Підтвердити/Скасувати buttons still work afterwards (state restored) |
| Tap «💬 Фідбек», then tap «📝 Список» instead of typing | The list shows; no feedback row is stored; the next sentence is a list edit, not feedback |
| Fresh chat, before connecting Silpo: type `/feedback`, then a sentence | Stored; the onboarding continues where it was («Під'єднати Сільпо» still works) |

```sql
SELECT telegram_chat_id, raw_text, source, created_at FROM feedback ORDER BY created_at DESC;
```

### Task 33: verify the Заплановані view end-to-end

The integration tests cover the dispatch logic against a stubbed Claude edit-slot response. Criterion 6
of task 33 is explicitly a live-chat check:

| Do this | Expect |
|---|---|
| Say «закажи до п'ятниці вино та сир по знижці», then tap «🗓 Заплановані» | The scheduled purchase appears with its theme and formatted trigger time, and Редагувати/Скасувати buttons |
| Tap «Редагувати», reply with a new time (e.g. «перенеси на суботу ввечері») | "Оновлено: ..." with the new time; tapping «🗓 Заплановані» again shows the updated time, not a second row |
| Tap «Редагувати» on another task, reply with a new theme | "Оновлено: ..." with the new theme, trigger time unchanged |
| Tap «Скасувати» on a pending task | "Скасовано: ...", and it never fires (wait past its original trigger time, or shorten the sweep interval per this runbook's demo-prep steps, and confirm no order shows up) |

### Task 34: verify the delivery-slot picker end-to-end

Task 34's Notion page was empty when this was built — see `docs/OVERNIGHT_QUESTIONS.md`'s "Task 34" entry
for the scoping reasoning. Nothing here has automated coverage of Silpo's *own* checkout page, only this
bot's side of the conversation:

| Do this | Expect |
|---|---|
| Get a cart to the confirmation step | The message now includes a "Доставка: ..." line naming a real time window |
| Tap «Інший час» | A menu of the other windows Silpo is currently offering for that branch |
| Pick a different one, then tap «Підтвердити» | Confirmation text unchanged in shape, but the delivery slot booked with Silpo is the one just picked — open the checkout link and confirm the window shown there matches |
| Tap «Підтвердити» without ever opening «Інший час» | Works exactly as before task 34 — no extra Silpo call, no behavior change |

---

## 8. The scheduled check-in

With `CHECKIN_INTERVAL=2m` and a per-minute sweep, wait about two minutes after the confirmation.

**Expect, unprompted:** «Як справи з їжею? Що вже закінчилось, а чого ще вистачає?»

**Verify:**

```sql
SELECT last_checkin_prompt_sent_at FROM users;
SELECT current_flow, current_step FROM conversation_state;   -- CHECK_IN / AWAITING_REPORT
```

**Check the anti-nag rule:** wait for the next sweep (a minute) without answering. No second prompt
arrives, because the prompt itself counts as contact. Only after another full interval does the agent
ask again.

In the log each sweep prints `check-in sweep: N of M eligible users prompted`.

---

## 9. Answer the check-in with text

**Send:** something loose and realistic, naming items from your baseline:

```
молоко ще є, хліба нема, гречка на межі
```

**Expect:** «Записав. Ще є: … Закінчується: … Немає: …» naming your baseline's own spellings, not
yours.

**Verify:**

```sql
SELECT source, raw_input_text, parsed_delta_json FROM checkin ORDER BY received_at DESC LIMIT 1;
SELECT item_name, consecutive_untouched_cycles FROM inventory_trend ORDER BY item_name;
SELECT current_flow FROM conversation_state;   -- NONE
```

`source` is `TEXT`. The trend counter went up for what you said you still have and to zero for what ran
out.

**Two failure paths worth testing deliberately:**

1. **Nonsense.** Trigger a new check-in (wait a cycle) and answer `ок`. The bot asks a clarifying
   question naming real items and stays in `CHECK_IN` — an empty answer must never be recorded as
   "everything unchanged". A `checkin` row is still written, with `parsed_delta_json` null.
2. **Invented items.** Say `трюфелі закінчились` when truffles are not in your baseline. They are
   dropped: the model is grounded on the baseline and the result is filtered again in code.

---

## 10. Answer with a voice note

Needs `STT_API_KEY`. Add it to `.env` and restart.

**Send:** a voice note during an open check-in, saying the same kind of thing in Ukrainian.

**Expect:** the same acknowledgement as step 9.

**Verify:**

```sql
SELECT source, raw_input_text FROM checkin ORDER BY received_at DESC LIMIT 1;   -- VOICE
```

`raw_input_text` is the transcript — read it, that is how you judge the transcription quality. The log
line is `transcribed N bytes of audio into M characters`.

**Test the unconfigured path first if you like:** with `STT_API_KEY` blank, a voice note is answered
«Голосові поки не розбираю. Напиши, будь ласка, текстом.» and nothing is stored. That is a supported
configuration, not a bug.

---

## 11. Answer with a fridge photo

Needs `ANTHROPIC_API_KEY` only.

**Send:** a photo of an open fridge or a shelf during an open check-in.

**Expect:** the same acknowledgement plus «Це приблизно — з фото видно не все. Якщо щось не так, просто
напиши.»

**Verify:**

```sql
SELECT source, raw_input_text, parsed_delta_json FROM checkin ORDER BY received_at DESC LIMIT 1;  -- PHOTO
```

**What good output looks like:** items visibly present land in `stillHave`; a visibly empty shelf lands
in `goneCompletely`; anything merely *not visible* lands nowhere. Try two photos — a full shelf and a
nearly empty one — and compare. That contrast is the demo.

> **A photo used to be the most expensive way to hit "budget wasted."** A vision call is the slowest
> and priciest call this app makes, and a slow synchronous webhook is exactly what makes Telegram
> redeliver an update — turning one photo into two (or more) full vision calls with no visible sign of
> it. Fixed: the webhook now answers instantly and redeliveries are ignored (`config/TelegramConfig`).
> If you send one photo and the log shows `MCP -> ...` or `Claude -> ...` lines for it more than once,
> that guard has a bug — say so.

---

## 12. Trends: what the household never eats

Repeat the check-in cycle **twice more** (with `CHECKIN_REMOVAL_THRESHOLD=2`, two consecutive
"still have" reports are enough), each time saying the *same* item is still there:

```
гречка ще є
```

**Verify:**

```sql
SELECT item_name, consecutive_untouched_cycles FROM inventory_trend ORDER BY 2 DESC;
```

Once an item is at or above the threshold, the next meal plan is told not to suggest it. To see that,
force a regeneration and read the prompt in the log — it carries a line «Не пропонуй ці продукти — їх
стабільно не їдять: …».

**And the reset rule:** say `гречки нема` on the next cycle. The counter goes to zero, because being
consumed is what breaks the streak.

---

## 13. The reorder — a delta, not a new week

**Send:** `/reorder`

There is deliberately no scheduler for this; the command is how a person or a demo starts the cycle.

**Expect:** «Дивлюсь, що треба докупити.» then either «Поки нічого докуповувати…» (if your last check-in
reported nothing missing — go say something ran out first) or a small order listing:

- only the items your last check-in said were low or gone,
- «Не беру, бо їх стабільно не їдять: …» for anything the trend counter flagged,
- a substitute question per unavailable item, each with its own «Взяти замість…» / «Без…» buttons,
- «На акціях економимо приблизно N грн» when promotions matched,
- a delivery slot, with an «Інший слот» button.

**Verify the delta is a delta** — in the log, the `silpo_find_products_batch` call must carry only the
needed items, not the whole weekly list.

```sql
SELECT type, status, delivery_slot FROM customer_order ORDER BY created_at DESC LIMIT 1;
-- SCHEDULED_REORDER / DRAFT
```

### 13a. Confirm with no edits — the baseline stays

**Tap** «Підтвердити» without touching any substitute buttons.

```sql
SELECT id, is_current, confirmed_at FROM baseline_basket ORDER BY confirmed_at;
-- still exactly one row, the same id as before
SELECT consecutive_unedited_confirmations FROM trust_level;   -- 1
```

### 13b. Confirm with an edit — the baseline moves

Run `/reorder` again, and this time tap «Без «X»» on a substitute before confirming.

```sql
SELECT id, is_current, confirmed_at FROM baseline_basket ORDER BY confirmed_at;
-- two rows now: the old one is_current = false, a new one is_current = true
SELECT consecutive_unedited_confirmations FROM trust_level;   -- back to 0
```

That is the rule this whole stage exists for: **an order you edited becomes the new normal; an order you
accepted as-is does not.** Choosing a different delivery slot is not an edit — it changes when the food
arrives, not what is in the basket. Test that too: run `/reorder`, tap «Інший слот», pick another, then
confirm. The trust counter still goes up.

---

## 14. Google Calendar

Needs a Google Cloud OAuth **Web application** client with
`http://localhost:8080/auth/google/callback` as an authorized redirect URI. Put the id and secret in
`.env` and restart.

**Send:** `/calendar` → tap «Підключити календар» → consent in the browser.

**Expect:** «Календар підключено. Доставки з'являтимуться там автоматично.»

**Verify:**

```sql
SELECT user_id, length(access_token) AS ciphertext_len, expires_at FROM google_oauth_token;
```

Separate table from Silpo's, same encryption. Now confirm any order (step 13) and look at your calendar:
a «Доставка «Сільпо»» block at the delivery slot, two hours long, with the order id in its description.

**Expect nothing to break without it:** send `/calendar` with no `GOOGLE_CLIENT_ID` configured and the
bot says «Календар зараз не налаштований на сервері.» Confirmations continue exactly as before.

---

## 14a. Spoken replies (Respeecher)

Needs `RESPEECHER_API_KEY` (Space API key from the Respeecher playground) and `ANTHROPIC_API_KEY` — the
message is rewritten for speech before it is synthesised.

> **Cost:** this combination — `/voice` on plus a fast `CHECKIN_INTERVAL` — is what actually burned a
> real API budget once, and the console showed why: input tokens outweighing output about 160 to 1, from
> a full system prompt repeated on trivial one-line confirmations nobody was reading. Two things soften
> it now — the rewrite runs on the cheap model (`ANTHROPIC_FAST_MODEL`), and a message with no digit, no
> link and no second line skips the rewrite entirely — but it is still a real call on anything that does
> need it. Turn `/voice` back off once you are done with this step, and do not leave a two-minute
> `CHECKIN_INTERVAL` running unattended for hours.

**Send:** `/voice`

**Expect:** «Тепер відповідатиму ще й голосом…» *and* an audio message saying roughly the same thing —
the confirmation is itself spoken, which is the fastest way to hear that it works.

**Verify:**

```sql
SELECT telegram_chat_id, voice_replies_enabled FROM users;   -- true
```

Now send anything that gets a plain reply — a check-in answer, for instance — and you should get text
plus audio. **What to listen for**, against Silpo's guidance: numbers spoken as words, no URL read
aloud, at most two items per sentence, short sentences.

A cart or any message with buttons stays text-only, deliberately.

**Send `/voice` again** to turn it off; the reply says «Вимкнув голосові відповіді.» and nothing is
spoken after that.

**Without the key:** `/voice` answers «Голосові відповіді зараз не налаштовані на сервері.» and nothing
changes — the same shape as every other optional integration here.

**If the audio arrives as a file rather than a playable bubble:** that is the documented fallback.
Respeecher returns WAV, Telegram's voice messages want OGG/Opus, so it is sent as audio and falls back
to a document.

## 15. Blackout mode

**Send:** `/blackout`

**Expect:** «Збираю щось на поїсти без плити й холодильника.» then a small cart — ready meals, tinned
fish, pâté, bread, nuts, biscuits, juice, water — with the same confirm/cancel buttons as any other
cart.

**Verify:**

```sql
SELECT type, status FROM customer_order ORDER BY created_at DESC LIMIT 1;   -- AD_HOC / DRAFT
```

Confirm it, then check the thing that matters:

```sql
SELECT count(*) FROM baseline_basket;   -- unchanged from before /blackout
```

An emergency lunch is explicitly not evidence about what the household normally eats.

**Judge the results:** read the item list. It should be genuinely no-cook food. If Silpo's catalogue
answers a query badly, the fix is the curated list in `BlackoutModeService`, not a smarter inference.

---

## 16. Metrics and the Grafana dashboard (task 54)

The app publishes Prometheus metrics at `/actuator/prometheus`. Grafana Alloy scrapes that locally and
pushes them outbound to Grafana Cloud — push, not pull, because a demo box behind a rotating tunnel has
no address anyone can scrape.

### Check the numbers are real, without any cloud account

```bash
make run
curl -s localhost:8080/actuator/prometheus | grep '^komora_' | sort
```

Absolute levels (`komora_users_registered`, `komora_orders_gmv_uah`, …) come from the database and are
rebuilt every 30 s, so they survive a restart — a counter would read zero after every `make run`, which
is exactly wrong for "GMV since launch". Rates and latencies (`komora_mcp_call_seconds`,
`komora_claude_call_seconds`, `komora_cart_*`) are recorded in-process and start empty on each boot;
drive a real session first or they will not be there at all.

> **A fresh database prints zeros, and zeros are not results.** `komora_orders_value_missing` is the
> honest companion to the GMV panel: it counts confirmed orders with no stored total — rows written
> before task 54 added the columns. Those are excluded from GMV rather than counted as ₴0.

### Cross-check GMV against the order table by hand

This is the number a judge can catch us on, so check it rather than trusting it. `items_json` holds
`price` and `quantity` per line, which reconstructs the basket independently of the stored column:

```sql
SELECT o.id,
       o.type,
       o.total                                                             AS stored_total,
       o.goods_total                                                       AS stored_goods,
       o.savings,
       ROUND(SUM((line->>'price')::numeric
                 * COALESCE((line->>'quantity')::numeric, 1)), 2)          AS lines_sum,
       jsonb_array_length(o.items_json)                                    AS line_count
FROM customer_order o
CROSS JOIN LATERAL jsonb_array_elements(o.items_json) AS line
WHERE o.status = 'CONFIRMED' AND o.total IS NOT NULL
GROUP BY o.id, o.type, o.total, o.goods_total, o.savings, o.items_json;

-- and the aggregate the GMV / average-cart panels must match, to the kopeck:
SELECT count(*)                                      AS confirmed,
       count(total)                                  AS with_a_total,
       SUM(total)                                    AS gmv,
       ROUND(AVG(total), 2)                          AS average_cart,
       ROUND(AVG(jsonb_array_length(items_json)), 2) AS average_lines
FROM customer_order
WHERE status = 'CONFIRMED' AND total IS NOT NULL;
```

`lines_sum` is **not** expected to equal `stored_total`: `total` carries the delivery fee, and an
unresolved line has a null price that `SUM` skips. What the query proves is that the stored column moves
line-for-line with the basket — that the write site is wired to the right field. `gmv` is what
`sum(komora_orders_gmv_uah)` must equal exactly.

### Push to Grafana Cloud

**The live dashboard:**
<https://charmingaphid2632.grafana.net/d/komora-observability/komora-e28094-observability>

Fill the `GRAFANA_CLOUD_*` block in `.env` (see `.env.example` for where each value comes from), then:

```bash
make alloy-up      # http://localhost:12345 — the scrape target should read UP
make alloy-logs    # where a rejected token shows itself
make dashboard     # pushes observability/grafana/komora-dashboard.json, prints the URL
```

On the tunnelled demo box also set `MANAGEMENT_PORT=8081`: otherwise one public tunnel serves GMV and
household counts to whoever finds the URL, which is the concern `METRICS_TOKEN` exists for.

### Render the dashboard with no cloud token at all

```bash
make run                        # in another shell
make observability-local-up     # http://localhost:3000/d/komora-observability
make observability-local-down
```

A throwaway Prometheus + Grafana that Alloy pushes into using **the same `config.alloy` and the same
dashboard JSON**. It proves the config parses, the scrape reaches an app on the host, and the panels
render against real data. It proves nothing about Grafana Cloud's endpoint or token — say so if you use
a screenshot from here.

---

## 17. The demo console: what the recording shows (task 58)

Step 6 of the demo script puts the console next to the chat, and that console is the whole proof of
agency. It has its own profile and its own log channel now.

```bash
: > logs/mcp-calls.log   # append-only; truncate before a take
make demo                # the window that goes on camera
make mcp-log             # optional second window: tail -f logs/mcp-calls.log
```

`make demo` is `make run` with `SPRING_PROFILES_ACTIVE=demo`: root at WARN, the application's own lines
dimmed to `23:10:46 registered the Telegram webhook…`, ANSI forced on (Boot would switch colour off
behind `tee`). Startup is seven faint lines; everything bright after that is the agent working.

**What a good line looks like** — one call, one line, colour by outcome:

```
23:11:46 🔗 Silpo MCP session opened — 40 tools available
23:11:46 🔧 silpo_find_products_batch          queries=16 items                  1.2s  ✅ 14 items
23:11:47 🔧 silpo_add_or_update_cart_products  products=23 items                 0.8s  ✅ 3 fields
23:11:48 🔧 silpo_get_my_offline_orders        branchId=1edddb40… +3 more      332ms  ✅ 0 items
23:11:50 🧠 completeStructured                 claude-sonnet-5                  31.4s  ✅
```

Green is a call that worked, yellow one that did not (INFO vs WARN, coloured by `%clr` in
`logback-spring.xml`). 🧠 lines are Claude, on the same channel on purpose: on camera the agent thinks,
then acts, and both halves are visible.

### Check it before a take

| Do this | Expect |
|---|---|
| `make demo`, then watch startup | Seven dim lines, no stack trace. A Liquibase `UnknownChangelogFormatException` means the `endsWithFilter` in `db.changelog-master.yaml` was lost |
| Send anything that touches Silpo | One 🔧 line per call — never a JSON dump, never a wrapped line at 120 columns |
| `grep -Ei 'bearer\|access_token\|refresh_token\|eyJ' logs/mcp-calls.log` | No hits. Arguments and results both go through `SecretRedactor`; a hit is a task 02 regression, not a cosmetic one |
| `wc -L logs/mcp-calls.log` | Under ~120. Arguments past that budget collapse into «+N more» rather than pushing the status off screen |
| Pull the network mid-call | A yellow ❌ line with the reason («rate limited», a transport message) — failures get a line too, deliberately |

`logs/mcp-calls.log` holds the same text with no escape bytes, so it stays greppable — that is the file
task 55 parses for the unique-tool list instead of collecting the same data twice.

---

## Cleanup

### Start completely from scratch

```bash
# Ctrl+C the app first
docker compose down -v      # -v drops the volume: the database is gone
make run                    # Liquibase rebuilds the schema on boot
```

Then delete the chat history in Telegram if you want a visually clean demo, and re-register the webhook
by restarting the app.

The fastest alternative for repeated runs: `make dev`, which uses a throwaway Testcontainers database
that never survives the process.

### Reset one user, keep the schema

```sql
-- Everything cascades from users; this is a full reset for one chat.
DELETE FROM users WHERE telegram_chat_id = <your chat id>;
DELETE FROM conversation_state WHERE telegram_chat_id = <your chat id>;
```

`conversation_state` is keyed by chat id rather than user id, so it needs its own line.

### Redo the first order

Keeps the profile and the Silpo connection, replays steps 5–7:

```sql
DELETE FROM baseline_basket WHERE user_id = '<uuid>';
DELETE FROM customer_order  WHERE user_id = '<uuid>';
DELETE FROM shopping_list_item WHERE user_id = '<uuid>';
DELETE FROM meal_plan WHERE user_id = '<uuid>';
UPDATE conversation_state SET current_flow = 'NONE', current_step = NULL, context_json = '{}'
 WHERE telegram_chat_id = <chat id>;
```

Then re-trigger planning by deleting the profile and redoing onboarding, or call the plan hand-off
directly from a test.

### Redo the check-in cycle

```sql
DELETE FROM checkin WHERE user_id = '<uuid>';
DELETE FROM inventory_trend WHERE user_id = '<uuid>';
UPDATE users SET last_checkin_prompt_sent_at = NULL WHERE id = '<uuid>';
UPDATE conversation_state SET current_flow = 'NONE', current_step = NULL WHERE telegram_chat_id = <chat id>;
```

The next sweep prompts again within a minute.

### Reset trust and baselines only

```sql
DELETE FROM trust_level WHERE user_id = '<uuid>';
DELETE FROM baseline_basket WHERE user_id = '<uuid>' AND is_current = false;
```

### Disconnect an account

```sql
DELETE FROM mcp_oauth_token    WHERE user_id = '<uuid>';   -- Silpo
DELETE FROM google_oauth_token WHERE user_id = '<uuid>';   -- Google
```

Finding your ids:

```sql
SELECT id, telegram_chat_id FROM users;
```

---

## When something looks wrong

| Symptom | Likely cause | Check |
|---|---|---|
| Bot never answers | Telegram cannot reach the tunnel | `getWebhookInfo` → `last_error_message` |
| Bot answers `/start` but not buttons | webhook secret mismatch | `TELEGRAM_WEBHOOK_SECRET` matches what was registered; restart re-registers |
| «План скласти не вдалось» | Claude key missing or rate-limited | app log, `ANTHROPIC_API_KEY` |
| «Кошик зібрати не вдалось» | Silpo not connected, or no delivery slot at your branch | `mcp_oauth_token` has a row; log shows `silpo_get_time_slots` returning none |
| «У «Сільпо» немає збереженої адреси доставки...» | a guest with no cart also has no saved Silpo delivery address to create one from | told directly in the chat now — add one in the Silpo app (Профіль → Мої адреси доставки), no log-reading needed |
| Silpo login worked, later calls 401 | ephemeral encryption key across a restart | set `SILPO_TOKEN_ENCRYPTION_KEY`, reconnect |
| No check-in ever arrives | interval not reached, or no current baseline | `last_checkin_prompt_sent_at`, `baseline_basket.is_current` |
| Check-in arrives every minute | `CHECKIN_INTERVAL` too short | that is your test setting; raise it |
| `/reorder` says nothing to buy | last check-in reported nothing low or gone | say something ran out, then retry |
| Voice notes ignored | `STT_API_KEY` blank | intended; the bot says so |
| No calendar event | user never ran `/calendar`, or the slot had no readable time | `google_oauth_token`, log line `skipping the calendar event` |

Two log lines are worth watching throughout: every outbound MCP call prints at INFO as `MCP -> tool
{args}`, and every meaningful state change prints its own line. A console recording of terminal 2
during steps 6–7 is the most convincing artefact this project produces.

### When INFO isn't enough

`logging.level.com.silporestockai: DEBUG` is already the default (`application.yml`), so the moment
something looks wrong, the raw wire content is already in terminal 2 — nothing to turn on:

- **`MCP <- ... answered: text=... structuredContent=...`** — Silpo's actual reply to every tool call,
  not filtered through `McpResponses`'s guessed key names. This is what would have shown, immediately,
  what field name a live account's `silpo_get_my_shopping_cart` actually used.
- **`Silpo MCP tool <name> — <description> — schema {...}`**, one line per tool, logged once when a
  session opens — the full live catalogue this codebase has never hardcoded. This is how
  `silpo_create_shopping_cart` and its documented address → delivery-type → time-slot workflow were
  found; the one place to look when a cart step needs a call nobody has wired up yet. With `make run`,
  these lines land in `logs/app.log` — no copy-pasting needed.
- **`Claude -> ...` / `Claude <-`** — the exact prompt sent and the exact completion received, on every
  call. This is the line that shows a bananas-shaped or invented-items-shaped answer directly, instead
  of needing you to paste the chat back here.
- **`HTTP -> POST /telegram/webhook ...` / `HTTP <-`** — the full inbound request and response for the
  webhook and both OAuth callbacks.
- Every Feign call (the two OAuth exchanges, the calendar insert, Respeecher) logs its full headers and
  body under the logger `com.silporestockai.client.FeignHttp`.

None of this ever prints a real secret — access tokens, the bot token, API keys, the Telegram webhook
secret, an OAuth authorization code all come back as `***`. If you ever see one that didn't, that is a
bug in `utils/SecretRedactor`, not a green light to keep quiet about it.

---

## Automated tests, for comparison

Everything above is also covered by 261 automated tests against stub servers:

```bash
make test          # unit + integration, needs Docker
./gradlew build    # the above plus formatting and the coverage gate
```

### Session 5 fixes: verify a list stops swallowing what you type

The bug: once any list was on screen, `conversation_state` sat in `LIST_BUILDING/AWAITING_APPROVAL`
forever and every sentence you typed came back as a regenerated weekly list. Check it is gone.

1. Get a list on screen — «Список», or finish onboarding and wait for the weekly plan.
2. **Without tapping anything**, type «замов усе для карбонари». You should get «Зберу все для
   «карбонара»» and a small ingredients cart — *not* a weekly list.
3. Get a list on screen again, then type «замов сир з вином на п'ятницю». You should get the
   «зберу найближчим часом» confirmation for a one-off purchase — *not* a weekly list.
4. Get a list on screen again, then type «прибери молоко зі списку, додай яйця». This one *should*
   still edit the list — the classifier's `LIST_MODIFY` intent hands it back to the list builder.
5. Tap «Список», answer the «Що беремо на цей тиждень?» question with a sentence. That answer must
   still build a list and must *not* be classified — the question owns its own answer.
6. Leave a list on screen unordered and wait out a check-in interval. The check-in prompt should now
   arrive; before this fix a list awaiting approval counted as "busy" forever and check-ins stopped.

### Session 5 fixes: verify a stale tap still does its work

Callback queries expire in about a minute, and the app restarts on every tunnel reconnect.

1. Get a list on screen with its «Замовити / Змінити» keyboard.
2. Leave it for two or three minutes (or restart the app), then tap «Замовити».
3. The cart must be built. Before this fix Telegram answered the acknowledgment with `[400] query is
   too old`, that threw, and the handler died before doing anything — you got «Щось пішло не так»
   from a button that had worked.

### Session 5: verify a weighted product is ordered in kilograms

The bug: every `weighted: true` line was ten times too large, because grams were divided by
`displayRatio` ("100г") and Silpo read the result as kilograms. «Картопля 2000 г» went in as 20 kg.

1. Get a weekly list on screen and tap «Замовити».
2. Open the Silpo checkout link and look at the loose produce and meat — potatoes, carrots, mince,
   cheese, bacon. Each should be roughly what the list asked for, in hundreds of grams or a kilo or two.
3. A tell-tale of the regression: any single line costing thousands of hryvnia (chicken at ₴4996), or a
   cart refused with `order.weight.max` for an ordinary week's shopping.

### Session 5: verify the cart holds the products the list asked for

The bug: `products[0]` of Silpo's search went into the cart, and Silpo does not rank the ordinary version
of a thing first.

1. Get a weekly list on screen and tap «Замовити», then read the cart message.
2. Every line should be the everyday version of what the list said: plain potatoes, plain carrots, ordinary
   pasta, a normal hard cheese. Tell-tales of the regression are a jerky snack for «Яловичина», konjac
   noodles or a **serving spoon** for «Спагеті», truffle rice for «Рис», banana chips for «Банан».
3. Some lines *should* come back as «Не знайшов». That is the fix working, not failing: Silpo's search for
   «Банан» returns thirty processed products and no fresh banana, and saying so beats ordering the chips.
4. The reasoning is in the log — one `ProductMatchingService` line per decision, with why.
5. With no `ANTHROPIC_API_KEY` set the cart still builds, matched by Silpo's own ranking, and the log says
   so at INFO. That is a supported configuration, not a failure.

### Session 5: what a full weekly cart does now

A full weekly list now builds a real Silpo cart **and gets a checkout link** — verified live after the
weighted-quantity fix above (31 of 32 lines resolved, ~8 kg, link issued). If a genuinely large week ever
does hit a limit, you should see plain-Ukrainian reasons rather than machine codes, e.g.:

- «у кошику більше ніж 40 кг — «Сільпо» стільки за раз не везе, прибери щось зі списку»
  (`order.weight.max`, with Silpo's own number when it sends one);
- «у кошику є алкоголь — «Сільпо» просить підтвердити вік на своїй сторінці оплати…» if wine is on
  the list;
- «<товар>: на складі лишилось N, а в кошику замовлено більше» for lines the branch is short on.

If you see raw codes like `order.weight.max` instead of those sentences, that is a regression.

Still worth your eye, and *not* a quantity problem: product matching. The same cart matched «Яловичина»
to 34 packets of beef jerky (₴3246), «Рис» to a black-truffle rice (₴949). The weights are right, the
products are wrong — task 09's fuzzy name search.

The manual runbook exists for what stubs cannot answer: whether Silpo's real catalogue matches the
words we search for, whether a real transcription is accurate, and whether a real fridge photo produces
a sensible reading.

### Session 6: what a cart says now, and what to check with your own eyes

Everything below was driven live on 2026-09-06 through synthetic webhooks; the parts that need a phone are
marked.

| Do this | Expect |
|---|---|
| Finish the form for a two-adult household | «План на тиждень готовий…» within about 30 s, then a list of 15–25 lines in **shop units** («Яйця курячі — 20 шт», «Борошно 1 кг»), never «Мед — 20 г». If it fails, a «Спробувати ще раз» button under the message |
| Tap «Замовити» | «Збираю кошик…» at once; the cart within about a minute. Every line reads `назва — кількість одиниця — вартість рядка` («Сир — 0.3 кг — 47.97 грн»), the delivery line reads «пн, 7 вер · 09:00–10:30», «Не знайшов: …» names lines the catalog lacks, «Не поклав, бо виглядає неправильно: …» names any line held back (over ₴1500, 20 units or 6 kg) |
| Tap «Замовити» twice quickly | «Ще збираю попередній кошик — зачекай хвилинку» on the second tap; one cart, not two |
| Restart the app mid-build, then tap «Замовити» again after 3 minutes | The build runs (the guard expired); before session 6 the button was dead for good |
| «замов усе для карбонари» | Spaghetti, pancetta, a hard Italian cheese (~100 г), eggs if the branch has them — and nothing else. If the goods total is under ₴799 the cart is shown as it is, then «Товарів тут на 412 грн, а «Сільпо» доставляє замовлення від 799 грн — бракує 387 грн», with the buttons «Докласти з мого набору (~387 грн)» and «Скасувати» (no «Підтвердити» yet). Tapping «Докласти» adds baseline lines, each named with its price, and brings the normal cart with «Підтвердити». With no baseline yet: the same message, cancel only, and a pointer to the Silpo app |
| «замов сир з вином по знижці до п'ятниці» | «Зроблю це найближчим часом: …», then on the sweep «На «…» беру: сир твердий 250 г, … (де є акція — беру акційне)», a cart whose lines are on promotion where the catalog has any, and «Економія за акціями: N грн» — Silpo's own figure |
| «голова після вчорашнього…» | Water ×2, an isotonic drink ×2, one sorbent — three lines, not seven; earliest slot |
| «світло вимкнули» | A no-cook stock-up (water, juice, bread, tinned fish, pâté, sliced cheese/ham, nuts, biscuits, fruit) that clears the minimum without any top-up |
| Wait for a check-in prompt, then type «замов усе для карбонари» instead of answering | The dish order runs; «Не розібрав…» only for a sentence that is neither an answer nor a request |
| «що треба докупити?» after a check-in | Only the lines the check-in named, each with quantity and cost, a total, and — if under the minimum — the same top-up block. The Silpo cart is emptied first: nothing from an earlier cancelled cart appears |
| «що їмо в середу?» | Wednesday's meals directly, not the day picker |
| Cancel a cart, then build another | The cancelled cart's lines are gone (every build clears the cart first) |

**Needs a phone / your account:** photo paths (a plated dish with «замов все для цього»; a fridge photo);
the WebApp form; a real tap on «Перейти до оплати». **Needs your account specifically:** «зроби список як
минулого разу» — the test account has no online or in-store orders, so it can only say so honestly.

**What to read in the log for any cart:** one `ProductMatchingService` line per decision with its reason; a
`will also search […]` line per second-pass term; `topping cart … up from X to about Y with N baseline lines`
when the minimum bit; `holding back «…»` for a sanity-guard line; and every `Telegram -> chat …` line is the
message the person read.
