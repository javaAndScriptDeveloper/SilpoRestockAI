#!/bin/bash
# Session-only tunnel helper. The localhost.run supervisor rotates the anonymous hostname every ~10 min and
# restarts the app each time, which cuts live flows in half. This runs one alternative tunnel with a hostname
# that is stable for the life of the process, points .env and the Telegram webhook at it, and restarts the app once.
# Usage: scripts/session-tunnel.sh ngrok|cloudflared|stop
set -u
cd "$(dirname "$0")/.." || exit 1
mkdir -p logs

stop_all() {
    pkill -f "scripts/tunnel-supervisor.sh" 2>/dev/null
    pkill -f "nokey@localhost.run" 2>/dev/null
    pkill -f "cloudflared tunnel --url" 2>/dev/null
    pkill -f "ngrok http" 2>/dev/null
}

point_and_restart() {
    local host="$1"
    sed -i "s|^TELEGRAM_WEBHOOK_URL=.*|TELEGRAM_WEBHOOK_URL=${host}/telegram/webhook|" .env
    sed -i "s|^TELEGRAM_WEB_APP_BASE_URL=.*|TELEGRAM_WEB_APP_BASE_URL=${host}|" .env
    echo "tunnel: $host"
    scripts/restart-app.sh
}

case "${1:-ngrok}" in
cloudflared)
    stop_all
    : > logs/cloudflared.log
    setsid nohup cloudflared tunnel --url http://localhost:8080 --no-autoupdate > logs/cloudflared.log 2>&1 < /dev/null &
    disown
    host=""
    for _ in $(seq 1 40); do
        host=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' logs/cloudflared.log | grep -v '//api\.' | head -1)
        [[ -n "$host" ]] && break
        sleep 1
    done
    [[ -z "$host" ]] && { echo "cloudflared gave no hostname; log:"; tail -5 logs/cloudflared.log; exit 1; }
    point_and_restart "$host"
    ;;
ngrok)
    stop_all
    : > logs/ngrok.log
    setsid nohup ngrok http 8080 --log stdout --log-format json > logs/ngrok.log 2>&1 < /dev/null &
    disown
    host=""
    for _ in $(seq 1 40); do
        host=$(curl -s localhost:4040/api/tunnels 2>/dev/null | grep -oE '"public_url":"https://[^"]+' | head -1 | cut -d'"' -f4)
        [[ -n "$host" ]] && break
        sleep 1
    done
    [[ -z "$host" ]] && { echo "ngrok gave no hostname; log:"; tail -5 logs/ngrok.log; exit 1; }
    point_and_restart "$host"
    ;;
stop)
    stop_all
    echo "stopped; restore with: setsid nohup scripts/tunnel-supervisor.sh > logs/tunnel-supervisor.out 2>&1 < /dev/null &"
    ;;
esac
