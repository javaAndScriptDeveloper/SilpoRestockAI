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
set -a; source .env; set +a
make run
```

`make run` starts Postgres through docker-compose automatically. `make dev` is the alternative — a
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

Answer the questions as they come:

| Question | Send | Note |
|---|---|---|
| «Скільки вас удома?» | `2` | «двоє» works too — Ukrainian numerals are parsed |
| «Є алергії чи дієтичні обмеження?» | `нема` | or `лактоза, горіхи` |
| «Що вдома точно не їдять?» | `печінка` | or `нема` |
| «Який бюджет на тиждень, у гривнях?» | `2500` | |

**Expect:** «Записав. Готую перший план на тиждень.»

**Verify:**

```sql
SELECT household_size, dietary_restrictions, disliked_foods, weekly_budget FROM user_profile;
SELECT current_flow FROM conversation_state;   -- NONE
```

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

**Expect:** «План на тиждень готовий, 7 днів. Понеділок: … Список покупок: N позицій.»

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

Everything above is also covered by 238 automated tests against stub servers:

```bash
make test          # unit + integration, needs Docker
./gradlew build    # the above plus formatting and the coverage gate
```

The manual runbook exists for what stubs cannot answer: whether Silpo's real catalogue matches the
words we search for, whether a real transcription is accurate, and whether a real fridge photo produces
a sensible reading.
