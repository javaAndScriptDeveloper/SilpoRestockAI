#!/bin/bash
# Restart the locally running app (make run) without touching the tunnel supervisor.
# Mirrors the supervisor's own restart recipe: kill the gradle wrapper and the forked JVM, wait for :8080
# to free, start `make run` detached, then poll /actuator/health.
set -u
cd "$(dirname "$0")/.." || exit 1
mkdir -p logs

pkill -f "gradle-wrapper.jar bootRun" 2>/dev/null
pkill -f "com.silporestockai.Application" 2>/dev/null
fuser -k 8080/tcp 2>/dev/null
for _ in $(seq 1 30); do
    fuser 8080/tcp >/dev/null 2>&1 || break
    sleep 1
done
if fuser 8080/tcp >/dev/null 2>&1; then
    echo "WARNING: :8080 still held after 30s, starting anyway"
fi

: > logs/app.log
# PROFILE=demo scripts/restart-app.sh starts the recording profile (make demo) instead.
if [[ "${PROFILE:-}" == "demo" ]]; then
    setsid nohup make demo > logs/bootrun.out 2>&1 < /dev/null &
else
    setsid nohup make run > logs/bootrun.out 2>&1 < /dev/null &
fi
disown

for i in $(seq 1 120); do
    if curl -sf localhost:8080/actuator/health >/dev/null 2>&1; then
        echo "app is UP after ${i}s"
        grep -m1 "Registered the Telegram webhook\|registered the Telegram webhook" logs/app.log || true
        exit 0
    fi
    sleep 1
done
echo "app did not come up within 120s; tail of logs/app.log:"
tail -30 logs/app.log
exit 1
