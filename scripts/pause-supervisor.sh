#!/bin/bash
# Pause the localhost.run supervisor for the length of a live driving session, leaving the already
# running app alone. The supervisor rotates the anonymous hostname every ~20 min, and each rotation
# restarts the app and truncates logs/app.log — which cuts a live flow in half and loses the evidence.
# Hand the app back afterwards with scripts/restore-supervisor.sh.
set -u
pkill -f "scripts/tunnel-supervisor.sh" 2>/dev/null
pkill -f "nokey@localhost.run" 2>/dev/null
echo "supervisor paused; app left running"
