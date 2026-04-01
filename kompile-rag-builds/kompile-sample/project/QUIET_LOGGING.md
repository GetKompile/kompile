# Quiet Logging Mode - Production-Ready Memory Analysis

## The Problem

The original comprehensive memory analysis script prints **extensive debugging output** to stdout:
- Snapshot progress messages every 30 seconds
- Memory breakdowns (100+ lines per snapshot)
- Comparison results
- Large anonymous mapping lists
- NMT category details

**For long-running servers**, this creates:
- Thousands of lines of console output
- Difficult to spot important events
- Not practical for background monitoring
- Log files become overwhelming

## The Solution

The **sampled memory analysis script** now uses **quiet logging mode**:

### Console Output (Minimal)

Only essential information printed to stdout:

```
Memory analysis started: /path/to/memory_analysis_sampled_20251105_173022
Monitor log: /path/to/memory_analysis_sampled_20251105_173022/monitor.log
RSS timeline: /path/to/memory_analysis_sampled_20251105_173022/rss_continuous.log
Snapshot log: /path/to/memory_analysis_sampled_20251105_173022/snapshots.log

Application PID will be logged to monitor.log

Application PID: 123456

Mode: manual | PID: 123456 | Script PID: 789012
Logs: monitor.log, snapshots.log, rss_continuous.log
Manual snapshot: kill -USR1 789012
Stop: Ctrl+C or kill 789012

Warming up (10s)...
Taking initial snapshot...
Monitoring active. Ctrl+C to stop.

[Application runs silently...]

Analysis complete. Results in: /path/to/memory_analysis_sampled_20251105_173022
```

**Total console output**: ~20 lines (vs thousands in old script)

### Log Files (Detailed)

All detailed information goes to log files:

#### 1. `monitor.log` - High-Level Timeline

Concise timeline of events:

```
========================================
Sampled Memory Analysis - Low Overhead
Started: Tue Nov  5 17:30:22 EST 2025
========================================
Output directory: /path/to/memory_analysis_sampled_20251105_173022

Configuration:
  Mode: manual
  Snapshot interval: 120s
  RSS poll interval: 5s
  Capture NMT detail: false
  Capture heap histogram: false
  Capture smaps: true

========================================
Monitoring Instructions
========================================

Continuous RSS monitoring: Every 5s → rss_continuous.log
Manual snapshots only: kill -USR1 789012

Manual commands:
  - Set NMT baseline:    jcmd 123456 VM.native_memory baseline
  - Manual snapshot:     kill -USR1 789012
  - Stop monitoring:     kill 789012

Application PID: 123456
[17:30:32] NMT baseline set
[17:30:32] RSS monitoring started (PID: 123457)
[17:30:42] Snapshot #0...
[17:30:45] Snapshot #0 complete (RSS: 2048 MB)
[17:32:15] Manual snapshot triggered
[17:32:18] Snapshot #100...
[17:32:21] Snapshot #100 complete (RSS: 4096 MB)
[17:32:21] Comparison 0→100: RSS 2048 MB
[17:45:00] Stopping monitoring

========================================
Analysis Complete
Ended: Tue Nov  5 17:45:00 EST 2025
========================================
[...]
```

**Purpose**: Quick overview of what happened when

#### 2. `snapshots.log` - Snapshot Details

Full details of each snapshot:

```
========================================
Capturing Snapshot #0 at Tue Nov  5 17:30:42 EST 2025
========================================

  [1/X] NMT summary...
  [2/X] Process status...
  [3/X] Memory maps...
  [4/X] SMAPS...
  [5/X] GC info...

Quick Summary:
--------------
Total RSS: 2048 MB

Memory by type (from smaps):
  Total Size: 6144 MB
  RSS: 2048 MB
  Anonymous: 1536 MB
  Swap: 0 MB

Memory-mapped regions: 1247

Large anonymous mappings (>100MB):
  7f8a00000000-7f8b00000000: 256 MB
  7f8c00000000-7f8d00000000: 256 MB
  7f8e00000000-7f9000000000: 512 MB

NMT 'Other' category (native code allocations):
  (malloc=512MB #12345)

Snapshot saved to: /path/to/snapshot_0 (took 3s)

========================================
Capturing Snapshot #100 at Tue Nov  5 17:32:18 EST 2025
========================================
[...]
```

**Purpose**: Detailed memory state for each snapshot

#### 3. `rss_continuous.log` - RSS Timeline (CSV)

Continuous RSS measurements:

```
timestamp,rss_kb,rss_mb
1730845842,2097152,2048
1730845847,2105344,2056
1730845852,2113536,2064
1730845857,2121728,2072
...
```

**Purpose**: Graph memory usage over time, spot spikes

#### 4. `comparison_*.txt` - Snapshot Diffs

Memory growth between snapshots:

```
========================================
Comparing Snapshot #0 to #100
========================================

RSS Change: 2048 MB
Anonymous Memory Change: 1024 MB

NMT Diff:
Total: reserved=6144MB +2048MB, committed=4096MB +1024MB
- Other (reserved=2048MB +1024MB, committed=1536MB +512MB)
  (malloc=1024MB +512MB #6789)

New/Grown Large Mappings (>100MB):
  7f9100000000-7f9200000000
  7f9300000000-7f9400000000
```

**Purpose**: Identify which categories and regions are growing

## Comparison: Old vs New

### Comprehensive Script (Old)

