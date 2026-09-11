# Task 80 — full E2E pass, operational recording script, value metrics (design)

Date: 2026-09-10 (night, session 25). Classification: architectural — the deliverable is a verified system
plus two Notion documents plus an improved dashboard pair, and the sequence itself is the design.

## Inputs re-read fresh

- <https://ai-factory.silpo.ua/#requirements> — unchanged: deadline 14.09 23:59, deliverables video pitch +
  working demo + docs; six equal criteria (guest/business value, MCP quality, agent capability, integration
  realism, prototype quality, validation & scaling). Jury (Discord, 10.09): classic pitch — problem → why it
  matters → solution → proof → a couple of technical words.
- Task 80 page, «Сценарій демо-запису», «Selling Points», «🎥 Вступний сюжет», PRODUCT_BRIEF, RUNBOOK §16–24,
  OVERNIGHT_SUMMARY sessions 18–24, the Development Plan DB (82 tasks; In review: 28, 35, 55, 63, 64, 66–68,
  70–79, 81).

## Facts that shape the pass

| Fact | Consequence |
|---|---|
| Testing is local: «Батон Степанович» (`@baton_stepanovych_bot`), dev DB `app-db`, local Grafana `:3000` (Alloy → Prometheus `:9090`) | every claim is verified here first; the dashboards are edited here |
| Recording is on prod: «Смузі Геннадійович» (`@smuzi_gennadiyovich_bot`), `https://89.167.115.28.sslip.io`, Grafana Cloud `https://charmingaphid2632.grafana.net/d/komora-business` and `/d/komora-observability`, `env=prod` already pushing (2 households, GMV ₴6 020.72) | both documents name only these; a spot-check greps them for «Батон», `lhr.life`, `ngrok`, `localhost` |
| Prod holds no partner placements yet (seeded per RUNBOOK §21 by hand) | the operational script's set-up notes say so per step |
| The local tunnel supervisor rotates hostnames and restarts the app mid-flow | the pass runs on `scripts/session-tunnel.sh ngrok`; restarts go through `scripts/restart-app.sh` |
| Dashboards are generated (`build-dashboards.py` → JSON, provisioned locally, `make dashboard` pushes to cloud) | improve the generator, restart local Grafana, sync once at the end |
| Two Telegram accounts are logged into Telegram Web (account switcher) | 68 and 81 are driven with A = owner (`218196255`), B = the second account; every step names the account |

## The recording order (= the E2E test order)

Top-level structure is the jury's: **problem → why now → solution → proof → technical words → close.**

