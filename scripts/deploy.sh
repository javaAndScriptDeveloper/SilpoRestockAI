#!/usr/bin/env bash
# Deploy Комора on the demo host (task 59). Run this ON THE SERVER, from the repo root.
#
#   scripts/deploy.sh              # pull the image CI published, restart, verify
#   scripts/deploy.sh --build      # build the image here instead (offline, or a commit CI has not seen)
#   scripts/deploy.sh --no-pull    # skip `git pull` (a hotfix you edited on the box)
#
# Safe to run repeatedly: it is the same command for the first deploy and for every update.
#
# Normally you do not run this at all. Watchtower (task 69) polls ghcr.io and deploys a green build on its
# own; this script is what you reach for to skip the wait, to deploy a commit CI has not built, or to bring
# the stack up the very first time.
#
# What it deliberately does NOT do: run migrations. Liquibase runs inside the app at startup and the
# schema is validated against the entities (ddl-auto: validate), so a changeset that failed to apply
# shows up as a refusal to start rather than as a corrupted schema — which is why the health gate at
# the end of this script is the migration check.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker-compose.prod.yml --env-file .env.prod)
DO_PULL=1
MODE=registry

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-pull)  DO_PULL=0; shift ;;
        # Build the image here instead of pulling what CI published. For working offline, or on a commit
        # that has not been through CI yet.
        --build)    MODE=build; shift ;;
        -h|--help)  sed -n '2,12p' "$0"; exit 0 ;;
        *)          echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done

step() { printf '\n=== %s ===\n' "$1"; }

# ---------------------------------------------------------------------------
step "preflight"
# ---------------------------------------------------------------------------
[[ -f .env.prod ]] || { echo "no .env.prod — copy .env.prod.example and fill it in first" >&2; exit 1; }

# A world-readable file of API keys on a public host. Fix it rather than warn about it.
if [[ "$(stat -c %a .env.prod)" != "600" ]]; then
    echo "tightening .env.prod permissions to 600"
    chmod 600 .env.prod
fi

# Fail here, with a readable message, rather than three minutes later inside Caddy's ACME client.
DOMAIN="$(grep -E '^DOMAIN=' .env.prod | head -1 | cut -d= -f2- | tr -d '"'"'"' ')"
[[ -n "$DOMAIN" ]] || { echo "DOMAIN is empty in .env.prod" >&2; exit 1; }
echo "domain: $DOMAIN"

if command -v dig >/dev/null 2>&1; then
    RESOLVED="$(dig +short A "$DOMAIN" | tail -1)"
    PUBLIC_IP="$(curl -sf --max-time 5 https://api.ipify.org || true)"
    if [[ -n "$RESOLVED" && -n "$PUBLIC_IP" && "$RESOLVED" != "$PUBLIC_IP" ]]; then
        # Not fatal — a proxying CDN or a fresh record legitimately disagrees — but Let's Encrypt
        # rate-limits failed authorizations, so this is worth reading before continuing.
        echo "WARNING: $DOMAIN resolves to ${RESOLVED}, this host's public IP is ${PUBLIC_IP}."
        echo "         If DNS has not propagated yet, Let's Encrypt will fail and rate-limit retries."
    fi
    [[ -z "$RESOLVED" ]] && echo "WARNING: $DOMAIN does not resolve at all yet."
fi

# `config` parses the compose file and substitutes .env.prod, so every ${VAR:?...} guard fires now,
# before anything is torn down. This is what turns a missing secret into a message instead of an outage.
"${COMPOSE[@]}" config -q
echo "compose config OK — all required variables are present"

# ---------------------------------------------------------------------------
if [[ "$DO_PULL" == 1 ]]; then
    step "git pull"
    git pull --ff-only
fi

# ---------------------------------------------------------------------------
if [[ "$MODE" == "registry" ]]; then
    step "pull the published image"
    # What CI built from this commit's branch, tagged latest. Seconds rather than minutes, and it sidesteps
    # the Gradle build being OOM-killed on a small VPS entirely — that failure belongs to --build now.
    # A 'denied' or 'not found' here usually means CI has not published yet: check the Actions run, or
    # fall back to --build.
    "${COMPOSE[@]}" pull app
else
    step "build image locally"
    # Gradle inside the build stage wants well over a gigabyte. On a 1 GB VPS this is where a deploy dies,
    # with a JVM that was OOM-killed rather than an error message. If that happens, either add swap:
    #   fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
    # or build the image on your laptop and ship it:
    #   docker save ghcr.io/javaandscriptdeveloper/silporestockai:latest | ssh <host> 'docker load'
    "${COMPOSE[@]}" build app
fi

# ---------------------------------------------------------------------------
step "start"
# No `down` first: recreating only what changed keeps Postgres up, so the households, orders and Silpo
# tokens in it survive every deploy. The named volume would survive a `down` too — but not a `down -v`,
# which is the command to never type on this box.
"${COMPOSE[@]}" up -d --remove-orphans