**Console output during run**:
```
========================================
Comprehensive Memory Analysis
========================================
[... 50 lines of initial setup ...]

========================================
Capturing Snapshot #0 at [date]
========================================

[1/7] Native Memory Tracking summary...
[2/7] Native Memory Tracking detail...
[3/7] Memory maps...
[4/7] Process status...
[5/7] Detailed memory breakdown...
[6/7] Heap histogram...
[7/7] GC information...

Quick Summary:
--------------
Total RSS: 2048 MB

Memory by type (from smaps):
  Total Size: 6144 MB
  RSS: 2048 MB
  Anonymous: 1536 MB
  Swap: 0 MB

Memory-mapped regions: 1247

Large anonymous mappings (>100MB):
  7f8a00000000-7f8b00000000: 256 MB
  7f8c00000000-7f8d00000000: 256 MB
  [... 20 more lines ...]

NMT 'Other' category (native code allocations):
  (malloc=512MB #12345)

Snapshot saved to: /path/to/snapshot_0

Waiting 30 seconds for next snapshot...

========================================
Capturing Snapshot #1 at [date]
========================================
[... REPEAT EVERY 30 SECONDS ...]

========================================
Comparing Snapshot #0 to #1
========================================

RSS Change: 32 MB
[... 50 lines of comparison ...]

Comparison saved to: comparison_0_to_1.txt

Waiting 30 seconds for next snapshot...

[... THOUSANDS MORE LINES ...]
```

**Result**: 100+ lines per snapshot × 120 snapshots/hour = **12,000+ lines per hour**

### Sampled Script (New)

**Console output during run**:
```
Memory analysis started: /path/to/memory_analysis_sampled_20251105_173022
Monitor log: monitor.log
RSS timeline: rss_continuous.log
Snapshot log: snapshots.log

Application PID: 123456

Mode: manual | PID: 123456 | Script PID: 789012
Logs: monitor.log, snapshots.log, rss_continuous.log
Manual snapshot: kill -USR1 789012
Stop: Ctrl+C or kill 789012

Warming up (10s)...
Taking initial snapshot...
Monitoring active. Ctrl+C to stop.

[SILENCE - all details go to log files]

Analysis complete. Results in: /path/to/memory_analysis_sampled_20251105_173022
```

**Result**: ~20 lines total (even for multi-hour runs)

## Benefits for Real-World Use

### 1. Background Monitoring

Run as a background process without overwhelming logs:

```bash
nohup ./run_sampled_memory_analysis.sh > /dev/null 2>&1 &
# All output goes to log files in memory_analysis_sampled_*/
```

### 2. SSH Sessions

No terminal spam when monitoring via SSH:

```bash
ssh server
./run_sampled_memory_analysis.sh
# Terminal stays clean, you can do other work
```

### 3. Log Management

Structured logs are easier to archive and analyze:

```bash
# Grep for specific events
grep "Snapshot.*complete" memory_analysis_*/monitor.log

# Check RSS growth rate
tail -100 memory_analysis_*/rss_continuous.log | awk -F, '{print $3}'

# View only important events
cat memory_analysis_*/monitor.log | grep -E "Snapshot|Comparison|Manual"
```

### 4. Production Deployment

Safe to run on production servers:

```bash
# Light mode - <1% overhead, logs only
MODE=light ./run_sampled_memory_analysis.sh

# Check logs periodically
tail -20 memory_analysis_sampled_*/monitor.log
```

## Quick Reference

### What Goes Where

| Information | Console | monitor.log | snapshots.log | rss_continuous.log |
|-------------|---------|-------------|---------------|-------------------|
| Startup info | ✅ | ✅ | ❌ | ❌ |
| Application PID | ✅ | ✅ | ❌ | ❌ |
| Snapshot progress | ❌ | ✅ (timestamp) | ✅ (detailed) | ❌ |
| Memory breakdowns | ❌ | ❌ | ✅ | ❌ |
| Comparisons | ❌ | ✅ (summary) | ❌ | ❌ |
| RSS timeline | ❌ | ❌ | ❌ | ✅ |
| Final summary | ✅ (path) | ✅ (detailed) | ❌ | ❌ |

### Monitoring During Run

```bash
# Watch high-level timeline
tail -f memory_analysis_sampled_*/monitor.log

# Watch RSS in real-time
tail -f memory_analysis_sampled_*/rss_continuous.log

# View latest snapshot details
tail -100 memory_analysis_sampled_*/snapshots.log

# Trigger manual snapshot
kill -USR1 <script_pid>
```

## Migration from Verbose Scripts

If you have scripts that pipe output:

```bash
# OLD (thousands of lines)
./run_comprehensive_memory_analysis.sh 2>&1 | tee output.log

# NEW (clean console, structured logs)
./run_sampled_memory_analysis.sh
# Check memory_analysis_sampled_*/monitor.log afterward
```

## Summary

**Quiet logging mode advantages**:
- ✅ Clean console (20 lines vs 12,000+ per hour)
- ✅ Structured log files (easy to grep/parse)
- ✅ Production-safe (can run in background)
- ✅ No terminal spam during SSH sessions
- ✅ All details preserved in log files
- ✅ Easy to spot important events (timestamps in monitor.log)

**Console philosophy**:
- **Minimal**: Only what you need to know RIGHT NOW
- **Actionable**: PID for manual snapshots, path to logs
- **Silent**: No continuous spam during monitoring
- **Final**: Where to find results when done

**Log file philosophy**:
- **Complete**: Every detail captured
- **Structured**: Easy to parse and analyze
- **Timestamped**: Know when events happened
- **Separated**: Different concerns in different files (timeline vs details vs data)
