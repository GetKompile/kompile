# Memory Testing Guide for Kompile Sample

This directory contains scripts for comprehensive memory leak and heap profiling using Valgrind.

## Understanding the Problem: LSAN vs Valgrind

### LeakSanitizer (LSAN) Behavior

**What LSAN detects:**
- Only **unreachable** memory (no valid pointers to it anywhere)
- Called "definitely lost" leaks
- Reports immediately and terminates program

**What LSAN DOES NOT detect:**
- Memory that still has valid pointers but hasn't been freed
- Called "still reachable" memory
- This is what causes your 100GB issue!

**Example:**
```java
// LSAN reports this (unreachable):
void leak1() {
    void* p = malloc(100MB);
    // p goes out of scope - unreachable
}

// LSAN DOES NOT report this (still reachable):
class Cache {
    byte[] data = new byte[100GB];  // Still has reference
    // Never freed, but still has pointer
}
```

### Why Valgrind is Better for Your Use Case

Valgrind's **leak-check=full** mode reports BOTH:
1. **Definitely lost** - Unreachable (what LSAN reports)
2. **Still reachable** - Has pointers but not freed (what LSAN ignores)

Your 100GB memory usage is likely in category #2, which is why you need Valgrind.

## Available Scripts

### 1. `run_valgrind_test.sh` - Comprehensive Analysis

**Use this for:** Full leak detection with detailed reports

**Features:**
- Detects all leak types: definitely lost, still reachable, possibly lost
- Generates XML report for visualization
- Tracks origins of uninitialized values
- High-resolution stack traces (30 frames)
- Auto-generates JVM suppressions

**Usage:**
```bash
./run_valgrind_test.sh
```

**Outputs:**
- `valgrind_YYYYMMDD_HHMMSS.log` - Detailed text log
- `valgrind_YYYYMMDD_HHMMSS.xml` - XML report for GUI tools
- `leak_summary_YYYYMMDD_HHMMSS.txt` - Quick summary

**When to use:**
- Initial investigation of memory issues
- Before/after comparisons
- Generating reports for analysis

**Performance impact:** High (5-20x slowdown)

### 2. `run_valgrind_quick.sh` - Fast Leak Check

**Use this for:** Quick checks during development

**Features:**
- Summary-level leak detection
- Minimal overhead (faster execution)
- Basic stack traces (20 frames)
- Only shows definite/possible leaks

**Usage:**
```bash
./run_valgrind_quick.sh
```

**Outputs:**
- `valgrind_quick_YYYYMMDD_HHMMSS.log` - Concise log

**When to use:**
- Iterative development (fix → test → repeat)
- Quick sanity checks
- CI/CD integration

**Performance impact:** Medium (3-10x slowdown)

### 3. `run_massif_heap_profile.sh` - Heap Growth Analysis

**Use this for:** Understanding WHERE and WHEN memory is allocated

**Features:**
- Tracks ALL heap allocations over time
- Shows memory growth patterns
- Identifies top allocation sites
- Visualizes heap snapshots

**Usage:**
```bash
./run_massif_heap_profile.sh
```

**Outputs:**
- `massif.out.YYYYMMDD_HHMMSS` - Raw massif data
- `massif_report_YYYYMMDD_HHMMSS.txt` - Text report (if ms_print available)

**When to use:**
- Tracking heap growth over time
- Finding "still reachable" allocations (your 100GB issue!)
- Optimizing memory usage patterns
- Understanding allocation hot spots

**Performance impact:** Medium (3-10x slowdown)

## Workflow Recommendations

### Scenario 1: "I have 100GB memory usage but LSAN reports nothing"

**Root cause:** Memory is "still reachable" (has pointers but not freed)

**Solution:**
1. Run `./run_valgrind_test.sh`
2. Let application reach high memory state
3. Interrupt with Ctrl+C
4. Look for "still reachable" in leak summary
5. Check log for allocation stack traces

**What to look for:**
```
LEAK SUMMARY:
   definitely lost: 0 bytes in 0 blocks
   indirectly lost: 0 bytes in 0 blocks
     possibly lost: 0 bytes in 0 blocks
   still reachable: 107,374,182,400 bytes in 12,345 blocks  ← YOUR 100GB
```

