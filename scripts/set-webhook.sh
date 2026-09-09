#!/usr/bin/env bash
# Point the Telegram bot at a domain — or inspect / clear where it currently points.
#
#   scripts/set-webhook.sh komora.example.com     # set it (https:// and the path are added for you)
#   scripts/set-webhook.sh --info                 # where does Telegram think it should deliver?
#   scripts/set-webhook.sh --delete               # stop delivery entirely
#
# The app already registers the webhook itself at every boot (TelegramWebhookRegistrationService), so a
# normal deploy needs none of this. It earns its place in the three cases where that is not enough:
#   - a development bot and the production bot fighting over the same URL, and you need to see who won;
#   - the app is down but you need delivery stopped now;
#   - Telegram reports a delivery error and you want last_error_message without reading a container log.
#
# Reads the token from --env-file (default .env.prod, falling back to .env), so no secret is ever typed
# on a command line where it would land in shell history.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-}"
ACTION="set"
# Deliberately not named DOMAIN: sourcing the env file below would overwrite it with .env.prod's own
# DOMAIN=, and the argument typed on the command line would be silently ignored.
TARGET=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --info)     ACTION="info"; shift ;;
        --delete)   ACTION="delete"; shift ;;
        --env-file) ENV_FILE="$2"; shift 2 ;;
        -h|--help)  sed -n '2,20p' "$0"; exit 0 ;;
        -*)         echo "unknown flag: $1" >&2; exit 2 ;;
        *)          TARGET="$1"; shift ;;
    esac
done

if [[ -z "$ENV_FILE" ]]; then
    if   [[ -f .env.prod ]]; then ENV_FILE=.env.prod
    elif [[ -f .env      ]]; then ENV_FILE=.env
    else echo "no .env.prod or .env found; pass --env-file <path>" >&2; exit 1
    fi
fi
# shellcheck disable=SC1090
set -a; . "./$ENV_FILE"; set +a

if [[ -z "${TELEGRAM_BOT_TOKEN:-}" ]]; then
    echo "TELEGRAM_BOT_TOKEN is not set in $ENV_FILE" >&2
    exit 1
fi
API="${TELEGRAM_API_URL:-https://api.telegram.org}/bot${TELEGRAM_BOT_TOKEN}"

# No argument: fall back to the DOMAIN the env file already defines, which is the usual case on the server.
[[ -z "$TARGET" ]] && TARGET="${DOMAIN:-}"

# Prints Telegram's reply readably when jq is present, raw when it is not — a server may have neither.
# Note the callers use `curl -s`, never `-sf`: on a 401 or a rejected URL, the JSON body IS the answer
# ("Unauthorized", "bad webhook: HTTPS url must be provided"), and -f throws exactly that body away.
show() { if command -v jq >/dev/null 2>&1; then jq .; else cat; echo; fi; }

case "$ACTION" in
info)
    # url, pending_update_count and last_error_message together answer "why is the bot silent?"
    curl -s "${API}/getWebhookInfo" | show
    ;;

delete)
    # drop_pending_updates: a queue built up while the app was down would otherwise all arrive at once
    # on the next start, replaying old messages into a live demo.
    curl -s -X POST "${API}/deleteWebhook" -d "drop_pending_updates=true" | show
    ;;

set)
    if [[ -z "$TARGET" ]]; then
        echo "usage: $0 <domain>   e.g. $0 komora.example.com" >&2
        echo "(or set DOMAIN= in $ENV_FILE and pass no argument)" >&2
        exit 2
    fi
    # Accept komora.example.com, https://komora.example.com, or either with a trailing slash.
    TARGET="${TARGET#https://}"; TARGET="${TARGET#http://}"; TARGET="${TARGET%/}"
    URL="https://${TARGET}/telegram/webhook"

    if [[ -z "${TELEGRAM_WEBHOOK_SECRET:-}" ]]; then
        # Not fatal, but say it out loud: without the secret the app accepts any POST to the webhook path,
        # and that path is now public.
        echo "WARNING: TELEGRAM_WEBHOOK_SECRET is empty — anyone who finds $URL can forge updates" >&2
    fi

    echo "setting webhook -> $URL"
    # Deliberately no allowed_updates. Telegram REMEMBERS a restricted list across later setWebhook calls
    # that omit the parameter — including the app's own call at every boot — so narrowing it here would
    # silently starve any update type a future flow starts handling. The default set already covers the
    # three the app reads today: message, callback_query, my_chat_member.
    curl -s -X POST "${API}/setWebhook" \
        --data-urlencode "url=${URL}" \
        --data-urlencode "secret_token=${TELEGRAM_WEBHOOK_SECRET:-}" \
        --data-urlencode "drop_pending_updates=true" \
        | show

    echo "confirming:"
    curl -s "${API}/getWebhookInfo" | show
    ;;
esac
