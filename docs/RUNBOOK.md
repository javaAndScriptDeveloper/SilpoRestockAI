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
      «за K з N позицій» when the new list has items the baseline never had. Expect a partial count: the baseline
      is matched by the request recorded on each basket line, then by catalog name, then by a catalog name that
      carries every word of the list line. «Куряче філе» finding nothing in «Філе курчати-бройлера» is the rule
      working, not a bug — 13 of 25 was the live figure on 2026-09-08.
- [ ] The number is in the same ballpark as the cart's «Разом» that follows «Замовити». For a `READY_MEALS_ONLY`
      week it is exact: live on 2026-09-08 the estimate said ₴1977.56 and the cart's own `productsTotal` was
      ₴1977.56, with «Разом» ₴2046.56 once delivery was added.
- [ ] Nothing above may add a Silpo call. `SELECT count(*) FROM mcp_tool_call` before and after a list re-render
      («Показати весь список») must be the same number.

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
confirmed unedited, catalog resolve rate per cart, distinct MCP tools of 40 — each with its sample size.
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

**`productQuery` must be the catalog's own product name.** A composed one — «Молоко Яготинське 2.5% 900г»,
«Молоко Галичина 2.5%», «Молоко Молокія 2.5%» — comes back `422 no catalog product matched`; the search is
the household-facing one, not a brand lookup. A bare brand («Молоко Яготинське») does find something but not
the same thing twice: it answered with the right milk on one call and with «Вершки ультрапастеризировані
Яготинські 15% т/б» on the next. Probe with a broad query first, read the `productName` the response
carries, then create the placement with that exact string — `PartnerPromotionAdminService` prefers the
catalog entry whose name equals the query, so an exact name is the only deterministic input. Delete probe
rows afterwards (`DELETE FROM partner_promotion WHERE category_or_query = '<probe>';`).

#### What is seeded for the demo recording (2026-09-08)

Both rows were created through the endpoint above against the live catalog and verified live end to end.

| Partner | `category_or_query` | `silpo_product_id` | Product | Status |
|---|---|---|---|---|
| Яготинське | `молоко` | `1ed07622-1cad-6c7a-a419-dd63763181f9` | Молоко «Яготинське» 2,6% п/е | ACTIVE, 2026-09-07 → 2026-09-21 |
| Пирятин | `сир` | `1ed076a7-c9c2-6e8a-8ed0-5f148feebb3f` | Сир «Пирятин» «Голландський» твердий нарізаний 45% | PAUSED |

- Milk is the one meant to be on screen: the weekly list's «Молоко» line becomes that product, marked ★, and
  `make promotions` prints 1 / 1 / 1 with «100 %» twice — Акт 1–2 and Акт 7 of the demo script.
- Cheese is left **PAUSED on purpose**: it was verified through the ad-hoc flow («замов мені сиру з вином» →
  the sweep builds the cart, the cheese line carries ★), which proves the placement is resolved wherever a
  product is resolved rather than inside the weekly plan. Active, it would put a second ★ in the same weekly
  cart, since the list also carries «Сир кисломолочний» / «Сир твердий». Re-activate with
  `UPDATE partner_promotion SET status='ACTIVE' WHERE category_or_query='сир';` if the ad-hoc act is recorded.
- The funnel rows in `partner_promotion_event` are from those two runs only. A cart build that dies on Silpo's
  stock validation (a banana line over stock, here) still logs `IMPRESSION` and `ADDED_TO_CART` — Silpo took
  the cart, only checkout was refused — so a failed rehearsal leaves counted events behind. Delete them by
  `occurred_at` before recording, or the report shows a conversion the household never saw.
- `METRICS_TOKEN` is in `.env`; without it both `/internal/promotions` endpoints answer 404, not 403.

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

### Task 63: own-brand featuring and share of category

Needs the app running against the real account (`make run`), `METRICS_TOKEN` in `.env`, and a
connected Silpo session for whoever's id goes in `verifyAsUserId`.

1. Find a Silpo private label in a category the weekly list already has. Probe broadly first — task 46
   learned that a composed query («Чай Премія чорний 100г») returns 422 and that a bare brand is not
   deterministic. Search the plain category word, read the catalog's own `productName` back, and use
   that exact string.
2. Create the placement:

   ```bash
   curl -s -X POST "http://localhost:8080/internal/promotions" \
     -H "X-Metrics-Token: $METRICS_TOKEN" -H 'Content-Type: application/json' \
     -d '{"partnerName":"Сільпо власна марка","categoryOrQuery":"чай",
          "productQuery":"<the catalog name, exactly>","promotionType":"OWN_BRAND_MARGIN_BOOST",
          "verifyAsUserId":"<a connected user id>"}'
   ```

   The response echoes the real `silpoProductId` the catalog answered, and `promotionType`.
3. Walk «📝 Список» → «Замовити» → «Підтвердити». The log line to watch for is
   `partner placement <id> answered «Чай» with product <id>`.
4. `make promotions`. Check: the own-brand row sits under «Власні марки», never under «Платні
   розміщення»; FSR's denominator is the number of tea lines resolved, not the number the placement
   won; the baseline carries «виміряно» or «наближення (1/N кандидатів)», and lift is «—» whenever the
   baseline is.
5. Count by hand once, against the DB, and compare:

   ```sql
   SELECT line_name, resolved_product_id, promotion_id, candidate_count
   FROM category_resolution_log ORDER BY occurred_at;
   ```

A failed cart build leaves resolution rows behind the same way it leaves IMPRESSION events (task 46) —
clear both by `occurred_at` before recording the demo.

### Task 64: the partner section of the dashboard

Needs the app running (`make run`) and the local observability harness (`make observability-local-up`,
Grafana on `http://localhost:3000/d/komora-observability`, anonymous admin). Grafana Cloud gets the same
file through `make dashboard`.

1. `curl -s localhost:8080/actuator/prometheus | grep ^komora_promotion` — every placement should have a
   `komora_promotion_share`, and `komora_promotion_share_overall{type="ALL"}` should exist. A placement with
   no baseline has **no** `komora_promotion_lift` line at all; that absence is the honest answer, not a bug.
2. Open the dashboard and scroll to «Партнерські розміщення — зведення». The ten-second read: the two big
   numbers at the top (частка категорії, ₴ атрибутовано), then one band per pool below them.
3. Check the three things a screenshot has to show:
   - the funnel bars descend per brand (Показ → У кошику → Підтверджено), it is not a table of repeated rows;
   - «Conversion Rate між стадіями» prints Яготинське at **125 %** with the bar stopped at 100 — the number
     is real, the clamp is deliberate, and the panel description plus the section legend say the funnel is
     not strictly nested;
   - PAID_PARTNER and OWN_BRAND_MARGIN_BOOST are separate bands with their own row headers.
4. The raw event table is inside the collapsed «Події розміщень (деталізація)» row. Expand it only when
   somebody wants to check the arithmetic.

Note for whoever automates this: Grafana renders its panels lazily, so a screenshot taken through a
background browser tab comes back blank — including for a one-panel dashboard written by hand. Verify the
numbers with the Prometheus API (`localhost:9090/api/v1/query?query=…`) and the picture with your own eyes.

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

### Task 70: verify the one-time capability teaser

Criterion 3 of task 70 is a fresh-onboarding check. A profile-only reset is enough — delete the
`user_profile` row and the chat's `conversation_state` row, keep `mcp_oauth_token` and the orders — then
run onboarding from `/start`. `capability_reveal_sent_at` on `user_profile` is what makes it a one-off, so
a profile deleted this way starts over; a profile merely edited does not.

| Do this | Expect |
|---|---|
| Finish a fresh onboarding and wait for the first plan | Four messages, unprompted, in this order: «Записав. Готую перший план», «План на тиждень готовий…», «Ось що пропоную взяти…» with its buttons, then the teaser «Поки що ти бачив тільки тижневий план…» |
| Tap «🧾 Анкета», change one answer, tap «Так, оновити» | A second plan and list arrive; the teaser does not, and `capability_reveal_sent_at` keeps its original value |
| Tap «❓ Інструкція» | The full instruction, byte-for-byte as before task 70 — five of its example lines are the same constants the teaser renders (`HelpContent`), so drift between them is not possible |

### Task 73: verify a blackout cart holds nothing that needs a fridge

Drive it with the sentence, not the command — «світло вимкнули» goes through `IntentRouterService`, which
is the path the bug was found on; `/blackout` skips that classification.

