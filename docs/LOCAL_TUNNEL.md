# Local dev tunnel for the Telegram WebApp form

Telegram requires a public HTTPS URL both for the webhook (`POST /telegram/webhook`) and for
the onboarding Mini App (`GET /webapp/onboarding.html`, opened inside Telegram's own WebView).
Locally that means a tunnel into `localhost:8080`. This documents what was tried, what actually
works, and the operational gotchas that look like tunnel bugs but aren't.

## The core problem: free tunnel "browser warning" interstitials

Both **ngrok** (free tier) and **serveo.net** (free tier) show an interstitial "are you sure you
want to visit this site" warning page to real-browser traffic, to stop the service being used for
phishing. Telegram's in-app WebView **is** a real browser and gets the warning page — the Mini App
renders blank (ngrok) or shows the tunnel's own warning banner (serveo) instead of the form.

- ngrok: only a **paid plan** removes it. A free static/reserved domain does **not**.
- serveo: same interstitial on the free tier, even with a registered SSH key for a custom
  subdomain — a stable domain was tried here (`scripts/tunnel-supervisor.sh` used to run this)
  and hit exactly this: the WebApp URL loaded fine outside Telegram, then showed serveo's own
  "click to continue" warning inside Telegram's WebView, with no way to tap through it.

## What actually avoids the interstitial: localhost.run

`localhost.run` (SSH-based, `*.lhr.life` domains) does not show this warning page. No install, no
account needed:

```bash
ssh -R 80:localhost:8080 nokey@localhost.run
```

The tradeoff: the free tier's subdomain **changes on every reconnect** — there is no free way to
pin it to a fixed name (a fixed name is a **paid** Custom Domain plan, $9/mo, at
`https://admin.localhost.run`). `scripts/tunnel-supervisor.sh` removes the pain that tradeoff
used to mean.

## `scripts/tunnel-supervisor.sh` — the supervisor

Keeps the tunnel alive, and on every (re)connect:
1. reads the newly assigned `*.lhr.life` domain out of the SSH banner,
2. rewrites `TELEGRAM_WEBHOOK_URL` and `TELEGRAM_WEB_APP_BASE_URL` in `.env`,
3. restarts the app so it picks up the new URL and re-registers the webhook.

```bash
./scripts/tunnel-supervisor.sh
```

Leave it running in its own terminal (or `nohup ... & disown` it). Nothing else needs touching by
hand afterward — a dropped connection reconnects, updates `.env`, and restarts the app on its own.

Confirm it worked:

```bash
grep "registered the Telegram webhook" logs/app.log | tail -1
```

## Gotchas that look like the tunnel is broken but aren't

**1. A Telegram message's button is baked in at send time — it doesn't update.**
The WebApp button URL is embedded in whatever message Telegram already delivered. If the tunnel
URL changes *after* that message was sent (a reconnect happened), tapping the old button hits a
dead URL — this looks identical to "the tunnel is broken" but the tunnel is actually fine; the
button is just stale. Fix: trigger a **new** message, don't retap an old one.

**2. Onboarding only runs once per user — `user_profile` existing skips it entirely.**
`OnboardingFlowService.isOnboarded()` checks for a `user_profile` row. To redo onboarding for a
test account, reset it (swap in your own `telegram_chat_id`, find it with
`SELECT telegram_chat_id FROM users;`):

```bash
docker exec -i app-db psql -U app -d app <<'SQL'
DELETE FROM shopping_list_item WHERE user_id = (SELECT id FROM users WHERE telegram_chat_id = <CHAT_ID>);
DELETE FROM meal_plan WHERE user_id = (SELECT id FROM users WHERE telegram_chat_id = <CHAT_ID>);
DELETE FROM conversation_state WHERE telegram_chat_id = <CHAT_ID>;
DELETE FROM user_profile WHERE user_id = (SELECT id FROM users WHERE telegram_chat_id = <CHAT_ID>);
SQL
```

Then send `/start` fresh — this generates a brand-new message with a button pointing at whatever
URL is *currently* in `.env`.

**3. `port 8080 already in use` right after a reconnect.**
If the app was started outside the supervisor (e.g. a plain `make run` in another terminal) and
that process is still holding `:8080` when the supervisor tries to restart it, the restart fails.
The supervisor's own restart already force-frees the port (`fuser -k 8080/tcp`) before starting a
new one — if you see this anyway, something outside the supervisor is holding the port; find it
with `ss -ltnp | grep 8080` and stop it.

## If localhost.run's free tier also turns out unreliable

- **Paid ngrok** (~$10/mo Personal plan) removes the interstitial outright, with a static domain.
- **localhost.run Custom Domain** ($9/mo) does the same on the same service already in use here —
  no need to switch providers, just add a plan.
- Cloudflare quick tunnels (`cloudflared tunnel --url http://localhost:8080`) are free and have no
  interstitial by design, but hand out a new random domain every run (no stable-domain story at
  all, free or paid, without a full Cloudflare Tunnel + owned domain setup) and were unreachable
  from this dev sandbox's network specifically when last tried — untested from a normal network.
