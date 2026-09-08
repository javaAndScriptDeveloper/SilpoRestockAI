#!/bin/bash
# End of a browser-driven session: drop the ngrok session tunnel and hand the app back to the
# localhost.run supervisor (the phone-friendly setup). The supervisor restarts the app itself once it
# gets a hostname.
set -u
cd "$(dirname "$0")/.." || exit 1
mkdir -p logs
scripts/session-tunnel.sh stop >/dev/null 2>&1
scripts/stop-app.sh
setsid nohup scripts/tunnel-supervisor.sh > logs/tunnel-supervisor.out 2>&1 < /dev/null &
disown
echo "supervisor started; waiting for the app"
for i in $(seq 1 150); do
    if curl -sf localhost:8080/actuator/health >/dev/null 2>&1; then
        echo "app is UP after ${i}s"
        grep -m1 "registered the Telegram webhook" logs/app.log || true
        exit 0
    fi
    sleep 1
done
echo "app did not come up within 150s; supervisor log:"; tail -5 logs/tunnel-supervisor.out
exit 1
