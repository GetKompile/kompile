#!/bin/bash

#
# Sampled Memory Analysis - Low-Overhead Approach
#
# This is a sampling-based version of the comprehensive analysis script
# designed to minimize overhead while still tracking memory growth.
#
# Key differences from comprehensive version:
# - Lightweight continuous monitoring (RSS only, 1 second intervals)
# - Full snapshots only on-demand or at configurable intervals
# - Optional data sources (skip expensive ones like NMT detail)
# - Configurable snapshot frequency
# - Manual trigger mode (no automatic snapshots)
#
# Usage modes:
#   1. Auto mode (default): Lightweight monitoring + periodic full snapshots
#   2. Manual mode: Lightweight monitoring + manual snapshots only (kill -USR1)
#   3. Continuous light: Only RSS tracking, no full snapshots
#

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Configuration (can be overridden via environment variables)
SNAPSHOT_INTERVAL="${SNAPSHOT_INTERVAL:-300}"  # Seconds between full snapshots (default: 5 minutes)
RSS_POLL_INTERVAL="${RSS_POLL_INTERVAL:-10}"    # Seconds between RSS checks (default: 10 seconds)
CAPTURE_NMT_DETAIL="${CAPTURE_NMT_DETAIL:-false}"  # Skip expensive NMT detail by default
CAPTURE_HEAP_HISTOGRAM="${CAPTURE_HEAP_HISTOGRAM:-false}"  # Skip heap histogram by default
CAPTURE_SMAPS="${CAPTURE_SMAPS:-false}"  # Skip smaps by default (can be very slow)
MODE="${MODE:-auto}"  # auto, manual, or light

# Try to find the jar file
if [[ -f "${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT-exec.jar" ]]; then
    JAR_FILE="${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT-exec.jar"
elif [[ -f "${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT.jar" ]]; then
    JAR_FILE="${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT.jar"
else
    echo "ERROR: JAR file not found. Run 'mvn package' first."
    exit 1
fi

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTPUT_DIR="${PROJECT_DIR}/memory_analysis_sampled_${TIMESTAMP}"
mkdir -p "$OUTPUT_DIR"
RSS_LOG="${OUTPUT_DIR}/rss_continuous.log"
MONITOR_LOG="${OUTPUT_DIR}/monitor.log"
SNAPSHOT_LOG="${OUTPUT_DIR}/snapshots.log"

# Log configuration
{
    echo "========================================"
    echo "Sampled Memory Analysis - Low Overhead"
    echo "Started: $(date)"
    echo "========================================"
    echo "Output directory: $OUTPUT_DIR"
    echo ""
    echo "Configuration:"
    echo "  Mode: $MODE"
    echo "  Snapshot interval: ${SNAPSHOT_INTERVAL}s"
    echo "  RSS poll interval: ${RSS_POLL_INTERVAL}s"
    echo "  Capture NMT detail: $CAPTURE_NMT_DETAIL"
    echo "  Capture heap histogram: $CAPTURE_HEAP_HISTOGRAM"
    echo "  Capture smaps: $CAPTURE_SMAPS"
    echo ""
} > "$MONITOR_LOG"

# Print minimal info to console
echo "Memory analysis started: $OUTPUT_DIR"
echo "Monitor log: $MONITOR_LOG"
echo "RSS timeline: $RSS_LOG"
echo "Snapshot log: $SNAPSHOT_LOG"
echo ""
echo "Application PID will be logged to $MONITOR_LOG"
echo ""

java \
    -XX:NativeMemoryTracking=summary \
    -XX:+UnlockDiagnosticVMOptions \
    -XX:+PrintNMTStatistics \
    -Xms1g \
    -Xmx4g \
    -jar "$JAR_FILE" &

APP_PID=$!

# Log PID to file
echo "Application PID: $APP_PID" | tee -a "$MONITOR_LOG"
echo ""

