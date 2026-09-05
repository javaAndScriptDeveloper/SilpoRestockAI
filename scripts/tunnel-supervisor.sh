#!/bin/bash
# Keeps a localhost.run tunnel alive for local Telegram bot dev, and keeps .env + the running app
# in sync with whatever subdomain gets assigned on each (re)connect.
#
# Why localhost.run and not serveo: serveo's free tier shows a "you are about to visit serveo.net"
# browser interstitial to real-browser traffic, which is exactly what Telegram's in-app WebView is
# — the onboarding Mini App hits that warning page instead of the form, with no way to tap through
# it. localhost.run's free tier does not show this. The tradeoff is that localhost.run's free
# subdomain is not fixed — it changes on every reconnect. This script removes the pain that
# tradeoff used to mean (manually editing .env and restarting the app after every reconnect) by
# doing both automatically.
#
# See docs/LOCAL_TUNNEL.md for the full picture, including what a stable *and* interstitial-free
# domain would actually cost (neither ngrok's nor localhost.run's paid plans are free).

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$APP_DIR/.env"

update_env_and_restart() {
    local host="$1"
    local url="https://${host}"

    if grep -q '^TELEGRAM_WEBHOOK_URL=' "$ENV_FILE"; then
        sed -i "s|^TELEGRAM_WEBHOOK_URL=.*|TELEGRAM_WEBHOOK_URL=${url}/telegram/webhook|" "$ENV_FILE"
    else
        echo "TELEGRAM_WEBHOOK_URL=${url}/telegram/webhook" >> "$ENV_FILE"
    fi
    if grep -q '^TELEGRAM_WEB_APP_BASE_URL=' "$ENV_FILE"; then
        sed -i "s|^TELEGRAM_WEB_APP_BASE_URL=.*|TELEGRAM_WEB_APP_BASE_URL=${url}|" "$ENV_FILE"
    else
        echo "TELEGRAM_WEB_APP_BASE_URL=${url}" >> "$ENV_FILE"
    fi

    echo "$(date -Iseconds) tunnel is ${url} — restarting the app to pick it up"
    # Both the "gradlew bootRun" wrapper shell and the actual forked JVM (whose command line
    # doesn't contain that string at all) hold state that matters here: killing only the wrapper
    # leaves the JVM running and bound to :8080, so the next start fails with "port already in use".
    fuser -k 8080/tcp 2>/dev/null
    pkill -f "gradlew bootRun" 2>/dev/null
    sleep 3
    ( cd "$APP_DIR" && mkdir -p logs && nohup make run > logs/app.log 2>&1 & disown )
}

while true; do
    ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
        -R 80:localhost:8080 nokey@localhost.run 2>&1 |
        while IFS= read -r line; do
            echo "$line"
            if [[ "$line" =~ ([a-z0-9]+\.lhr\.life)\ tunneled ]]; then
                update_env_and_restart "${BASH_REMATCH[1]}"
            fi
        done
    echo "$(date -Iseconds) tunnel dropped, reconnecting in 2s..."
    sleep 2
done