| # | Beat | Register | Interactive? | Proves criterion |
|---|---|---|---|---|
| 0 | Animated intro «Машрум vs Смузі» (scenes 1–6 from «🎥 Вступний сюжет») | CPO | no | prototype quality (production value) |
| 1 | Problem & why now: name, Hermes/OpenClaw-of-food, audience widened, war → delivery → forecasting, «food as a service — запрошуємо вас із собою» | CPO | no (slides) | guest/business value |
| 2 | Onboarding: `/start` → «Під'єднати Сільпо» (OAuth) → enrichment from MCP → form → capability reveal → calendar offer | sales | yes | MCP quality, integration realism |
| 3 | Plan → «Список» with price estimate and budget warning (#39/#66) | sales | yes | guest value |
| 4 | Cart build with the console on screen; Technical dashboard RED wave; «Підтвердити» → checkout link | sales | yes | agent capability, MCP quality |
| 5 | Check-in → «що треба докупити?» reorder delta with Silpo savings | sales | yes | guest value, validation |
| 6 | Chat-only intents, rapid: «голова після вчорашнього…» (#32/#72), «замов усе для гречаної каші на молоці» (#36 — hits both placements), «світло вимкнули» (#19/#73), «цей тиждень запара» (#67), «я захворів, гастрит» (#25) | sales | yes | scaling primitive (3) |
| 7 | Scheduled one-off «замов до п'ятниці вино та сир зі знижкою» + «Заплановані» | sales | yes | scaling primitive (2) |
| 8 | Social A: group round in a group chat — [A] tags, [B] replies, [A] «Всі відповіли», [A]+[B] 👍, cart in A's private chat | sales | yes, 2 accounts | primitive (4), viral |
| 9 | Social B: gift — [A] «відправ подарунок @B…», [B] answers the address in their chat, [A] gets a cart with no address | sales | yes, 2 accounts | primitive (4), viral |
| 10 | **Метрики, що доводять цінність** — Business A (intent→order per intent, resolved %, reorders unedited), Business B (GMV moved by this take, Attributed Revenue, FSR), plus DB proof queries | sales | Grafana + psql | validation & scaling, business value |
| 11 | **Пара слів про технічну реалізацію** — one live 🔧 line, `IntentRouterService` enum on screen, four primitives, BYOK / Apache 2.0 / «може стати частиною екосистеми «Сільпо»» | CPO | console + IDE | agent capability, integration realism |
| 12 | Close: QR codes (repo, bot, Business dashboard, pitch.html), «Ми переходимо в food as a service» | CPO | no | — |

One continuous take: nothing later resets what an earlier beat produced. Metrics beat comes after the
activity that populates it.

## Per-step loop (task 80's, applied literally)

1. Decide the input that argues value best (written in the plan before execution).
2. Perform it in Telegram Web against «Батон Степанович».
3. Read the reply, `logs/app.log`, the local Grafana panel, and the DB row.
4. Works? If not: fix greedily, **one commit per fix**, `scripts/restart-app.sh`, re-verify.
5. Is the value argued by a metric? If not and feasible: add it (code + generator), same discipline.
6. Better input found? Swap, redo, keep the better one.
7. Ideas that do not fit → «Selling Points» parking-lot section.

## Grafana: what «actively improve» means here

Business dashboard:
- **Section A leads with the strongest guest proof**: «Намір → замовлення» stays big; per-intent bars next to it.
- **New: «Соціальні канали — люди, яких бот привів сам»** tiles: group rounds by status, participants who
  replied, gift orders by status, recipients asked. Gauges `komora_group_rounds{status}`,
  `komora_group_participants`, `komora_gift_orders{status}` from the DB refresh. This is the metric behind
  primitive (4) and the viral note — today it has no number at all.
- Featuring block unchanged in content; Attributed Revenue first (already).
- Cut nothing that a sentence in the pitch needs; drop «Нових анкет за період» (process counter, resets on
  restart, confuses next to the absolute funnel).

Technical dashboard: keep. Verify the RED wave renders during beat 4; the per-tool leaderboard is the
«N з 40» proof.

Sync to prod once at the end with `make dashboard` (credentials already in `.env`).

## Cross-feature risks to re-verify together

- #78/#79 benefits block + #66 budget warning + #39 price line on one cart message.
- #76 slot re-pick when #78/#79 also touched the cart.
- Ordinary order after a gift resolves to the household's own address (#81 restore).
- Local metrics reflect this pass (Alloy local → Prometheus); the cloud check is a prod dry-run item.
- Intro sequence content flows into beat 1.

## Honest limits, stated up front

- Real payment is not clicked by the agent (the owner's money); the script marks the moment.
- #79 promo/coupon apply path cannot be shown (account holds nothing applicable); coupon *mention* can.
- SelfPickup/NovaPoshta gifts do not exist in the API — never in the script.
- Voice check-in needs `STT_API_KEY` (unset locally) — text path only.
- Group round on prod needs BotFather `/setprivacy` Disable on the prod bot or admin rights in the group.

## Outputs

1. «Сценарій демо-запису» updated in place (narrative shape kept, statuses corrected, cross-link at top).
2. New «🎬 Операційний сценарій запису» page: timecode / Дія / Озвучка / Що показати / продакшн-нотатки per
   step; intro and close marked non-interactive with tool guidance and a Claude Design prompt; DB-proof
   sub-steps with exact SQL and expected rows; every multi-account step labelled [Акаунт A]/[Акаунт B].
3. Dashboards regenerated, verified locally, synced to Grafana Cloud once.
4. `docs/OVERNIGHT_SUMMARY.md` → Session 25; `docs/RUNBOOK.md` → «Session 25» live list; Notion task 80 → In
   review (the recording itself needs the owner).
5. Commits on `main`, pushed (which deploys via GHCR + Watchtower — the owner asked for the push).