# Function to capture lightweight snapshot (essential data only)
capture_snapshot() {
    local snapshot_num=$1
    local snapshot_dir="${OUTPUT_DIR}/snapshot_${snapshot_num}"
    mkdir -p "$snapshot_dir"

    local start_time=$(date +%s)

    # Log to snapshot log, minimal to console
    {
        echo "========================================"
        echo "Capturing Snapshot #${snapshot_num} at $(date)"
        echo "========================================"
        echo ""
    } >> "$SNAPSHOT_LOG"

    echo "[$(date +%H:%M:%S)] Snapshot #${snapshot_num}..." >> "$MONITOR_LOG"

    local step=1

    # 1. Always capture: NMT Summary (fast)
    echo "  [${step}/X] NMT summary..." >> "$SNAPSHOT_LOG"
    jcmd $APP_PID VM.native_memory summary scale=MB > "${snapshot_dir}/nmt_summary.txt" 2>&1
    step=$((step + 1))

    # 2. Always capture: Process status (very fast)
    echo "  [${step}/X] Process status..." >> "$SNAPSHOT_LOG"
    cat /proc/$APP_PID/status > "${snapshot_dir}/status.txt" 2>&1
    step=$((step + 1))

    # 3. Optional: Memory maps (skip by default for speed)
    if [[ "$CAPTURE_SMAPS" == "true" ]]; then
        echo "  [${step}/X] Memory maps..." >> "$SNAPSHOT_LOG"
        cat /proc/$APP_PID/maps > "${snapshot_dir}/maps.txt" 2>&1
        step=$((step + 1))
    fi

    # 4. Optional: SMAPS (can be slow for large processes)
    if [[ "$CAPTURE_SMAPS" == "true" ]]; then
        echo "  [${step}/X] SMAPS..." >> "$SNAPSHOT_LOG"
        cat /proc/$APP_PID/smaps > "${snapshot_dir}/smaps.txt" 2>&1
        step=$((step + 1))
    fi

    # 5. Optional: NMT Detail (VERY slow, skip by default)
    if [[ "$CAPTURE_NMT_DETAIL" == "true" ]]; then
        echo "  [${step}/X] NMT detail (slow)..." >> "$SNAPSHOT_LOG"
        jcmd $APP_PID VM.native_memory detail scale=MB > "${snapshot_dir}/nmt_detail.txt" 2>&1
        step=$((step + 1))
    fi

    # 6. Optional: Heap histogram (slow, skip by default)
    if [[ "$CAPTURE_HEAP_HISTOGRAM" == "true" ]]; then
        echo "  [${step}/X] Heap histogram (slow)..." >> "$SNAPSHOT_LOG"
        jcmd $APP_PID GC.class_histogram > "${snapshot_dir}/heap_histogram.txt" 2>&1
        step=$((step + 1))
    fi

    # 7. Lightweight: GC info (fast)
    echo "  [${step}/X] GC info..." >> "$SNAPSHOT_LOG"
    jcmd $APP_PID GC.heap_info > "${snapshot_dir}/gc_info.txt" 2>&1 || echo "N/A" > "${snapshot_dir}/gc_info.txt"

    # Quick analysis - log to snapshot log
    {
        echo ""
        echo "Quick Summary:"
        echo "--------------"

        # Total RSS from /proc/status
        RSS=$(grep "^VmRSS:" /proc/$APP_PID/status | awk '{print $2}')
        RSS_MB=$((RSS / 1024))
        echo "Total RSS: ${RSS_MB} MB"

        # Memory breakdown from smaps (if captured)
        if [[ "$CAPTURE_SMAPS" == "true" ]] && [[ -f "${snapshot_dir}/smaps.txt" ]]; then
            echo ""
            echo "Memory by type (from smaps):"
            awk '/^Size:/ {size+=$2} /^Rss:/ {rss+=$2} /^Anonymous:/ {anon+=$2} /^Swap:/ {swap+=$2} END {print "  Total Size: " size/1024 " MB"; print "  RSS: " rss/1024 " MB"; print "  Anonymous: " anon/1024 " MB"; print "  Swap: " swap/1024 " MB"}' "${snapshot_dir}/smaps.txt"
        fi

        # Count memory-mapped regions
        echo ""
        MMAP_COUNT=$(grep -c "^[0-9a-f]" "${snapshot_dir}/maps.txt")
        echo "Memory-mapped regions: $MMAP_COUNT"

        # Show large anonymous mappings (likely memory issue!)
        echo ""
        echo "Large anonymous mappings (>100MB):"
        awk '$6 == "" && $5 == "00:00" {
            start = strtonum("0x" $1);
            end = strtonum("0x" substr($1, index($1,"-")+1));
            size = (end - start) / 1024 / 1024;
            if (size > 100) printf "  %s: %.0f MB\n", $1, size
        }' "${snapshot_dir}/maps.txt" | head -20

        # NMT Other category (native allocations)
        echo ""
        echo "NMT 'Other' category (native code allocations):"
        grep -A 1 "Other (reserved" "${snapshot_dir}/nmt_summary.txt" | tail -1 || echo "  Not found"

        local end_time=$(date +%s)
        local duration=$((end_time - start_time))
        echo ""
        echo "Snapshot saved to: $snapshot_dir (took ${duration}s)"
        echo ""
    } >> "$SNAPSHOT_LOG"

    # Minimal console output
    echo "[$(date +%H:%M:%S)] Snapshot #${snapshot_num} complete (RSS: ${RSS_MB} MB)" >> "$MONITOR_LOG"
}

