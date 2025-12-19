# Memory Analysis Tools - Current Inventory

## Active Scripts

### Primary Tool (Recommended)

**`run_sampled_memory_analysis.sh`** - Production-ready memory profiling
- **Purpose**: Low-overhead memory leak detection and monitoring
- **Features**:
  - Continuous RSS monitoring (5s intervals)
  - Configurable snapshot intervals (default: 2 minutes)
  - Three modes: auto, manual, light
  - Quiet logging (all details to log files)
  - Skips expensive operations by default (NMT detail, heap histogram)
  - Always captures: NMT summary, /proc/maps, /proc/smaps, GC info
- **Overhead**: 2-4% (auto mode), <1% (light mode)
- **Use for**: Production monitoring, long-running leak investigation
- **Output**:
  - `monitor.log` - Timeline of events
  - `snapshots.log` - Detailed snapshot info
  - `rss_continuous.log` - CSV timeline
  - `comparison_*.txt` - Snapshot diffs

### Valgrind Options (Heavy, for specific debugging)

**`run_valgrind_lightweight.sh`** - Lighter Valgrind configuration
- **Purpose**: Valgrind with optimized suppressions and reduced overhead
- **Overhead**: ~200-300% (still heavy)
- **Use for**: When you need Valgrind-specific leak detection
- **Note**: Still too slow for production, use only for targeted debugging

**`run_valgrind_minimal.sh`** - Minimal Valgrind (fastest possible)
- **Purpose**: Absolute bare minimum Valgrind overhead
- **Features**: Summary-only, definite leaks only, no origin tracking
- **Overhead**: ~200% (fastest Valgrind can be)
- **Use for**: Quick sanity checks when Valgrind is required
- **Note**: Still much slower than sampled analysis

### Utility Scripts

**`find_libnd4j_heaps.sh`** - Find libnd4j heap allocations
- **Purpose**: Specific helper to identify libnd4j memory regions
- **Use for**: When you've identified a leak and need to narrow down to libnd4j

**`list_memory_reports.sh`** - List and summarize memory analysis results
- **Purpose**: Browse and compare previous analysis runs
- **Use for**: Historical analysis after multiple runs

**`run-with-jemalloc.sh`** - Run with jemalloc profiling
- **Purpose**: Alternative heap profiler (note: doesn't track mmap/DirectByteBuffers)
- **Use for**: Native-only heap profiling when combined with other tools
- **Note**: As you mentioned, "won't fully show the source of memory growth"

## Removed Scripts (Superseded)

These scripts have been removed as they're superseded by `run_sampled_memory_analysis.sh`:

### ❌ `run_comprehensive_memory_analysis.sh`
- **Reason**: Too verbose, 50-100% overhead
- **Replacement**: Use `run_sampled_memory_analysis.sh` (same data, 2-4% overhead)

### ❌ `run_native_memory_tracking.sh`
- **Reason**: Only NMT, missing /proc/maps and smaps
- **Replacement**: Use `run_sampled_memory_analysis.sh` (includes NMT + more)

### ❌ `run_valgrind_test.sh`
- **Reason**: Full Valgrind with XML output (2000% overhead)
- **Replacement**: Use `run_valgrind_minimal.sh` if Valgrind is truly needed

### ❌ `run_valgrind_quick.sh`
- **Reason**: Duplicate of minimal, no significant difference
- **Replacement**: Use `run_valgrind_minimal.sh`

### ❌ `run_massif_heap_profile.sh`
- **Reason**: Massif only shows heap, misses mmap/DirectByteBuffers
- **Replacement**: Use `run_sampled_memory_analysis.sh` with CAPTURE_SMAPS=true

## Decision Tree: Which Tool to Use?

```
Do you need Valgrind-specific features (race detection, etc.)?
├─ YES → Use run_valgrind_minimal.sh (accept 200% overhead)
└─ NO  → Continue below

Is this production monitoring or a long-running investigation?
├─ YES → Use run_sampled_memory_analysis.sh MODE=light (< 1% overhead)
└─ NO  → Continue below

Do you need detailed snapshots at regular intervals?
├─ YES → Use run_sampled_memory_analysis.sh MODE=auto (2-4% overhead)
└─ NO  → Continue below

Do you want to control snapshot timing manually?
├─ YES → Use run_sampled_memory_analysis.sh MODE=manual
└─ NO  → Use run_sampled_memory_analysis.sh (default auto mode)
```

## Quick Reference

| Scenario | Recommended Tool | Mode | Overhead |
|----------|-----------------|------|----------|
| Production server monitoring | `run_sampled_memory_analysis.sh` | light | <1% |
| Development leak investigation | `run_sampled_memory_analysis.sh` | auto | 2-4% |
| Interactive debugging | `run_sampled_memory_analysis.sh` | manual | 2-4% |
| Need NMT detail + heap histogram | `run_sampled_memory_analysis.sh` | auto with CAPTURE_NMT_DETAIL=true CAPTURE_HEAP_HISTOGRAM=true | 5-10% |
| Valgrind is absolutely required | `run_valgrind_minimal.sh` | N/A | 200% |
| Lightweight Valgrind debugging | `run_valgrind_lightweight.sh` | N/A | 200-300% |
| Find specific libnd4j allocations | `find_libnd4j_heaps.sh` | N/A | Instant |
| Compare historical results | `list_memory_reports.sh` | N/A | Instant |

## Configuration Examples

### Sampled Analysis - Common Configurations

```bash
# Production (minimal overhead, manual snapshots)
MODE=light ./run_sampled_memory_analysis.sh

# Development (2-minute snapshots, skip expensive operations)
./run_sampled_memory_analysis.sh

# Detailed (5-minute snapshots with NMT detail)
SNAPSHOT_INTERVAL=300 CAPTURE_NMT_DETAIL=true ./run_sampled_memory_analysis.sh

# High-frequency (1-minute snapshots for testing)
SNAPSHOT_INTERVAL=60 ./run_sampled_memory_analysis.sh

# Manual trigger only
MODE=manual ./run_sampled_memory_analysis.sh
# Then: kill -USR1 <script_pid>
```

## Documentation

- **`SAMPLED_VS_COMPREHENSIVE_ANALYSIS.md`** - Performance comparison, usage examples
- **`QUIET_LOGGING.md`** - Explanation of quiet logging mode
- **`MEMORY_PROFILING_COMPARISON.md`** - Tool comparison (NMT, jemalloc, Valgrind)
- **`DEBUG_SYMBOLS_REQUIREMENTS.md`** - Debug symbol requirements for each tool
- **`MEMORY_TESTING_GUIDE.md`** - LSAN vs Valgrind differences
- **`VALGRIND_OPTIMIZATION_NOTES.md`** - Valgrind optimization details

## Summary

**For 99% of use cases**: Use `run_sampled_memory_analysis.sh`
- Tracks ALL memory types (malloc, mmap, DirectByteBuffers, arenas)
- Low overhead (2-4% auto, <1% light)
- Quiet logging (production-safe)
- Configurable capture options
- Three operating modes

**Only use Valgrind** (`run_valgrind_minimal.sh` or `run_valgrind_lightweight.sh`) when:
- You need Valgrind-specific features (race detection, etc.)
- You're debugging a very short-lived process
- You've already identified the issue with sampled analysis and need deeper investigation
- You can afford 200-300% overhead

**Utility scripts** (`find_libnd4j_heaps.sh`, `list_memory_reports.sh`) are supplementary tools for post-analysis.
