#!/bin/bash
# External watchdog script for killing stuck JVM process
# Usage: ./shutdown-watchdog.sh <pid> <timeout_seconds>

if [ $# -lt 2 ]; then
    echo "Usage: $0 <java_pid> <timeout_seconds>"
    exit 1
fi

JAVA_PID=$1
TIMEOUT=$2

echo "[Watchdog] Monitoring Java process $JAVA_PID with ${TIMEOUT}s timeout"

# Wait for the timeout period
sleep $TIMEOUT

# Check if process still exists
if kill -0 $JAVA_PID 2>/dev/null; then
    echo "[Watchdog] Process $JAVA_PID still running after ${TIMEOUT}s. Attempting graceful kill..."
    kill -TERM $JAVA_PID
    
    # Wait 2 more seconds
    sleep 2
    
    # If still running, force kill
    if kill -0 $JAVA_PID 2>/dev/null; then
        echo "[Watchdog] Graceful kill failed. Force killing process $JAVA_PID with SIGKILL..."
        kill -9 $JAVA_PID
        
        if [ $? -eq 0 ]; then
            echo "[Watchdog] Process $JAVA_PID forcefully terminated"
        else
            echo "[Watchdog] ERROR: Failed to kill process $JAVA_PID"
        fi
    else
        echo "[Watchdog] Process $JAVA_PID terminated gracefully"
    fi
else
    echo "[Watchdog] Process $JAVA_PID already terminated naturally"
fi