### Scenario 2: "I want to see WHERE the 100GB is allocated"

**Solution:**
1. Run `./run_massif_heap_profile.sh`
2. Let application allocate memory
3. Interrupt with Ctrl+C
4. View report: `ms_print massif.out.*`

**What to look for:**
```
Peak snapshot: 100,000 MB
  ->99.9% (99,990,000,000B) 0x123456: some_function_name
    ->80.0% (80,000,000,000B) 0x234567: array_allocation
      ->60.0% (60,000,000,000B) in Java_org_nd4j_*
```

This tells you exactly which functions allocated the bulk of memory.

### Scenario 3: "I want to track memory over time"

**Solution:**
1. Run `./run_massif_heap_profile.sh`
2. Exercise different workloads
3. Analyze snapshots at different time points

**Visualization:**
```bash
# If massif-visualizer installed (GUI):
massif-visualizer massif.out.*

# Or text-based:
ms_print massif.out.* | less
```

### Scenario 4: "I need to compare before/after a fix"

**Solution:**
```bash
# Before fix
./run_valgrind_test.sh  # Note the timestamp

# Apply fix

# After fix
./run_valgrind_test.sh  # Note the new timestamp

# Compare
diff leak_summary_TIMESTAMP1.txt leak_summary_TIMESTAMP2.txt
```

## Understanding Valgrind Output

### Leak Categories

**1. Definitely lost**
```
100,000 bytes in 1 blocks are definitely lost in loss record 1 of 100
   at 0x4C2B1E0: malloc (vg_replace_malloc.c:299)
   by 0x12345: my_function (file.c:42)
```
- No pointers to this memory
- True leak (LSAN would report this)
- **Action:** Fix immediately

**2. Still reachable**
```
100,000,000,000 bytes in 10,000 blocks are still reachable in loss record 50 of 100
   at 0x4C2B1E0: malloc (vg_replace_malloc.c:299)
   by 0x23456: cache_allocate (cache.c:100)
```
- Has valid pointers but not freed
- **This is your 100GB issue!**
- Not always a bug (e.g., program-lifetime caches), but worth investigating
- **Action:** Review if memory should be freed earlier

**3. Possibly lost**
```
50,000 bytes in 5 blocks are possibly lost in loss record 25 of 100
```
- Pointer exists but may not be to the start of the block
- Could be interior pointers or complex pointer chains
- **Action:** Investigate if "possibly lost" is large

**4. Indirectly lost**
```
20,000 bytes in 200 blocks are indirectly lost in loss record 10 of 100
```
- Memory pointed to by "definitely lost" blocks
- Will be freed if parent block is fixed
- **Action:** Fix the parent leak

### JVM-Specific Notes

**Suppressions:**
The Java wrapper automatically generates suppressions for `libjvm.so` to filter out:
- JVM internal allocations
- JIT compiler memory
- Class metadata
- Thread-local storage

This leaves you with **YOUR** application's leaks, not JVM internals.

**JIT Disabled:**
The wrapper adds `-Djava.compiler=none` to:
- Disable JIT compilation
- Provide cleaner stack traces
- Reduce JVM memory overhead

This makes your application slower but reports much clearer.

## Advanced Analysis Commands

### Find Top Allocation Sites
```bash
# Extract all stack traces with "still reachable"
grep -B 10 "still reachable" valgrind_*.log | grep "at 0x" | sort | uniq -c | sort -rn | head -20
```

### Count Leaks by Size
```bash
# Show breakdown of leak sizes
grep "are definitely lost\|are still reachable" valgrind_*.log | awk '{print $1, $2, $7}' | sort -rn
```

### Find Leaks from Specific Library
```bash
# Example: Find nd4j-related allocations
grep -A 20 "still reachable" valgrind_*.log | grep -i "nd4j\|javacpp"
```

### Visualize with massif-visualizer
```bash
# Install on Fedora/RHEL
sudo dnf install massif-visualizer

# Install on Ubuntu/Debian
sudo apt-get install massif-visualizer

# Run
massif-visualizer massif.out.*
```

## Performance Tuning

### If Valgrind is Too Slow

**Option 1: Use quick mode**
```bash
./run_valgrind_quick.sh  # Uses --leak-check=summary
```

**Option 2: Reduce workload**
- Test with smaller dataset
- Shorter execution time
- Single-threaded mode