# ---------------------------------------------------------------------------
step "wait for health"
# The app's own healthcheck is the source of truth: it hits /actuator/health on the management port,
# which is only UP once Liquibase has finished and the datasource answers. A timeout here means the
# migration or the boot failed — the logs printed below say which.
for i in $(seq 1 60); do
    STATUS="$(docker inspect -f '{{.State.Health.Status}}' komora-app 2>/dev/null || echo missing)"
    case "$STATUS" in
        healthy)   echo "app is healthy after $((i * 5))s"; break ;;
        unhealthy) echo "app reported UNHEALTHY"; STATUS=failed; break ;;
    esac
    [[ "$i" == 60 ]] && STATUS=failed
    sleep 5
done

if [[ "$STATUS" == "failed" || "$STATUS" == "missing" ]]; then
    echo "--- last 60 lines of the app log ---"
    "${COMPOSE[@]}" logs --tail 60 app
    echo
    echo "deploy FAILED: the app did not become healthy." >&2
    echo "A Liquibase or schema-validation failure appears in the log above." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
step "verify"
# Everything below goes through Caddy over real TLS — the only checks that prove the PUBLIC url works,
# rather than that the container is alive.
#
# INSECURE_TLS is set by the first probe if the certificate cannot be verified. That is expected exactly
# once, when rehearsing this script with DOMAIN=localhost (Caddy signs those with its own internal CA);
# on a public domain it means Let's Encrypt has not issued yet, which is a real problem.
INSECURE_TLS=""

# `|| true` inside the function, not at the call site: curl already prints 000 on a connection failure,
# and an `|| echo 000` around the substitution would concatenate a second one into "000000".
http_code() {
    curl -s ${INSECURE_TLS} -o /dev/null -w '%{http_code}' --max-time 15 "$1" 2>/dev/null || true
}

if [[ "$(http_code "https://${DOMAIN}/webapp/onboarding.html")" == "000" ]]; then
    if [[ "$(curl -sk -o /dev/null -w '%{http_code}' --max-time 15 "https://${DOMAIN}/webapp/onboarding.html" 2>/dev/null || true)" != "000" ]]; then
        INSECURE_TLS="-k"
        echo "NOTE: the certificate for ${DOMAIN} is not trusted, continuing the checks unverified."
        echo "      Expected when rehearsing with DOMAIN=localhost (Caddy's internal CA)."
        echo "      On a public domain it means Let's Encrypt has not issued — see: ${COMPOSE[*]} logs caddy"
    fi
fi

# The app answers 405 to a GET on the POST-only webhook path, and 405 is exactly the proof wanted: TLS
# terminated, Caddy routed, the app replied. 502 means Caddy is up but cannot reach the app.
CODE="$(http_code "https://${DOMAIN}/telegram/webhook")"
case "$CODE" in
    405) echo "https://${DOMAIN}/telegram/webhook -> 405 (reachable through Caddy)" ;;
    000) echo "WARNING: https://${DOMAIN} did not answer at all — last of Caddy's log:"
         "${COMPOSE[@]}" logs --tail 30 caddy ;;
    *)   echo "WARNING: https://${DOMAIN}/telegram/webhook -> ${CODE} (expected 405)" ;;
esac

# Telegram refuses to open a WebApp whose page is not a clean 200 over HTTPS.
CODE="$(http_code "https://${DOMAIN}/webapp/onboarding.html")"
[[ "$CODE" == "200" ]] && echo "https://${DOMAIN}/webapp/onboarding.html -> 200" \
                       || echo "WARNING: the WebApp form -> ${CODE} (want 200); onboarding will fall back to text"

# Actuator must NOT be reachable from outside: it lives on the unpublished management port, and Caddy
# proxies only 8080. Anything but 404 here means the household counts and GMV are public.
CODE="$(http_code "https://${DOMAIN}/actuator/health")"
if [[ "$CODE" == "404" ]]; then
    echo "https://${DOMAIN}/actuator/health -> 404 (not public, as intended)"
else
    echo "WARNING: /actuator answered ${CODE} publicly — MANAGEMENT_PORT is not taking effect." >&2
fi

# Which image is actually serving, so "did my fix deploy?" has an answer that is not a guess. With
# Watchtower running, this can legitimately be newer than the commit you just pulled.
echo "running image: $(docker inspect -f '{{.Config.Image}}' komora-app 2>/dev/null || echo unknown)"

# Did the app manage to tell Telegram where to deliver? It does this itself at boot; this only reports it,
# matching the success line and the failure line both, since a wrong token fails here and nowhere else.
"${COMPOSE[@]}" logs app 2>/dev/null | grep -iE "register(ed)? the Telegram webhook" | tail -1 || \
    echo "note: nothing about the webhook in the log — run scripts/set-webhook.sh ${DOMAIN}"

step "done"
echo "logs:    ${COMPOSE[*]} logs -f app"
echo "webhook: scripts/set-webhook.sh --info"