# Function to compare two snapshots
compare_snapshots() {
    local snap1=$1
    local snap2=$2
    local output_file="${OUTPUT_DIR}/comparison_${snap1}_to_${snap2}.txt"

    local dir1="${OUTPUT_DIR}/snapshot_${snap1}"
    local dir2="${OUTPUT_DIR}/snapshot_${snap2}"

    # Compare RSS
    RSS1=$(grep "^VmRSS:" "${dir1}/status.txt" | awk '{print $2}')
    RSS2=$(grep "^VmRSS:" "${dir2}/status.txt" | awk '{print $2}')
    RSS_DIFF=$((RSS2 - RSS1))
    RSS_DIFF_MB=$((RSS_DIFF / 1024))

    # Write comparison to file
    {
        echo "========================================"
        echo "Comparing Snapshot #${snap1} to #${snap2}"
        echo "========================================"
        echo ""
        echo "RSS Change: ${RSS_DIFF_MB} MB"

        # Compare anonymous memory (if smaps captured)
        if [[ "$CAPTURE_SMAPS" == "true" ]] && [[ -f "${dir1}/smaps.txt" ]] && [[ -f "${dir2}/smaps.txt" ]]; then
            ANON1=$(awk '/^Anonymous:/ {sum+=$2} END {print sum}' "${dir1}/smaps.txt")
            ANON2=$(awk '/^Anonymous:/ {sum+=$2} END {print sum}' "${dir2}/smaps.txt")
            ANON_DIFF=$((ANON2 - ANON1))
            ANON_DIFF_MB=$((ANON_DIFF / 1024))

            echo "Anonymous Memory Change: ${ANON_DIFF_MB} MB"
        fi

        # Compare NMT (if baseline was set)
        echo ""
        echo "NMT Diff:"
        jcmd $APP_PID VM.native_memory summary.diff scale=MB 2>&1 | grep -A 15 "Total:"

        # Find new/grown memory regions
        echo ""
        echo "New/Grown Large Mappings (>100MB):"
        comm -13 \
            <(awk '$6 == "" && $5 == "00:00" {start = strtonum("0x" $1); end = strtonum("0x" substr($1, index($1,"-")+1)); size = (end - start) / 1024 / 1024; if (size > 100) print $1}' "${dir1}/maps.txt" | sort) \
            <(awk '$6 == "" && $5 == "00:00" {start = strtonum("0x" $1); end = strtonum("0x" substr($1, index($1,"-")+1)); size = (end - start) / 1024 / 1024; if (size > 100) print $1}' "${dir2}/maps.txt" | sort) \
            | head -10

        echo ""
    } > "$output_file"

    # Log summary to monitor log
    echo "[$(date +%H:%M:%S)] Comparison ${snap1}→${snap2}: RSS ${RSS_DIFF_MB:+${RSS_DIFF_MB} MB}" >> "$MONITOR_LOG"
}

# Lightweight RSS monitoring function (runs in background)
monitor_rss() {
    echo "timestamp,rss_kb,rss_mb" > "$RSS_LOG"
    while ps -p $APP_PID > /dev/null 2>&1; do
        RSS=$(grep "^VmRSS:" /proc/$APP_PID/status 2>/dev/null | awk '{print $2}')
        if [[ -n "$RSS" ]]; then
            RSS_MB=$((RSS / 1024))
            TIMESTAMP=$(date +%s)
            echo "${TIMESTAMP},${RSS},${RSS_MB}" >> "$RSS_LOG"
        fi
        sleep $RSS_POLL_INTERVAL
    done
}

# Signal handler for manual snapshots (USR1)
MANUAL_SNAPSHOT_NUM=100  # Start manual snapshots at 100 to distinguish from auto
trap_usr1() {
    echo "[$(date +%H:%M:%S)] Manual snapshot triggered" >> "$MONITOR_LOG"
    capture_snapshot $MANUAL_SNAPSHOT_NUM
    if [ $MANUAL_SNAPSHOT_NUM -gt 100 ]; then
        compare_snapshots $((MANUAL_SNAPSHOT_NUM - 1)) $MANUAL_SNAPSHOT_NUM
    elif [ -d "${OUTPUT_DIR}/snapshot_0" ]; then
        compare_snapshots 0 $MANUAL_SNAPSHOT_NUM
    fi
    MANUAL_SNAPSHOT_NUM=$((MANUAL_SNAPSHOT_NUM + 1))
}

trap trap_usr1 USR1

# Trap Ctrl+C
trap 'echo "Stopping..."; echo "[$(date +%H:%M:%S)] Stopping monitoring" >> "$MONITOR_LOG"; kill $APP_PID 2>/dev/null; wait $RSS_MONITOR_PID 2>/dev/null; exit 0' INT TERM