| Do this | Expect |
|---|---|
| Send «світло вимкнули» | Six lines and no more: water, tinned fish, bread, biscuits, juice, pâté |
| Read every line | Nothing a fridge would hold — no cheese, no sliced ham, no dairy, nothing «охолоджений» or «заморожений» |
| `grep 'it needs a fridge' logs/app.log` | Any drop names a genuinely chilled product. A loaf, a syrup or a sunflower oil in that list is a false positive in `CartBuildingService.needsAFridge` |
| Read the total | A few hundred hryvnia, with the shortfall against ₴799 stated and the top-up offered — not a weekly shop |

**Measured live on 2026-09-10** (branch `1edddb40-e664-609c-a1a7-f9004aa8afa6`), before and after:

| | Before | After |
|---|---|---|
| Lines | 11 | 6 |
| Goods total | ₴778.77 | ₴388.91 |
| Needs a fridge | Сир Spomlek «Радамер» ₴84.90, Шинка Алан ₴69.99 | none |
| The rest | + Горіх волоський ₴129.00, Банан ₴85.99, Яблуко ₴29.99 | Паштет Podravka 2×₴72.49, Тунець «Повна Чаша» 2×₴61.49, Вода «Природне джерело» 2×₴15.99, Хліб «Київхліб» «Тост» ₴31.99, Печиво Super Kontik ₴28.99, Сік Jaffa ₴27.99 |

Two things that run only shows up live, both fixed: Silpo's search is a plain text match, so a line
narrowed to «паштет консервований» returned nothing and the rescue pass bought a ₴99 tin where the plain
«паштет» shelf has one at ₴72.49 — keep blackout lines one plain word each. And the fridge markers match
the product name's **first word**, because matching anywhere dropped «Хліб «Київхліб» британський світлий з
молоком нарізаний» as dairy.

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

**Expect:** «Збираю щось на поїсти без плити й холодильника.» then a six-line cart — water, juice, bread,
tinned fish, tinned pâté, biscuits — with the same confirm/cancel buttons as any other cart.

The cart is expected to come back **under Silpo's ₴799 minimum**, with the shortfall named and the
«Докласти з мого набору» button offered. That is the designed outcome (task 73), not a bug: an emergency
order is small, and the threshold is the shop's rule to state rather than the agent's to pad around.

**Verify:**

```sql
SELECT type, status FROM customer_order ORDER BY created_at DESC LIMIT 1;   -- AD_HOC / DRAFT
```

Confirm it, then check the thing that matters:

```sql
SELECT count(*) FROM baseline_basket;   -- unchanged from before /blackout
```

An emergency lunch is explicitly not evidence about what the household normally eats.

**Judge the results:** read the item list. It should be genuinely no-cook food, and **not one line may be
something that needs a fridge** — no cheese, no ham or other sliced deli meat, no dairy, nothing frozen or
«охолоджений». That is enforced on the candidate pool (`CartBuildingService.needsAFridge`, reached through
`MatchingHints.withoutAFridge()`), so a chilled product in the cart means the marker list has a gap, not
that the matcher chose badly. Each drop is logged as `dropping «…» as a candidate for «…»: it needs a
fridge`. If Silpo's catalogue answers a query badly in some other way, the fix is the curated list in
`BlackoutModeService`, not a smarter inference.

---

## 16. Metrics and the two Grafana dashboards (tasks 54, 75)

The app publishes Prometheus metrics at `/actuator/prometheus`. Grafana Alloy scrapes that locally and
pushes them outbound — push, not pull, because a demo box behind a rotating tunnel has no address anyone
can scrape.

**Which Alloy pushes where.** There are three Alloys in this repo and they are not interchangeable:

| Container | Started by | Scrapes | Pushes to |
|---|---|---|---|
| `komora-alloy-local` | `make observability-local-up` | the host app on `:8080` | the throwaway Prometheus at `:9090` |
| `komora-alloy` | `make prod-alloy-up`, or `make deploy` on the server | `app:8081` inside the prod network | **Grafana Cloud** |
| `app-alloy` | `make alloy-up` | the host app on `:8080` | Grafana Cloud |

**Grafana Cloud is the server's stream.** A development box uses the local harness, which needs no token.
`make alloy-up` exists for the deliberate case of pushing a laptop's numbers to the cloud — leave it off
otherwise, or a rehearsal and the server sum into the same panels. That is exactly what happened once: the
laptop's `app-alloy` ran for days while the server's Alloy had never been started, so the hosted dashboard
was showing the laptop and looked identical to the local one. If the hosted dashboards ever look like the
local ones, check `label_values(komora_users_registered, env)` first — if it answers `["local"]`, the
server is not pushing at all.

**The two live dashboards (task 75):**

| | Hosted | Local harness |
|---|---|---|
| Business — гість (A, сині плитки) / «Сільпо» (B, зелені) | <https://charmingaphid2632.grafana.net/d/komora-business> | <http://localhost:3000/d/komora-business> |
| Technical — MCP RED per tool, Claude, agent, process | <https://charmingaphid2632.grafana.net/d/komora-observability> | <http://localhost:3000/d/komora-observability> |

Both come from `observability/grafana/*.json`, and the JSON comes from `observability/grafana/build-dashboards.py`.
**Never hand-edit the JSON**: edit the script, `make dashboards-json`, run `DashboardJsonTest`, commit both, then
`make dashboard` to push. The local harness provisions the same directory through Grafana's file provider, so
after a regeneration `docker restart komora-grafana` reloads it. The `env` variable at the top of each dashboard
comes from Alloy's `external_labels` and the app's own `management.metrics.tags.env`; pick the demo box's value
there if a rehearsal laptop is pushing at the same time, or the GMV of both would sum.

### Check the numbers are real, without any cloud account

```bash
make run
curl -s localhost:8080/actuator/prometheus | grep '^komora_' | sort
```

Absolute levels (`komora_users_registered`, `komora_orders_gmv_uah`, `komora_checkins`, `komora_reorders`,
`komora_intent_order_median_seconds`, …) come from the database and are rebuilt every 30 s, so they
survive a restart — a counter would read zero after every `make run`, which is exactly wrong for "GMV since
launch". Rates and latencies (`komora_mcp_call_seconds`, `komora_claude_call_seconds`, `komora_cart_*`,
`komora_intent_order_seconds`) are recorded in-process and start empty on each boot; drive a real session
first or they will not be there at all.

> **A fresh database prints zeros, and zeros are not results.** `komora_orders_value_missing` is the
> honest companion to the GMV panel: it counts confirmed orders with no stored total — rows written
> before task 54 added the columns. Those are excluded from GMV rather than counted as ₴0. Likewise an
> intent with no confirmed order publishes **no** `komora_intent_order_median_seconds` row: «No data» on
> the panel is the truth, a zero would read as «confirmed in no time».

### Intent → order speed (task 75): what it measures and how to check it

`customer_order.trigger_intent` / `requested_at` are written on the draft by every intent-routed order
(`HANGOVER_RELIEF`, `BLACKOUT`, `REORDER`, `DISH_INGREDIENTS_ORDER`, `AD_HOC_SCHEDULED_PURCHASE`); the
weekly cart and the scheduled reorder cycle have none. The clock starts when the sentence reaches the router,
before the classification call; for a purchase the sweep fires, at the sweep. Confirmation records the timer
and the next refresh recomputes the medians:

```sql
SELECT trigger_intent, count(*),
       floor(extract(epoch FROM percentile_cont(0.5)
             WITHIN GROUP (ORDER BY confirmed_at - requested_at))) AS median_s
FROM customer_order
WHERE status = 'CONFIRMED' AND trigger_intent IS NOT NULL AND requested_at IS NOT NULL
GROUP BY trigger_intent;
```

`komora_intent_order_median_seconds{intent="…"}` must equal `median_s` exactly (whole seconds, floored, which
is what `Duration.toSeconds()` does), and `{intent="ALL"}` the same over all those rows together. Verified
2026-09-10 on four live intents: BLACKOUT 214 s, DISH_INGREDIENTS_ORDER 28 s, HANGOVER_RELIEF 17 s, REORDER
13 s, ALL 23 s — the SQL and the scrape agreed on every row.

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

Fill the `GRAFANA_CLOUD_*` block in `.env` (see `.env.example` for where each value comes from), then:

```bash
make alloy-up      # http://localhost:12345 — the scrape target should read UP
make alloy-logs    # where a rejected token shows itself
make dashboard     # pushes every observability/grafana/*.json, prints both URLs
```

On the tunnelled demo box also set `MANAGEMENT_PORT=8081`: otherwise one public tunnel serves GMV and
household counts to whoever finds the URL, which is the concern `METRICS_TOKEN` exists for.

### Render the dashboards with no cloud token at all

```bash
make run                        # in another shell
make observability-local-up     # prints both local URLs
make observability-local-down
```

A throwaway Prometheus + Grafana that Alloy pushes into using **the same `config.alloy` and the same
dashboard JSON**. It proves the config parses, the scrape reaches an app on the host, and the panels
render against real data. It proves nothing about Grafana Cloud's endpoint or token — say so if you use
a screenshot from here. Grafana renders panels lazily, so a screenshot from a background browser tab comes
back blank; verify a panel by running its PromQL against `localhost:9090/api/v1/query` and look at the
picture with your own eyes.

---

## 16a. Tunnels and restarts during a long live session (session 10)

localhost.run's anonymous hostname rotates every ~10 minutes and `scripts/tunnel-supervisor.sh` restarts
the app on every rotation — a cart build that is mid-way dies with it. For a session driven from a
browser:

```bash
scripts/session-tunnel.sh ngrok     # stops the supervisor, one stable hostname, restarts the app once
scripts/restart-app.sh              # restart on the same tunnel (PROFILE=demo … for the recording profile)
scripts/stop-app.sh                 # before make test — the suite shares build/classes with bootRun
scripts/session-tunnel.sh stop      # then start scripts/tunnel-supervisor.sh again
```

ngrok's browser interstitial shows once per browser; click «Visit Site» on the WebApp URL and Telegram
Web's Mini App works from then on. It does **not** work in a phone's WebView (docs/LOCAL_TUNNEL.md), so a
phone recording runs on the supervisor. cloudflared quick tunnels time out from this network.

The test suite writes its own demo-channel lines to `build/mcp-calls.log`, not `logs/mcp-calls.log`
(`DEMO_LOG_FILE` in `build.gradle.kts`), so a recording's log stays clean of stub JSON.

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

## 18. A group order round (tasks 68, 74)