**Option 3: Use sampling**
```bash
# In run_valgrind_test.sh, add:
--vgdb=no  # Disable debugger support (faster)
--track-origins=no  # Don't track uninitialized values (faster)
```

**Option 4: Run on a subset**
- Profile specific operations
- Use test harness with known inputs

## Interpreting Results for Your 100GB Issue

### What You're Looking For

**Goal:** Find allocations totaling ~100GB in "still reachable"

**Expected output:**
```
LEAK SUMMARY:
   definitely lost: 0 bytes in 0 blocks
   indirectly lost: 0 bytes in 0 blocks
     possibly lost: 1,024,000 bytes in 100 blocks
   still reachable: 107,374,182,400 bytes in 500,000 blocks  ← HERE!
        suppressed: 0 bytes in 0 blocks
```

### Next Steps After Finding Allocations

1. **Identify the call stack:**
   - Look for Java class names in stack traces
   - Find JNI calls (Java_org_nd4j_*)
   - Note native library calls (libnd4jcpu.so)

2. **Group by allocation site:**
   - Are they from a specific class?
   - Are they NDArray allocations?
   - Are they cached objects?

3. **Determine if it's a bug or design:**
   - **Bug:** Memory that should be freed but isn't
   - **Design:** Long-lived caches or program-lifetime objects

4. **Fix:**
   - Add proper cleanup/free calls
   - Implement cache eviction
   - Use weak references in Java
   - Call `triggerLeakCheck()` at specific points (for LSAN)

## Troubleshooting

### "Java wrapper not found"
```bash
# Ensure deeplearning4j is at the expected path:
ls -la /home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests/bin/java

# Or edit the scripts to point to correct location
```

### "JAR file not found"
```bash
# Build the project first
cd /home/agibsonccc/Documents/GitHub/kompile/kompile-rag-builds/kompile-sample/project
mvn clean package
```

### "valgrind: command not found"
```bash
# Fedora/RHEL
sudo dnf install valgrind

# Ubuntu/Debian
sudo apt-get install valgrind
```

### "Suppressions not working"
The Java wrapper auto-generates suppressions. If you see many JVM-related reports:
```bash
# Check if suppressions file was created
ls -la valgrind_suppressions.supp

# Verify it contains libjvm.so entries
cat valgrind_suppressions.supp
```

### "Too many errors, output truncated"
Valgrind has a default error limit. The scripts already use `--error-limit=no`, but if you still see truncation:
```bash
# In the script, ensure you have:
--error-limit=no
```

## Comparison: LSAN vs Valgrind

| Feature | LeakSanitizer (LSAN) | Valgrind |
|---------|---------------------|----------|
| Detects unreachable memory | ✅ Yes | ✅ Yes |
| Detects still-reachable memory | ❌ **No** | ✅ **Yes** |
| Runtime overhead | Low (2x slowdown) | High (5-20x slowdown) |
| Requires recompilation | ✅ Yes (-fsanitize=address) | ❌ No (works on any binary) |
| Stack trace quality | Excellent | Very good |
| XML/GUI reports | ❌ No | ✅ Yes |
| Suppressions | Basic | Advanced |
| Heap profiling over time | ❌ No | ✅ Yes (Massif) |
| Best for | Finding true bugs | Understanding memory usage |

**For your 100GB issue:** Use Valgrind, not LSAN.

## Further Reading

- [Valgrind Documentation](https://valgrind.org/docs/manual/manual.html)
- [Massif Manual](https://valgrind.org/docs/manual/ms-manual.html)
- [Understanding Leak Categories](https://valgrind.org/docs/manual/mc-manual.html#mc-manual.leaks)
- [Suppressing Errors](https://valgrind.org/docs/manual/manual-core.html#manual-core.suppress)

## Contact

If you find issues with these scripts or have questions:
- Check script comments for inline documentation
- Review Valgrind logs for error messages
- Ensure Valgrind version >= 3.15 for best Java support

---

**Quick Reference:**
- Full analysis: `./run_valgrind_test.sh`
- Quick check: `./run_valgrind_quick.sh`
- Heap profiling: `./run_massif_heap_profile.sh`
- View results: `less valgrind_*.log` or `ms_print massif.out.*`