# Log monitoring instructions to file
{
    echo "========================================"
    echo "Monitoring Instructions"
    echo "========================================"
    echo ""
    echo "Continuous RSS monitoring: Every ${RSS_POLL_INTERVAL}s → $RSS_LOG"
    if [[ "$MODE" == "auto" ]]; then
        echo "Automatic snapshots: Every ${SNAPSHOT_INTERVAL}s"
    elif [[ "$MODE" == "manual" ]]; then
        echo "Manual snapshots only: kill -USR1 $$"
    elif [[ "$MODE" == "light" ]]; then
        echo "Lightweight mode: RSS tracking only, no snapshots"
    fi
    echo ""
    echo "Manual commands:"
    echo "  - Set NMT baseline:    jcmd $APP_PID VM.native_memory baseline"
    echo "  - Manual snapshot:     kill -USR1 $$"
    echo "  - Stop monitoring:     kill $$"
    echo ""
} >> "$MONITOR_LOG"

# Minimal console output
echo "Mode: $MODE | PID: $APP_PID | Script PID: $$"
echo "Logs: monitor.log, snapshots.log, rss_continuous.log"
echo "Manual snapshot: kill -USR1 $$"
echo "Stop: Ctrl+C or kill $$"
echo ""

# Wait for app to warm up
echo "Warming up (10s)..."
sleep 10

# Set NMT baseline
jcmd $APP_PID VM.native_memory baseline > /dev/null 2>&1
echo "[$(date +%H:%M:%S)] NMT baseline set" >> "$MONITOR_LOG"

# Start lightweight RSS monitoring in background
monitor_rss &
RSS_MONITOR_PID=$!
echo "[$(date +%H:%M:%S)] RSS monitoring started (PID: $RSS_MONITOR_PID)" >> "$MONITOR_LOG"

# Take initial snapshot
echo "Taking initial snapshot..."
capture_snapshot 0
echo "Monitoring active. Ctrl+C to stop."

if [[ "$MODE" == "light" ]]; then
    echo "[$(date +%H:%M:%S)] Light mode: RSS monitoring only" >> "$MONITOR_LOG"
    # Just wait for application to exit or Ctrl+C
    wait $APP_PID

elif [[ "$MODE" == "manual" ]]; then
    echo "[$(date +%H:%M:%S)] Manual mode: Waiting for triggers" >> "$MONITOR_LOG"
    # Wait for application to exit or Ctrl+C
    wait $APP_PID

else
    # Auto mode - periodic snapshots
    echo "[$(date +%H:%M:%S)] Auto mode: Snapshots every ${SNAPSHOT_INTERVAL}s" >> "$MONITOR_LOG"
    SNAPSHOT_NUM=1
    LAST_SNAPSHOT_TIME=$(date +%s)

    while true; do
        if ! ps -p $APP_PID > /dev/null 2>&1; then
            echo "[$(date +%H:%M:%S)] Application stopped" >> "$MONITOR_LOG"
            break
        fi

        # Sleep in small increments to be responsive to signals
        sleep 5

        CURRENT_TIME=$(date +%s)
        TIME_SINCE_LAST=$((CURRENT_TIME - LAST_SNAPSHOT_TIME))

        if [ $TIME_SINCE_LAST -ge $SNAPSHOT_INTERVAL ]; then
            capture_snapshot $SNAPSHOT_NUM
            compare_snapshots $((SNAPSHOT_NUM - 1)) $SNAPSHOT_NUM
            SNAPSHOT_NUM=$((SNAPSHOT_NUM + 1))
            LAST_SNAPSHOT_TIME=$CURRENT_TIME
        fi
    done
fi

# Final summary
echo ""
echo "Analysis complete. Results in: $OUTPUT_DIR"

{
    echo ""
    echo "========================================"
    echo "Analysis Complete"
    echo "Ended: $(date)"
    echo "========================================"
    echo ""
    echo "Results saved in: $OUTPUT_DIR"
    echo ""
    echo "RSS timeline: $RSS_LOG"
    echo ""
    echo "To visualize RSS over time:"
    echo "  awk -F, 'NR>1 {print \$1, \$3}' $RSS_LOG | sort -n"
    echo ""
    echo "To analyze snapshots:"
    echo "  cd $OUTPUT_DIR"
    echo "  cat snapshots.log       # All snapshot details"
    echo "  cat monitor.log         # Timeline of events"
    echo "  cat comparison_*.txt    # Snapshot comparisons"
    echo "  grep 'reserved=' snapshot_*/nmt_summary.txt"
    echo ""
} >> "$MONITOR_LOG"

# Wait for RSS monitor to finish
wait $RSS_MONITOR_PID 2>/dev/null
