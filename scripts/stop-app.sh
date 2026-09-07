#!/bin/bash
# Stop the locally running app (make run) and wait for :8080 to free. The tunnel is left alone.
set -u
pkill -f "gradle-wrapper.jar bootRun" 2>/dev/null
pkill -f "com.silporestockai.Application" 2>/dev/null
fuser -k 8080/tcp 2>/dev/null
for _ in $(seq 1 30); do
    fuser 8080/tcp >/dev/null 2>&1 || { echo "app stopped"; exit 0; }
    sleep 1
done
echo "WARNING: :8080 still held after 30s"
exit 1