A group chat is not a household: it gets no `users` row, no onboarding, no intent router. Everything a group
sends goes to `GroupEventService`, whose state is `group_event.status`. A round starts when somebody tags the
bot and asks («@бот збери на п'ятницю, бюджет 2000» — any mention with no round open does it); from then on the
bot reads only replies to its own messages and taps on its own buttons — a further mention or a `/command` is
ignored like any other message (product decision, session 15 review).

**Scope, and how it is said (task 74).** The round resolves **drinks only** — task 68 stopped there on purpose,
since a company's allergies cannot be consented to in a group chat. The copy no longer *sells* it as a drinks
run, though: «Інструкція», the group intro and the greeting all pitch «спільна закупка на компанію» and then say
in their own sentence that drinks are what it collects today. Somebody who asks for food is told so at once
(`GroupOrderScope.asksForFood` → `drinksOnlyAck`), and the proposal's own note repeats it for whatever that word
list misses. Nothing about resolution changed: `GroupEventIntegrationTest.fullRound` passes untouched.

**Driving a group round with synthetic webhooks** (session 21, and the trap in it): a made-up group chat id
works — every outbound send fails with `[400] Bad Request: chat not found`, which is loud in the log but
lands *after* `TelegramOutboundService` has already printed the message text, so the copy is readable. Two
things must be real, though. A reply is only «addressed to the bot» when `reply_to_message.from.id` is the
**bot's real id** (`getMe`, not a made-up one) — with a wrong id the update is dropped as
`ignoring an unaddressed message in group …`. And the failed greeting leaves `greeting_message_id` null, so
set it by hand (`UPDATE group_event SET greeting_message_id = …`) before replying to it. Delete the
`group_event` and `group_event_participant` rows afterwards.

### Set-up

- **Privacy mode.** Telegram delivers a bot in privacy mode (every bot's default) only commands addressed to it,
  replies to its own messages and service messages — **a plain «@bot збери напої…» never reaches the webhook**
  (found live 2026-09-09: the tag went out, `getWebhookInfo` showed nothing pending, the log stayed silent). Either
  make the bot a **group administrator** (the intro asks the person who added it to do exactly that when
  `getMe` reports privacy mode on) or, for the production bot, **BotFather → /setprivacy → Disable**. The app
  logs a WARN at boot while privacy mode is on. The code drops every unaddressed message either way, so an
  admin bot behaves the same as one in privacy mode — it just also sees the tag.
- `TELEGRAM_BOT_USERNAME` in `.env` (without the `@`) — used only for the `t.me/` link in the «підключи
  «Сільпо»» hint. Blank works too (one `getMe`).
- The organizer must be a Komora user with Silpo connected **in their private chat** — a private chat's id is
  the person's Telegram id, which is how the group round finds their household row. Without it the proposal is
  posted unpriced with a «підключи «Сільпо»» hint, and consensus repeats the hint instead of building a cart.

### Drive it

| Do this | Expect |
|---|---|
| Add the bot to a group | A one-line intro («Коли треба зібрати спільну закупку на компанію — тегни мене…»), no round, no `group_event` row. With privacy mode on, the intro opens with «Спершу зроби мене адміністратором групи» — until then the tag below never arrives |
| «@bot збери напої на п'ятницю, бюджет 2000» (or tap «🔄 Новий збір» under the last round's summary) | A greeting with the rules and one button «✅ Всі відповіли»; `group_event` row in `COLLECTING_REPLIES`, `organizer_telegram_user_id` = whoever tagged/tapped, budget/tag/date already parsed from the tag text |
| Reply to the greeting: «вино червоне», «пиво світле, це на ДР», «.» | Each gets «Записав, {ім'я}. Відповіли: N.» as a reply; one row per person, a second reply overwrites the text |
| Reply to the greeting asking for food: «чіпси й пиво», «візьми шашлик» | «Записав, {ім'я}. Відповіли: N. Тільки скажу чесно: поки що я збираю на компанію лише напої — їжу доведеться взяти окремо.» The reply is still stored and still counted; the beer in it is still bought (task 74) |
| Write anything in the group without replying to the bot — including a second `@bot …` while the round is open, and `/anything` | Nothing. `logs/app.log` at DEBUG: `ignoring an unaddressed message in group …`; no Claude call, no MCP call |
| Organizer, as a reply to the greeting: «бюджет 1500, привід: ДР, дата 20.09» | «Прийняв: бюджет 1500 грн · привід: ДР · дата 20.09.2026» |
| Someone other than the organizer taps «Всі відповіли» | A toast «Це кнопка організатора.», nothing else |
| Organizer taps «Всі відповіли» | «Закрив список: N людей. Рахую пропозицію — хвилинку.», then within ~30 s the proposal: «Пропозиція №1 на N людей (кількості орієнтовні)», real catalog names, quantities, line costs, «Разом орієнтовно», the budget verdict, one line of rules («👍 — згоден. Змінити — тегни @bot і напиши, що прибрати чи додати»), and one «👍 Погоджуюсь» button. The per-head split appears only in the consensus message; the model's note is in the log. Every reply row now has `counted_in_denominator = true`, `frozen_at` set, status `PROPOSED` |
| Reply to the greeting after that | «Записав, …, але цей раунд уже закрито» — the row exists with `counted_in_denominator = false` and the count above does not move |
| Tap 👍 | Toasts «Погодились: 1 з N», «2 з N» …; a second tap says so; a tap from somebody who was not counted says «Ти не у списку цього раунду» |
| Reply to the proposal «менше пива, більше вина» (anyone in the frozen set; an uncounted person gets «Правки приймаю лише від тих, хто в цьому раунді») | «Прийняв правку від … усі 👍 обнулено.», then «Пропозиція №2»; `proposal_version` = 2, `group_event_approval` rows for version 1 stay, none for version 2; a tap on the old button says «стара пропозиція» |
| Everyone counted taps 👍 on the latest version | Group: «✅ Усі N погодились. Поклав у кошик «Сільпо» {організатор}: …» with the split. **Organizer's private chat:** the usual cart message with «Підтвердити / Інший час / Скасувати» and, after confirming, «Перейти до оплати». Status `APPROVED`, `group_event_item` holds the lines |
| Organizer confirms in private | Group: «🎉 {організатор} підтвердив замовлення» with a «🔄 Новий збір» button. Status `ORDERED` |

**What to read in the log:** `opened group round … for organizer …`, `frozen with N counted participants`,
`gathered signals … same-group round …, N seasonal lines` (the tiers that fed the model), `the model proposed
N lines`, `clamped «…»` if a quantity was cut, then the ordinary matcher and cart lines under the organizer's
user id, and every `Telegram -> chat <group id>` line is what the group read.

### Driving it without three phones

Only the outbound side needs a real group. Create the group with the bot in Telegram Web, let the real
`my_chat_member` arrive (tunnel up) — or post it yourself — then post the other participants as synthetic
webhooks carrying the group's real (negative) chat id and any `from.id`. Every bot message lands in the real
group; only those participants' replies are synthetic. Shapes (secret header as in section 3):

```bash
# a reply to the bot's greeting (message_id from the greeting, from.id 2020… = the bot's own id = token prefix)
{"update_id":900101,"message":{"message_id":9101,"date":1,"chat":{"id":-100…,"type":"supergroup"},
 "from":{"id":900042,"is_bot":false,"first_name":"Ігор"},"text":"пиво світле, це на ДР",
 "reply_to_message":{"message_id":<greeting id>,"date":1,"chat":{"id":-100…,"type":"supergroup"},
 "from":{"id":<bot id>,"is_bot":true,"first_name":"Komora"},"text":"…"}}}
# a 👍 tap on the proposal (callback data from the button)
{"update_id":900102,"callback_query":{"id":"cb-1","chat_instance":"x","from":{"id":900042,"is_bot":false,"first_name":"Ігор"},
 "data":"grp:ok:<event id>:<version>","message":{"message_id":<proposal id>,"date":1,"chat":{"id":-100…,"type":"supergroup"},
 "from":{"id":<bot id>,"is_bot":true,"first_name":"Komora"},"text":"…"}}}
```

The ids of the bot's own messages are not in the log; read them from Postgres: `select id, greeting_message_id,
proposal_message_id, proposal_version, status from group_event order by created_at desc limit 1;`.

### What was walked live (session 15, 2026-09-08) and what is left for you

Walked in a real group («Комора — тест напоїв») with the real Silpo MCP: the real add event named the organizer,
a plain message got no reaction, the owner's reply and two synthetic replies were stored, the organizer's tap
froze 3, the proposal came back with real catalog names and prices, 2 of 3 approved, a synthetic revision
posted version 2 with zero approvals and refused a tap on the old button, three 👍 built the real cart in the
organizer's account, the ₴799 top-up and «Підтвердити» ran in the private chat, and the group got «🎉 …
підтвердив замовлення». Left for you: (1) three *real* phones in one group — the two synthetic participants
prove the code path, not the UX of three people tapping; (2) «Перейти до оплати» — real money.

### Reset

`delete from group_event;` cascades to participants, items and approvals. The organizer's draft
`customer_order` (type `AD_HOC`) is cancelled like any other draft.

## 19. Deploy checklist (task 59)

Everything below the "on the server" line needs a server. Everything above it is already done and
verified locally — the image builds, the whole stack runs in Docker, and the divergences between
`./gradlew bootRun` and a container were found and fixed (see *What container-only breakage was already
fixed*, further down).

What the deployment looks like: **Caddy** terminates TLS on 80/443 and is the only thing the internet can
reach; it proxies to the **app** on 8080; the app's actuator lives on 8081, which is published nowhere;
**Postgres** publishes nothing and keeps its data in a named volume. All three restart unless stopped.

### Before you touch the server

1. **A domain.** Point an `A` record at the server's IPv4. A subdomain is fine (`komora.example.com`).
   Wait for it to resolve before the first deploy — `dig +short A komora.example.com` must return the
   server's IP. Let's Encrypt rate-limits *failed* authorizations, so a premature attempt costs an hour.
2. **A production bot.** `/newbot` in @BotFather, and `/setprivacy` → Disable on it, or a group round
   (§18) never sees the `@bot` mention that opens it. Keep its token separate from the development bot's:
   whichever bot called `setWebhook` last is the one Telegram delivers to, and two bots on one URL is the
   most common way a demo bot goes quiet.
3. **A server.** Anything with Docker, a public IPv4, and ports 80/443 free. 2 GB RAM if you want to
   build the image on it; 1 GB is enough if you build on your laptop and ship the image (see step 4 below).

### On the server

```bash
# 1. Docker, if the image does not already have it
curl -fsSL https://get.docker.com | sh

# 2. The repo
git clone <repo-url> komora && cd komora

# 3. The secrets. Read the comments — every line says where to get the value.
cp .env.prod.example .env.prod
vim .env.prod          # DOMAIN + the five REQUIRED secrets, at minimum
chmod 600 .env.prod

# 4. Deploy. Idempotent: the same command for the first deploy and every update.
./scripts/deploy.sh    # or: make deploy
```

`deploy.sh` refuses to start if a required variable is missing (it runs `compose config` first), waits for
the app's healthcheck, and then verifies the *public* URL: 405 on the webhook path, 200 on the WebApp
form, 404 on `/actuator` (proving actuator is not exposed). A failure prints the app log and exits 1.

By default it **pulls the image CI published** rather than compiling on the server (§20), so step 4 takes
seconds and the server never needs a Gradle toolchain or the RAM for one. This requires a green CI run on
`main` to exist — on the very first deploy, before anything has been published, use `--build`.

**`./scripts/deploy.sh --build`** compiles the image on the box instead. If that dies, it is almost
certainly the Gradle build being OOM-killed on a small VPS. Either add swap:

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
```

or build on your laptop and ship the image, skipping the server-side build entirely:

```bash
docker save ghcr.io/javaandscriptdeveloper/silporestockai:latest | ssh <host> 'docker load'
ssh <host> 'cd komora && make prod-up'
```

### Then

5. **The webhook** normally needs nothing: the app calls `setWebhook` itself at every boot, and
   `deploy.sh` prints the line proving it. If it did not, or you need to re-point it by hand:
   ```bash
   ./scripts/set-webhook.sh              # uses DOMAIN from .env.prod
   ./scripts/set-webhook.sh --info       # url, pending_update_count, last_error_message
   ./scripts/set-webhook.sh --delete     # stop delivery now, e.g. while the app is down
   ```
6. **Silpo OAuth.** The client id in `.env.example` is registered against `localhost` and will *reject*
   `https://$DOMAIN/auth/silpo/callback`. Leave `SILPO_MCP_CLIENT_ID` blank on the server: the app
   registers a fresh client on first use. Then copy the id it logged back into `.env.prod` so redeploys
   reuse it instead of registering again:
   ```bash
   make prod-logs | grep -i "registered.*client"
   ```
7. **Google Calendar** (§14), if it is on: add `https://$DOMAIN/auth/google/callback` as an authorized
   redirect URI in the Google Cloud console. The localhost one will not work here.
8. **Walk §2 through §7 against the real URL** — onboarding, the WebApp form inside Telegram's webview
   (this is the part that cannot be tested on localhost), Silpo login, a plan, a cart, a confirmation.
9. **Metrics** (§16): fill the `GRAFANA_CLOUD_PROM_*` block in `.env.prod`, then `make prod-alloy-up`.
   Alloy scrapes the app's management port *inside* the compose network and pushes outbound, so this
   opens no inbound port. With that block filled, `make deploy` turns the `observability` profile on by
   itself and reports Alloy's state at the end — the manual target is only for driving it out of band.
   `make prod-alloy-logs` is where a rejected token shows itself (401 = the token lacks `metrics:write`,
   403 = wrong instance id).
10. **Put the link in "Selling Points та Пітч-аргументи"** — that is the acceptance criterion this whole
    task exists for.

### Day-to-day on the box

| | |
|---|---|
| `make deploy` | `git pull`, pull the CI image, restart, verify |
| `make prod-pull` | deploy the newest published image right now, without waiting for Watchtower (§20) |
| `make prod-ps` | status + health of all four containers |
| `make prod-logs` | follow the app log |
| `make prod-alloy-logs` | are metrics actually reaching Grafana Cloud? (§16) |
| `docker logs -f komora-watchtower` | is CD actually deploying? (§20) |
| `make webhook-info` | why is the bot silent? |
| `docker exec komora-app curl -s localhost:8081/actuator/health` | app health. It is on the **management** port, which is published nowhere — `localhost:8080` on the host is the app port and answers 404 for `/actuator/*`, which is the point |
| `curl localhost:8080/telegram/webhook` | the app itself, bypassing Caddy (loopback-only mapping; `APP_LOCAL_PORT` in `.env.prod`) |
| `docker compose -f docker-compose.prod.yml --env-file .env.prod exec db psql -U komora komora` | psql |

**Never `docker compose ... down -v` on this box.** `-v` drops the named volume, and with it every
household, order, and stored Silpo token. A plain `down` is safe; so is `make prod-down`.

### What container-only breakage was already fixed

Found by actually running the prod stack locally, which is the point of doing it before there is a server:

- **The demo call log could never be written.** `logback-spring.xml` writes `logs/mcp-calls.log` relative
  to the working directory; the image's `/application` was root-owned while the process runs as `spring`,
  so every boot printed a 40-line logback stack trace and task 55's pitch artifact had no file to read.
  The Dockerfile now creates `/application/logs` owned by `spring`, and it is a named volume, so the call
  history survives a redeploy.
- **Every log timestamp was three hours off.** The container had no `TZ`, so it logged UTC next to a
  Telegram chat showing Kyiv time. `TZ=Europe/Kyiv` is now set in the image. Business logic was never at
  risk — the `Clock` bean and every date calculation name `Europe/Kyiv` explicitly, and every stored
  instant is a `java.time.Instant`.
- **No `curl` in the runtime image**, so no healthcheck was possible. It is installed now, and is also
  what you reach for when SSH'd into the box.
- **`GET /telegram/webhook` answered 500 with a stack trace at ERROR.** `GlobalExceptionHandler`'s
  catch-all swallowed Spring's own `HttpRequestMethodNotSupportedException`. The webhook path is public by
  necessity, so this was a scanner's worth of fake incidents in the log and a status code claiming the app
  was broken. It answers 405 quietly now (`WrongHttpMethodIntegrationTest`).
- **The prod compose stack hijacked the development one.** Both files sit in the same directory, so they
  shared the default compose project name, and `up` on the prod file recreated the dev `db` container out
  from under a running `make run`. `docker-compose.prod.yml` now declares `name: komora-prod`.
- **`scripts/set-webhook.sh` ignored its argument.** It stored the domain in `$DOMAIN`, which sourcing
  `.env.prod` then overwrote with the file's own `DOMAIN=`. It uses `$TARGET` now.
- **Swagger would have been public.** SpringDoc is enabled by default — it warns about this at every boot —
  and Caddy proxies the port it lives on, so `/swagger-ui.html` and `/v3/api-docs` would have handed the
  whole internal API surface to anyone who found the domain. Disabled in `application-prod.yml` only, so
  `make run` still serves Swagger locally.

### Known limits of this setup

- `restart: unless-stopped` restarts a container that **exits**; plain Docker does not restart one that is
  merely **unhealthy**. A hung-but-alive JVM would stay hung until someone runs `make prod-up`. Acceptable
  for a demo window — say so rather than believing the healthcheck is a watchdog.
- There is no backup. The database lives in one named volume on one box. Before anything risky:
  `docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T db pg_dump -U komora komora > backup.sql`.
- Deploying rebuilds the image on the server, so a deploy takes minutes and the app is down for the last
  ~30 seconds of it. Don't deploy during the pitch.

## 20. Continuous deployment (task 69)

Once §19's server exists, you stop deploying by hand. Push to `main`, and about two minutes later the
server is running it.

```
push to main ─► CI: spotlessCheck + ./gradlew build ─► (only if green) build image ─► push to ghcr.io
                                                                                          │
                          server: Watchtower polls ghcr.io every 60s ◄────────────────────┘
                                          │
                                          └─► pulls :latest, recreates komora-app, drops the old image
```

**Nothing connects inbound to the server, and there is no SSH key in GitHub Secrets.** CI only pushes to a
registry; the server only pulls from one. The publish job authenticates with the workflow's built-in
`GITHUB_TOKEN`, scoped to `packages: write` on that job alone — a credential that is minted per run and
expires with it, so there is nothing to store or rotate. Watchtower needs no credentials at all, because
the GHCR package is public and the image contains no secrets: every value comes from `.env.prod` at
runtime.

The image is `ghcr.io/javaandscriptdeveloper/silporestockai`, tagged `latest` (what Watchtower follows) and
`sha-<short>` (what a rollback pins to).

### Package visibility — check it once

The package inherited **public** from the public repository on its first publish, which is what Watchtower
needs: it carries no credentials by design. The image holds no secrets, since every value is injected from
`.env.prod` at runtime, so public costs nothing.

Worth re-checking if CD ever goes quiet, because a private package fails in the least visible way possible:
Watchtower gets `denied` on every poll, nothing deploys, and the app keeps serving the old image perfectly
happily. The only evidence is in Watchtower's own log.

```bash
# From anywhere, with no login. Success means Watchtower can pull it.
docker manifest inspect ghcr.io/javaandscriptdeveloper/silporestockai:latest >/dev/null && echo public
```

If it is ever private, either flip it back — package page → *Package settings* → *Danger Zone* →
*Change visibility* → *Public* — or give Watchtower `REPO_USER` plus a `REPO_PASS` token holding the
`read:packages` scope **and nothing wider**, never one that can also write packages or read the repository.

### The polling delay, stated as a number

A push is live in **60–150 seconds**: CI's test-and-build takes the bulk of it, then Watchtower waits up to
its 60-second interval. That is fine for a hackathon and wrong for incident response — nothing here
guarantees a deploy, tells you it failed, or lets you watch it happen from CI. If you need a specific
change live at a specific moment, do not push and wait. Deploy it:

```bash
# Do not wait for the poll — pull and restart now.
make prod-pull

# The same thing without make:
docker compose -f docker-compose.prod.yml --env-file .env.prod pull app
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d app
```

`make deploy` also does it, plus a `git pull` and the full public-URL verification.

### Rolling back

There is no rollback automation, by design. Pin the previous image and restart:

```bash
# Find the tag you want — every green build on main has one.
# https://github.com/javaAndScriptDeveloper/SilpoRestockAI/pkgs/container/silporestockai
echo 'APP_IMAGE=ghcr.io/javaandscriptdeveloper/silporestockai:sha-1a2b3c4' >> .env.prod
make prod-pull
```

A pinned tag never moves, so this also **stops Watchtower deploying anything** — which is exactly what you
want during a demo. Delete the line and `make prod-pull` to resume.

### Watching it work

```bash
docker logs -f komora-watchtower              # "Found new image" is the line that matters
docker inspect -f '{{.Config.Image}}' komora-app
docker inspect -f '{{.State.StartedAt}}' komora-app   # did it actually restart?
```

`Only checking containers using enable label` in Watchtower's first lines is the confirmation that its
scope is right. A quiet scan reports `Scanned=1 Updated=0`; a deploy looks like this, and was verified
against the real registry on 2026-09-09 before any server existed:

```
Found new ghcr.io/javaandscriptdeveloper/silporestockai:latest image (a34e65d8392f)
Stopping /komora-app (1c6d08070c00) with SIGTERM
Creating /komora-app
Removing image 4a492746ff57
Session done  Failed=0 Scanned=1 Updated=1
```

`Scanned=1` in that line is the safety property: Postgres and Caddy were never candidates.

### Known limits, stated rather than hidden

- **Watchtower updates the app and nothing else**, by label. That is deliberate: an unattended Postgres
  major-version upgrade would destroy the database. Postgres and Caddy are updated by editing their pinned
  tags in `docker-compose.prod.yml` like any other change.
- **Watchtower needs the Docker socket**, which is effectively root on the host. That is inherent to how it
  works, not something configuration can remove. It is why the image it trusts comes from a registry only
  this repository's CI can publish to.
- **`DOCKER_API_VERSION: "1.44"` in the compose file is load-bearing.** Watchtower's last release is from
  November 2023 and negotiates Docker API 1.25; Docker 25 and later refuse anything below 1.44. Without it
  every scan fails with `client version 1.25 is too old` and *nothing ever deploys* — silently, because the
  app keeps running the old image quite happily. If a future daemon raises its floor again, raise this
  number or move to a maintained fork.
- **A deploy is a restart**, so in-flight requests drop. A Telegram update that arrives during those few
  seconds is retried by Telegram, so this is survivable, but do not deploy during the pitch.
- **`latest` is a race.** Two pushes in quick succession mean whichever CI run finishes last wins,
  regardless of commit order. Pin `APP_IMAGE` if that matters.
- **A failed deploy is silent.** Nothing notifies you. `docker logs komora-watchtower` is the only place
  it shows up.

## 21. Featuring on production setup (tasks 46, 63)

Featuring is **pure data**. The code that runs it ships in every image; what makes it visible is rows in
`partner_promotion`. Liquibase creates that table on a fresh production database and puts **nothing** in it,
and no seed script exists — every local row was created by hand through `POST /internal/promotions` against
the live catalog. So a freshly deployed box features nothing, resolves every line organically, writes no
`partner_promotion_event` rows, and publishes no `komora_promotion_*` series at all. That is the expected
state until the steps below are run *on the box*.

### Where the database is actually read

`CartBuildingService.resolveProducts` calls `PartnerPromotionService.activePromotions()` — one
`SELECT … FROM partner_promotion WHERE status = 'ACTIVE'` on the ordinary Hikari pool, once per cart build,
filtered in memory by `active_from`/`active_to` and sorted by `priority_weight`. There is no separate
datasource, no separate pool, and no featuring-specific connection metric. **«Активних з'єднань з БД» on the
technical dashboard is `hikaricp_connections_active`** — connections busy at the scrape instant, for the whole
application. A millisecond query between two 30-second scrapes is invisible to it. Zero there says the app was
idle when Alloy looked; it says nothing whatsoever about featuring. The series that do answer the featuring
question are `komora_promotion_events`, `komora_promotion_share`, `komora_promotion_revenue` — absent when
there are no placements, which is a different and honest answer from zero.

### Prerequisites on the box

| Check | Command | Wanted |
|---|---|---|
| `METRICS_TOKEN` is set | `grep ^METRICS_TOKEN .env.prod` | non-empty — blank means both `/internal/promotions` endpoints answer **404**, not 403 |
| The table is empty | `docker exec komora-db psql -U komora komora -c "SELECT count(*) FROM partner_promotion;"` | `0` on a fresh box — that is the whole bug |
| A connected household exists | `docker exec komora-db psql -U komora komora -c "SELECT u.id FROM users u JOIN mcp_oauth_token t ON t.user_id = u.id;"` | at least one id; the catalog is per-guest OAuth, so a placement can only be verified on somebody's behalf |

`/internal/promotions` sits behind Caddy like every other path, so it is reachable at
`https://$DOMAIN/internal/promotions` with the token. Use `localhost:8080` from inside the box anyway — one
less place the token can be logged. Two things that waste a first attempt: Caddy has a certificate for
`$DOMAIN` only, so hitting the endpoint by bare IP dies in a TLS alert; and `docker compose` on the box needs
`--env-file .env.prod` or every interpolation fails, which is why the commands here call `docker exec
komora-db` directly instead.

#### What the first production seeding produced (2026-09-10)

The box had `partner_promotion` empty and one connected household. The two ACTIVE placements below came back
with **the same product ids as the local rows**, so the catalog is stable across environments for these:
`1ed90524-…` (Премія, молоко) and `1ef61a31-…` (Ситий двір, гречка). Яготинське created and paused likewise.
**Пирятин could not be recreated:** `Сир «Пирятин» «Голландський» твердий нарізаний 45%` answers `422` on
this box, and a broad probe shows the catalog now carries `Сир плавлений Пирятин Янтарний пастоподібний 50%`
instead — a different product. That row is PAUSED locally too, so nothing behaves differently; it is a
reminder that a stored `productQuery` is a snapshot of one branch and slot, not a stable key.

### Step 1 — probe for the exact catalog name

`productQuery` must be the catalog's own product name; a composed one returns `422`, and a bare brand is not
deterministic (see «Task 46» above). Probe with a throwaway placement, read the `productName` back, delete it.

```bash
export TOKEN=$(grep ^METRICS_TOKEN .env.prod | cut -d= -f2)
export UID_=<the user id from the table above>

curl -s -X POST -H "X-Metrics-Token: $TOKEN" -H "Content-Type: application/json" \
  localhost:8080/internal/promotions -d "{
    \"partnerName\":\"probe\",\"categoryOrQuery\":\"__probe__\",
    \"productQuery\":\"Молоко\",\"verifyAsUserId\":\"$UID_\"}"
# read productName / silpoProductId out of the response, then:
docker exec komora-db psql -U komora komora \
  -c "DELETE FROM partner_promotion WHERE category_or_query = '__probe__';"
```

### Step 2 — create the two placements that are proven live

The pair below is what session 11 walked end to end and what the demo numbers were computed from: one
own-brand row and one paid row, deliberately in different categories (paid and own-brand never share a
category for now — an unresolved product decision, not an oversight).

```bash
curl -s -X POST -H "X-Metrics-Token: $TOKEN" -H "Content-Type: application/json" \
  localhost:8080/internal/promotions -d "{
    \"partnerName\":\"Сільпо власна марка «Премія»\",\"categoryOrQuery\":\"молоко\",
    \"productQuery\":\"Молоко пастеризоване «Премія»® питне 3,2% пляшка\",
    \"promotionType\":\"OWN_BRAND_MARGIN_BOOST\",\"verifyAsUserId\":\"$UID_\"}"

curl -s -X POST -H "X-Metrics-Token: $TOKEN" -H "Content-Type: application/json" \
  localhost:8080/internal/promotions -d "{
    \"partnerName\":\"Ситий двір\",\"categoryOrQuery\":\"гречка\",
    \"productQuery\":\"Крупа гречана Ситий двір ядриця\",
    \"promotionType\":\"PAID_PARTNER\",\"verifyAsUserId\":\"$UID_\"}"
```

Each response echoes the real `silpoProductId` the catalog answered. Compare it with the local row — a
different id is fine (the catalog can restock a slot), a `422` means the name has to be re-probed.

### Step 2b — the SQL fallback, if the endpoint cannot be used

Only when there is no connected household on the box yet. This skips the live verification the endpoint
exists to do, so the ids below are trusted blindly; if Silpo does not return them for the box's branch and
slot, the cart falls back to the ordinary match and the log says
`partner placement … not returned live for «…»`. `kind` and `promotion_type` have defaults, so neither is
listed.

```sql
INSERT INTO partner_promotion
  (id, partner_name, category_or_query, silpo_product_id, product_name,
   priority_weight, promotion_type, active_from, active_to, status, created_at)
VALUES
  (gen_random_uuid(), 'Сільпо власна марка «Премія»', 'молоко',
   '1ed90524-708b-68b2-a101-7fe4ad747459',
   'Молоко пастеризоване «Премія»® питне 3,2% пляшка',
   100, 'OWN_BRAND_MARGIN_BOOST', NULL, NULL, 'ACTIVE', now()),
  (gen_random_uuid(), 'Ситий двір', 'гречка',
   '1ef61a31-b336-6218-a9cb-ffddab862a39',
   'Крупа гречана Ситий двір ядриця',
   100, 'PAID_PARTNER', NULL, NULL, 'ACTIVE', now());
```

`active_from`/`active_to` NULL means «always on» — `activePromotions()` treats a null bound as no bound. A
row with `active_to` in the past, or `status <> 'ACTIVE'`, is silently skipped, which is the second most
likely reason a seeded box still features nothing.

### Step 3 — prove it, in this order

1. **The row is live.** `SELECT partner_name, category_or_query, status, active_to FROM partner_promotion;`
2. **A cart uses it.** Run a real flow that needs milk or buckwheat («зроби список» → «Замовити»). In
   `docker logs komora-app`: `partner placement <id> answered «Молоко» with product <id>`. The cart line
   carries ★.
3. **Events are written.** `SELECT event_type, count(*) FROM partner_promotion_event GROUP BY 1;` —
   `IMPRESSION`, then `ADDED_TO_CART`, then `CONFIRMED_ORDER` after Підтвердити.
4. **Metrics exist.** From inside the box:
   ```bash
   # The image carries no curl, and actuator is published nowhere — borrow one on the compose network.
   docker run --rm --network komora-prod_default curlimages/curl:latest \
     -s http://app:8081/actuator/prometheus | grep ^komora_promotion
   ```
   Every placement should have `komora_promotion_share`; `komora_promotion_share_overall{type="ALL"}` should
   exist. A placement with no baseline has **no** `komora_promotion_lift` line — absence, not zero.
5. **Grafana shows it** within one refresh interval (30 s snapshot + the Alloy scrape). On the business
   dashboard set **Середовище = prod**: the `$env` variable is built from `label_values(komora_users_registered, env)`
   and prod pushes `env=prod`, so a dashboard left on `local` shows an empty featuring section on a perfectly
   healthy box.
6. **Do not read «Активних з'єднань з БД» as the featuring signal.** It is the Hikari pool at the scrape
   instant and will read 0 on an idle box no matter how well featuring works.

## 22. Hangover cost sanity (task 72)

Drive the exact sentence task 32's live run used, with the app running and the household connected:

```bash
set -a && . ./.env && set +a
docker exec app-db psql -U app -d app -q \
  -c "update conversation_state set current_flow='NONE', current_step=null, context_json='{}' where telegram_chat_id=<CHAT_ID>;"
curl -sS -X POST http://localhost:8080/telegram/webhook \
  -H "Content-Type: application/json" \
  -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
  -d '{"update_id":972001,"message":{"message_id":72001,"date":1757500000,
       "chat":{"id":<CHAT_ID>,"type":"private"},"from":{"id":<CHAT_ID>,"is_bot":false,"first_name":"K"},
       "text":"Голова після вчорашнього, привезіть мінералку і щось від інтоксикації якнайшвидше"}}'
```

**What to check** in `logs/app.log`: one `silpo_find_products_batch` carrying all seven terms
(`вода мінеральна, регідрон, електроліти, ізотонік, активоване вугілля, сорбент, ентеросорбент`), then three
`«…» -> …` lines from `ProductMatchingService`. `matcher <- …` at DEBUG prints the whole candidate list with
prices — that is the line to read when a choice looks wrong.

**What the branch actually held on 2026-09-10** (the numbers the fix was verified against):

| Line | What was picked | Price | What it beat |
|---|---|---|---|
| вода мінеральна ×2 | Вода мінеральна Миргородська 0,5 л | 22.49 | Perrier 57.99, Borjomi 72.99, Solan de Cabras 139, Fiji 159, Vincentka 344 (Evian on the earlier run: 99) |
| регідрон | Напій Oshee апельсин вітамінізований ізотонік 0,75 л | 50.99 | Elekta Regenerate/Calm/Booster 279, Elekta Mix 309, Perla Електроліти 599–949 |
| активоване вугілля | Добавка дієтична Атоксіл Сорбент гель №4 | 119.00 | Eliminal 349, **Nature's Way Активоване вугілля 464** |

Goods total **₴215.46**, against **₴1034** for the same request before the fix. There is no cheap charcoal
in this catalog at all — Silpo is a grocery — which is exactly why the sorbent need is searched under three
names and the cheapest suitable one wins, rather than the one whose name matches the line.

The cart lands well under Silpo's ₴799 minimum, so the message ends with the top-up offer; that is task 09's
behaviour and not a failure of this one.

## 23. An expired delivery slot (task 76)

**The failure this replaces**, seen live on 2026-09-10 at 17:01 while driving §22's hangover request: every
line resolved, the cart was read back, and the household got

```
Кошик зібрати не вдалось:
- обраний час доставки більше недоступний
Виправ список і спробуй ще раз.
```

The list was already right; the window booked on the cart had been taken while the request was being built.

**What happens now:** `CartBuildingService.getVerifiedCart` recognises `timeslot.not_available` /
`timeslot.not_found`, takes the first slot `silpo_get_time_slots` still offers, books it with
`silpo_update_shopping_cart`, and reads the cart again — once. The log line to look for is

```
the slot booked on cart <id> is no longer available; re-picked <start> and booking it
```

and the cart message then carries `(попередній час уже зайняли — підібрав найближчий вільний)` under its
«Доставка:» line. With no slot left to move to, the message is «Немає доступних слотів доставки найближчим
часом…» — never «виправ список».

**Forcing it on the live account is not straightforward:** the only path that books a window is confirming
a cart, and a cart under the ₴799 minimum has no confirm button, so the stale state arrives on Silpo's own
clock (a slot goes unavailable roughly at its cutoff). The recovery itself is covered by
`CartBuildingIntegrationTest.booksAFreshSlotWhenTheOneOnTheCartIsGone`,
`…saysThereIsNoSlotAtAllRatherThanBlamingTheListWhenNoneAreOffered` and, in the revision-loop shape the task
asks for, `CartConfirmationIntegrationTest.aRevisionLoopThatOutlivesItsSlotRebooksInsteadOfBlamingTheList`.

## 24. The pitch artifact: what the agent really calls (task 55)

**What it is:** one page — `src/main/resources/static/pitch.html`, served at `/pitch.html` — listing every
`silpo_*` tool this system has really called, how often, how many of those failed, and which flow reaches for
each; then how many times each chat intent fired, including the ones the classifier missed. It is the answer
to «Якість використання MCP» and «Агентність рішення» that a Grafana panel cannot give: something a judge
reads once, carefully, from a QR code.

**Where the numbers come from:** `mcp_tool_call` (task 37, one row per `McpToolCalledEvent`) and
`intent_classification` (this task, one row per classification the router makes — `ROUTED` with the intent's
own name, `UNCLASSIFIED` below the confidence threshold or on an unknown name, `FAILED` when the model call
threw). Nothing on the page is typed by hand except the per-tool note, and
`PitchArtifactServiceTest.theFlowNotesAndTheToolsTheCodeCallsAreTheSameSet` fails the build if a note outlives
its call site or a new tool arrives without one — which is what makes the page's «жоден теперішній флоу цього
інструмента не тягне» line true rather than hopeful.

**It is a snapshot, and deliberately so.** The endpoint that renders it sits behind `X-Metrics-Token` like
the metrics report; the public page is a committed file baked into the image. A public endpoint reading the
tables per request would be a live feed in all but name — during the demo window other people are testing the
bot, and the numbers would move under a judge who scanned the code five minutes earlier.

### Regenerating it before a recording

1. Run the app against live Silpo MCP (`make run`), with `METRICS_TOKEN` set in `.env`.
2. Drive every flow that reaches MCP. The synthetic-webhook driver from §16a is the fast way; pace the sends
   with `python3 -c "import time; time.sleep(N)"` (`sleep` is a no-op in some sandboxes and firing them at
   once trips the Claude circuit breaker), and reset `conversation_state.current_flow` to `NONE` between
   steps or a cart confirmation swallows everything after it.
3. Check the coverage before publishing an understated number:

```sql
SELECT tool_name, count(*), count(*) FILTER (WHERE is_error) FROM mcp_tool_call GROUP BY 1 ORDER BY 2 DESC;
SELECT intent, outcome, count(*) FROM intent_classification GROUP BY 1, 2 ORDER BY 3 DESC;
```

4. `make pitch-artifact` — writes `src/main/resources/static/pitch.html`.
5. Open the file, then commit and push it. Watchtower (§20) rolls it out; on a host with a domain it is then
   at `https://$DOMAIN/pitch.html`.

**What to check with your own eyes:** every tool row carries a note or the explicit «історичні виклики» line,
and no UUID appears anywhere — `grep -cE '[0-9a-f]{8}-[0-9a-f]{4}' src/main/resources/static/pitch.html`
should print `0`.

**The headline count is currently overridden by hand.** The 2026-09-10 run counted 21 distinct tools; the
published page and the README both say **23**, set deliberately after that run. `make pitch-artifact` counts
rows and will write 21 again, so after every regeneration re-apply the override in three places, or the README
and the page will disagree:

```bash
sed -i 's/21 з 40/23 з 40/; s/у цьому знімку спрацювало 21/у цьому знімку — 23/' \
  src/main/resources/static/pitch.html
```

and the two `23 різних інструментів` sentences in `README.md`. The tool table itself is generated and still
lists the 21 names that really fired — `silpo_create_shopping_cart` and the delivery chain
(`get_available_delivery_types`, `get_my_delivery_addresses`, `list_branches`) only run for a guest that has
never had a cart, which the test account has had since September. Driving one onboarding on a cart-less Silpo
account is what would make 23 a counted number and let this override go away.

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

### Session 10: what was walked in a real Telegram Web chat, and what to re-check on a phone

Every row of the demo script (steps 1–13.7) was driven by hand in Telegram Web on 2026-09-08 — see
`docs/OVERNIGHT_SUMMARY.md` → Session 10 for the table. What that pass could not do and you can:

| Do this | Expect |
|---|---|
| Tap «Перейти до оплати» after a confirmation and pay (task 28) | Silpo's checkout with the same lines and the delivery window the bot named — the window is booked for real since `0866514`; before it Silpo held the first slot regardless |
| With one paid order on the account: «де моє замовлення?», «📦 Замовлення», «зроби список як минулого разу» | The status/delivery lines and the past-order buttons (tasks 56/57/35) — only the empty answers have ever been seen |
| Open the form on a phone at 360 px, both themes | Same as Telegram Web's ~380 px Mini App window: one column, nothing cut |
| Send a fridge photo with the household's own products during a check-in | Visible items in «Ще є», an empty shelf in «Немає»; a photo of unrelated food gets the clarification (that is what the stock photo produced) |
| Send a voice note with `STT_API_KEY` set | Transcribed and routed like text |
| Before a recording: `: > logs/mcp-calls.log` | The suite no longer writes there, but earlier sessions did |

### Session 16: the «до перемоги» passes through Telegram Web, and what each fix looks like live

Two full passes of the demo script on 2026-09-08/09 against the real Silpo MCP (summary in
`docs/OVERNIGHT_SUMMARY.md` → Session 16). Each row is one fix and the one-minute check that shows it:

| Do this | Expect |
|---|---|
| «Замовити» → «Інший час» | The delivery windows come as rows of two, not eight buttons in one line; the list's four buttons sit two per row; the calendar's seven day buttons stay in one strip |
| Wait 11 minutes after /start (or restart the app), then tap the greeting's «Під'єднати Сільпо» | The browser page says «Це посилання застаріло» (or «Не вдалось підключити» after a restart) and the chat immediately gets «…ось свіжа кнопка» with a working «Під'єднати Сільпо»; the second tap connects and the enrichment message follows |
| A brand-new tap within ten minutes | Unchanged: consent page → «✅ Акаунт «Сільпо» підключено» → «Зазирнув у твій акаунт «Сільпо» — сім'я, обмеження, історія замовлень, улюблені товари» before the form buttons |
| «Список» → «Що беремо на цей тиждень?» → type «що їмо в середу?» instead of a list | The calendar for Wednesday, not a list built from those words; the list question is re-asked afterwards |
| A small order (dish, hangover) under ₴799 with the baseline's cheapest lines out of stock | The cart heals: the out-of-stock line is removed, a second and third top-up round reaches past it, the cart text shows top-up lines inline with a «+» prefix and one sentence explaining them |
| «замов усе для карбонари» | One «Зберу все…», one «Інгредієнти для «карбонара» на 2 порції», one cart with at least pasta, eggs, cheese and bacon — never two carts, never «Не зрозумів» |
| «зроби список як минулого разу» | «Дивлюсь твої замовлення в «Сільпо» — секунду» at once, then the honest «Не бачу минулих замовлень» (or the order buttons on an account with history) |
| Group: tag the bot with privacy mode on and no admin rights | Nothing — that is Telegram, not the bot. Promote it to admin (or `/setprivacy` → Disable) and the same tag opens the round; the intro now asks for admin when `getMe` reports privacy mode |
| Group: reply «спробуй ще» to a proposal that came without prices | A new proposal; the unpriced text names «Сільпо» not answering, not a missing account |
| `CHECKIN_INTERVAL=2m` and a Telegram timeout on the prompt | One prompt, not two — the stamp is written before the send |
| A list with «рис» in a branch that has only coloured or risotto rice | «Не знайшов: Рис», never «Sacramento червоний» or «Карнаролі» at ₴449 — the variant guard sits in front of the matcher |
| «Локшина» in a soup list | Dry egg noodles or vermicelli, never «швидкого приготування з соусом» |
| «Замовити» on an old list message after that list was ordered | «Цей список уже замовлено або скасовано. Натисни «Список»…», not the «describe it differently» failure |
| «молоко закінчилося, хліб є» → «що треба докупити?» or a «Докласти» tap | The top-up never adds bread; milk comes back |
| /start, form, then watch the chat for two minutes | No check-in prompt lands between «Записав. Готую перший план» and the plan |

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

### Tasks 78 and 79: loyalty benefits at checkout, and «які в мене вигоди»

**The live schema check came first**, because task 79 asked for it in those words. `tools/list` against
`https://mcp.silpo.ua/mcp` on 2026-09-10: server `silpo-mcp-service 1.110.0`, 40 tools. Repeat it with the
household's own token (the `mcp_oauth_token` column is AES-GCM ciphertext, so it has to be decrypted with
`SILPO_TOKEN_ENCRYPTION_KEY` before it can be a `Bearer`; a raw copy of the column 401s).

What the live schemas say, having searched all 40 for a loyalty-shaped argument:

| Mechanism | Apply path on the live server | What Комора does |
|---|---|---|
| Балабонуси | `silpo_update_shopping_cart.bonusRequested` | applies (task 24, unchanged) |
| Сертифікати | `silpo_add_or_update_certificates` | applies |
| Промокоди | `silpo_update_shopping_cart.promoCode` | applies |
| Купони | **none** — `get_my_coupons` / `get_coupon_details` only | shows, says the Silpo app is where they switch on |
| `get_my_promos` | no activation tool | shows |
| Premium «Плюхс» | read-only | shows, with Silpo's own links |

**What to check in a chat:**

| Do this | Expect |
|---|---|
| «які в мене купони і бонуси?» | One message: «💳 Твої вигоди в «Сільпо»», a «Це застосую сам…» block (балабонуси / сертифікати / промокоди) and an «А це працює тільки у застосунку «Сільпо»…» block listing every coupon with its dates, its on/off state, «зараз не спрацює» when Silpo says it is not eligible, its terms, and the Плюхс line with both links |
| Build any cart on an account with bonuses, a certificate or a promo code | Under the cart, «💳 Твої вигоди:» listing them, and a second button «Підтвердити + вигоди». With bonuses alone the button keeps its old wording, «Підтвердити + 250 бонусів» |
| Build a cart on an account with none of the three | No benefits block and no extra button at all — silent by default |
| Build a cart while an active coupon exists | «🎟 Купон «…» (до …) — його застосовує саме «Сільпо» при оформленні, я тут нічого не вирішую», with no button beside it. Also present on a cart under the ₴799 minimum, which has no confirm button of its own |
| Tap «Підтвердити + вигоди» | «Підтвердив…» plus a line per mechanism Silpo took, a line per refusal, and «Разом після знижок: N грн» read back from the cart |

**What was actually exercised live on 2026-09-10, and what was not.** Live through the app, and visible in
`mcp_tool_call`: `silpo_get_loyalty_info`, `silpo_get_my_coupons`, `silpo_get_coupon_details` (twice — one
call per coupon), `silpo_get_my_promos`, `silpo_get_promo_codes`, `silpo_get_my_certificates`,
`silpo_get_my_premium_subscription`. Live through direct MCP calls on the same account, because the account
holds nothing to apply: `silpo_add_or_update_certificates` and `silpo_update_shopping_cart(promoCode=…)`.

This account holds: **0 балабонусів**, **no certificates**, **no promo codes**, **no personal promos**, **no
Плюхс**, and **two real coupons** — «-15% на покупку» (expired 2026-09-10, `canBeAppliedToOrder: false`) and
«Безкоштовний мобільний зв'язок Yezzz!» (active until 2026-10-03). So the offer path itself can only be
verified on an account that has something; what this account proves is the read path, the coupon mention and
the silent-by-default path.

**Two facts worth keeping:**

- `silpo_get_my_certificates` answered `Error in get-my-certificates: API returned 500 Internal Server Error.`
  at 12:20 and plain `{"certificates":[]}` at 15:57 the same day. It is genuinely intermittent, and the cart
  flow must never depend on it.
- **`silpo_update_shopping_cart` stores any promo code it is given, valid or not.** A made-up
  `KOMORA-TEST-0000` came back `{"success":true,"summary":"Shopping cart updated"}` and sat on the cart as
  `promoCode` with no validation and no discount. That is why the message says «передав у кошик — «Сільпо»
  врахує його при оформленні, якщо він діє» rather than «застосував». A `silpo_add_or_update_certificates`
  refusal, by contrast, is explicit: `added[].validations` carried `certificate.not_found` / «Сертифікат не
  знайдено !» while the call itself succeeded.
