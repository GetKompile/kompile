# Valgrind Sampled Leak Detection

## Overview

`run_valgrind_leaks_only.sh` provides **periodic leak snapshots** with full stack traces, making it safe to kill the process at any time without losing data.

## Key Features

- **Periodic snapshots**: Captures leak summaries and stack traces every 2 minutes (configurable)
- **Safe to kill**: All snapshots saved to separate files - can kill with `kill -9` anytime
- **Full stack traces**: 30-frame depth to identify allocation sites
- **Correlates with RSS**: Each snapshot includes memory usage for comparison
- **Suppressed noise**: JVM/JavaCPP/OpenBLAS false positives filtered out
- **Incremental analysis**: Can analyze partial results if process killed early

## Usage

### Basic Run
```bash
cd /home/agibsonccc/Documents/GitHub/kompile/kompile-rag-builds/kompile-sample/project
./run_valgrind_leaks_only.sh
```

### Custom Snapshot Interval
```bash
# Snapshot every 3 minutes instead of 2
SNAPSHOT_INTERVAL=180 ./run_valgrind_leaks_only.sh
```

### Kill Safely
```bash
# Find the process
ps aux | grep valgrind

# Kill with signal 9 (all snapshots already saved)
kill -9 <PID>

# Or kill the monitoring script (will wait for app to finish)
kill <script_PID>
```

## Output Structure

```
valgrind_sampled_20251105_213045/
├── monitor.log                  # Timeline of snapshots with timestamps
├── valgrind_full.log           # Complete Valgrind output (all errors/leaks)
├── leak_snapshot_0.txt         # First snapshot (at 2 min)
├── leak_snapshot_1.txt         # Second snapshot (at 4 min)
├── leak_snapshot_2.txt         # Third snapshot (at 6 min)
└── leak_snapshot_final.txt     # Final snapshot after app exits
```

## Snapshot Contents

Each `leak_snapshot_N.txt` file contains:

1. **Timestamp**: When the snapshot was taken
2. **Current Leak Summary**: Definite/possible/reachable counts
3. **Recent Stack Traces**: Last 200 lines of leak stack traces
4. **RSS Memory Usage**: For correlation with actual memory growth

## Analysis Workflow

### 1. Monitor Progress
```bash
# Watch the monitoring log
tail -f valgrind_sampled_*/monitor.log
```

### 2. Check Intermediate Snapshots
```bash
# View leak growth over time
cat valgrind_sampled_*/leak_snapshot_*.txt | grep "definitely lost"

# See stack traces at specific point
cat valgrind_sampled_*/leak_snapshot_2.txt | less
```

### 3. Analyze Final Results
```bash
# View final leak summary
cat valgrind_sampled_*/leak_snapshot_final.txt

# Search for specific allocation sites
grep -A 30 "libnd4jcpu.so" valgrind_sampled_*/leak_snapshot_final.txt

# Find NDArray-related leaks
grep -A 30 "NDArray" valgrind_sampled_*/leak_snapshot_final.txt
```

### 4. Correlate with Memory Growth
```bash
# Compare RSS growth with leak detection
cd valgrind_sampled_*/
cat monitor.log | grep "complete (RSS"

# Example output:
# [19:44:00] Snapshot #0 complete (RSS: 2500 MB)
# [19:46:00] Snapshot #1 complete (RSS: 5800 MB)  ← +3.3 GB in 2 min
# [19:48:00] Snapshot #2 complete (RSS: 9200 MB)  ← +3.4 GB in 2 min
```

## Why Sampling?

**Without sampling** (standard Valgrind):
- Leak check only happens at program exit
- If you kill with signal 9, you lose ALL leak data
- Long-running processes may OOM before completion
- No visibility into leak growth rate

**With sampling** (this script):
- Leak data captured every 2 minutes
- Safe to kill anytime - all snapshots preserved
- Can see leak growth rate over time
- Can analyze partial results if process killed
- Correlate leak detection with RSS growth timeline

## Performance Impact

- **Overhead**: ~400% (same as standard Valgrind)
- **Snapshot cost**: Minimal (just grep on existing log file)
- **Disk usage**: ~10-50 MB per snapshot depending on leak count

## Comparison with NMT

| Feature | Valgrind Sampling | NMT Detail |
|---------|------------------|------------|
| **Tracks mmap() leaks** | ✅ Yes | ❌ No (only JVM-managed) |
| **Stack traces** | ✅ Full native stacks | ⚠️ Java→JNI boundary only |
| **Periodic snapshots** | ✅ Yes (every 2 min) | ✅ Yes (manual) |
| **Safe to kill** | ✅ Yes | ⚠️ Loses unsaved data |
| **Overhead** | ~400% | ~5-10% |
| **Best for** | Native memory leaks (mmap) | JVM heap/metaspace issues |

**For the 16 GB anonymous mmap leak**: Valgrind is the right tool because:
1. NMT showed 0 MB in "Other" category (leak is invisible to NMT)
2. 99.3% of growth is anonymous mmap regions
3. Need native stack traces to libnd4jcpu.so allocations
4. Valgrind tracks ALL memory, including direct mmap() calls

## Expected Output

Based on the memory growth pattern (2 GB/min for 8 minutes):

**Snapshot timeline**:
```
Snapshot #0 (2 min):  ~2 GB leaked,  ~500 leak records
Snapshot #1 (4 min):  ~6 GB leaked,  ~1500 leak records
Snapshot #2 (6 min):  ~10 GB leaked, ~2500 leak records
Snapshot #3 (8 min):  ~14 GB leaked, ~3500 leak records
Snapshot #4 (10 min): ~16 GB leaked, ~4000 leak records (plateau)
```

**Stack traces should show**:
- Allocations via `mmap()` system calls
- Calls through `libnd4jcpu.so` (NDArray buffer allocation)
- Possibly JavaCPP `Pointer.allocate()` or similar
- Missing `munmap()` or `free()` calls

## Troubleshooting

### "No leak data available yet"
- Valgrind hasn't detected leaks yet (normal for first snapshot)
- Application hasn't allocated much memory yet

### Empty snapshot files
- Application finished before first snapshot interval
- Increase snapshot frequency or run longer workload

### Process killed but no final snapshot
- Use `kill` (SIGTERM) not `kill -9` to allow graceful shutdown
- Or analyze the last periodic snapshot instead

### Suppressions hiding real leaks
- Check `valgrind_java_optimized.supp` for overly broad rules
- Temporarily disable suppressions: comment out `--suppressions` line

## Next Steps After Getting Stack Traces

1. **Identify allocation sites**: Look for patterns in stack traces
   - Which functions allocate but don't free?
   - Are there loops creating NDArrays?
   - Missing `.close()` calls?

2. **Correlate with code**: Map stack traces back to source
   ```bash
   addr2line -e libnd4jcpu.so <address>
   ```

3. **Fix the leak**: Common fixes
   - Add missing `NDArray.close()` calls
   - Use workspace context for temporary arrays
   - Fix reference counting in native code
   - Add proper cleanup in destructors

4. **Verify the fix**: Run script again and confirm:
   - Leak count stops growing
   - RSS stays stable
   - Snapshots show same leak count over time
