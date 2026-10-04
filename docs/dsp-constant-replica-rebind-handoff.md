# DSP Constant-Replica Rebind (Frozen-Pin ABA) Handoff

Written 2026-10-02 between about 06:40 and 07:00 JST, when the previous agent (Claude Code) stopped. The user asked for a fresh model to take over.

**Status, 2026-10-02 ~11:00 JST.** The continuation session `0ba0fa99-d21f-4123-b01b-8c646939c4ec` has finished Steps 1-5; Step 6 (the parent goal) is next.

- Both gaps are fixed with tests that fail without the fix.
- The regression sweep after the Gap B install (proc-018, 10:26-10:51 JST) matches the earlier runs in every selection. g3 ran 1357 tests: the 3 known `DspCompositeReplayTest` test13 placement failures (open item 2 in the memory note) and 1 opt-in skip, covered by the five `-Dnd4j.dsp.stagingFault` forks. Every selection had no lifecycle errors, no CUDA errors and no hs_err.
- Everything is installed and uncommitted.
- The full record is in `~/.claude/projects/-home-agibsonccc-Documents-GitHub-kompile/memory/reference_dsp_constant_replica_rebind.md`: code anchors, diagnostic lines, tests, mutant results, the sweep and the open questions for the user.
- That session's logs are `.kompile/process-output/0ba0fa99-d21f-4123-b01b-8c646939c4ec/proc-NNN.log`.
- Line numbers below come from the original handoff and have since shifted. The memory note has the current ones.

## Agent prompt

Work in:

- `/home/agibsonccc/Documents/GitHub/kompile`
- `/home/agibsonccc/Documents/GitHub/deeplearning4j` (branch `ag_new_release_updates_2`; stay on it)

Before anything else:

1. Read both repositories' `AGENTS.md` files.
2. Read the Kompile memory index `~/.claude/projects/-home-agibsonccc-Documents-GitHub-kompile/memory/MEMORY.md` and the notes named under Step 5.
3. Load the `/libnd4j-math-kernels` skill before reading or changing any DL4J native or DSP code. That means `/home/agibsonccc/.claude/skills/libnd4j-math-kernels/SKILL.md` plus the references it routes to.

Any agent you delegate native, DSP or device work to must also be told to load the skill from that path.

## Important constraints

**Git and the shared tree**

- Both repositories carry many uncommitted changes from other sessions. Do not revert, overwrite, clean, stash or reset them.
- These are banned: `git checkout`, `git revert`, `git reset`, `git clean`, `git stash` and worktrees.
- Never switch or create branches.
- Commit only when the user asks.
- The Kompile autosave has committed the deeplearning4j repo before, as "Auto-commit: update project state". The last one was `d505b1d997`, 2026-10-01 11:24 JST. Check `git log` before assuming a hunk is still uncommitted.

**DSP**

- Never disable DSP or the optimizer, whether as a fix, a bisection or a validation path. The user said: "Don't do this DSP disable garbage." Root-cause problems with DSP on.

**Build and test**

- Maven is `/home/agibsonccc/dev-apps/mvn/bin/mvn`.
- Install every changed DL4J module before running `platform-tests`, or the tests run a stale `~/.m2` classpath.
- DL4J tests live only in `deeplearning4j/platform-tests`. Run them from that directory:
  - Use targeted `-Dtest=` selections with `-Dsurefire.failIfNoSpecifiedTests=false`.
  - Pipe the output through `tee`.
  - Never run a full reactor test.
- Serialize native builds and GPU tests.
- Never run Maven `clean` on libnd4j.
- Use `org.eclipse.deeplearning4j:*` coordinates. Never use the old namespaces `org.deeplearning4j`, `org.nd4j` or `org.datavec`, and never use `-platform` dependencies.
- Do not put env vars in front of `mvn test`.
- Never use `CUDA_VISIBLE_DEVICES`. DL4J owns GPU arbitration.
- No reflection in tests. Widen the field and expose it through `DynamicShapePlanExecutorTestAccess`.
- Before diagnosing a failing test, check memory and git history.
- Never call a failure "pre-existing", "environmental" or "out of scope". Never let an odd regression or an overlong run slide.

**Mutation work**

- Never install a mutant into `~/.m2`.
- Never write into `libnd4j/blasbuild`.
- Never let a run touch the shared JavaCPP cache `~/.javacpp/cache`. See [JavaCPP cache hazard](#javacpp-cache-hazard-read-before-shadowing-any-native-library).

**Tools and processes**

- Use the kompile MCP tools for file I/O.
- Run builds and tests through the kompile `process` tool. Do not use Claude's `run_in_background` or a background Agent.
- Lock files with `edit_coordinator` before editing. It needs the full session and lock ids.
- No broad `pkill` or `killall`. Kill only exact PIDs that you started.
- Never use qwen.
- Never read credentials, including any stray `auth.json.tmp-*`.
- Never drive a Claude sign-in.

**GPU lane**

- `bash /tmp/dl4j-lane.sh status` reports `HELD: claude-int8-smallfix … since=2026-09-28T17:47:18+09:00`. That lane belongs to another session. Never release it; that is the user's call.
- The previous agent ran its native build and GPU tests while that lane was held. Before each run, it checked that no other `platform-tests` fork was running.
- At about 06:25 JST another session ran a surefire fork from the deeplearning4j **root**. `run-gate.sh`'s `platform_forks()` only detects forks whose cwd is `platform-tests`. Before every GPU run, also check `pgrep -f surefire.booter.ForkedBooter`.

## Objective

### Parent goal (still open)

Audit and extend the standard Kompile CLI terminal chat and web chat for image+text conversations.

- Local VLMs and image-capable remote models share one chat entry point.
- The acceptance model is SmolVLM-Instruct (~2B, fp32 ONNX) at `/tmp/kompile-vlm-test/smolvlm-instruct`. It takes 26 GB with its caches. `/tmp` is tmpfs, so that is RAM.
- SmolDocling-256M is for plumbing and regression checks only.
- The user's standing instruction is "Keep implementing and testing."
- Shared todos #7, #9 and #11 are in progress. All sessions share that list: run `todoread` first, and never `set` it.

### This sub-task

`DynamicShapePlanExecutor.clearReplicaCaches()` frees the executor's cross-device constant replicas between pages and images. Afterwards, the native frozen plan stayed bound to the freed replica and replayed CUDA graphs captured over it.

The fixes below are implemented, built and installed, and the tests pass. Validation is unfinished. Do the remaining work in this order:

1. Native mutation check. **Done (proc-004):**
   - S0: 3/3 pass.
   - S1: killed, by `…SteadyState` only.
   - S2: killed by `…Pages` and `…SteadyState`. `…UnderView` survived S2 because no recycling happened in that test, so it does not cover the pre-pass.
2. Java mutation check. **Done (proc-005):**
   - M0: 3/3 pass.
   - M1: killed by a SIGSEGV in `sd::NDArray::shapeInfo()` at page 1 call 0. That is the predicted use-after-free through the stale binding.
   - M2: killed, 3/3.
3. Regression sweep, one run at a time. **Done; rerun after Gap B as proc-018.**
4. Fix the two known gaps. **Done.**
5. Write the memory notes. **Done.**
6. Return to the parent goal. **Next.**

## Root cause

**How a cross-device constant is bound**

1. A constant lives on device 1 and feeds slots on device 0.
2. The executor migrates it into a native constant replica on device 0 and caches that replica in `nativeConstantReplicaCache`.
3. The replica is bound as an external input in the plan's `OpaqueContext`.
4. Once shapes freeze, the native plan pins the replica as a protected weight in the global frozen-pin registry.

**Java defects in `clearReplicaCaches()`**

1. The old code called `Arrays.fill(cachedInputOpaques, 0L)` on an `OpaqueNDArray[]`, which throws `ArrayStoreException`.
   - A `null` fill would not throw, but it is wrong too. The frozen fast path skips null entries, so the cleared constant is never re-resolved, re-migrated or rebound. The context keeps the closed replica.
2. `externalInputs`, or the retained per-plan inputs, may still name a replica. In that case `closeNativeConstantReplicaCache()` treats it as a protected caller input and drops it without freeing it.

**Native ABA**

- `BaseDataBuffer.close()` frees the native `DataBuffer` synchronously.
- The allocator readily hands the same `DataBuffer*`, `NDArray*` and device address to the next replica.
- The protected-external refresh in `NativeDynamicShapePlan::execute()` diffs `DataBuffer*` pointers, so it cannot tell the successor from the freed buffer:
  - **Full recycle:** the successor looks unchanged, and the plan silently replays captures over an unpinned successor.
  - **Partial recycle:** the plan raises a spurious `LIFECYCLE_ERROR: external input 0 NDArray wrapper identity changed during frozen execution` (proc-173).
- `g_frozenPinCounts` counted pins per buffer only, so one plan could untrack another plan's pin.
- `executeSteadyState()` skips the refresh entirely, so it would replay freed memory.

**Rejected designs**

| Design | Why it was rejected |
|---|---|
| Global liveness only | Cannot see same-address successors. |
| DataBuffer instance id | Header change; costs a near-full libnd4j rebuild. |
| Java deferred close | Holds VRAM, and other paths still free synchronously. |
| Per-execute scan without an epoch | Adds cost to every call on the hot path. |
| Thread-local maps | Buffers are destroyed on other threads. |
| NativeOps counter API | Needs a binding regeneration for no gain. |

## Implemented changes (uncommitted)

### DL4J native: `libnd4j/include/graph/impl/NativeDynamicShapePlan.cpp`

**Frozen-pin registry** (≈L108-194, plus `#include <atomic>` at L68)

- `g_frozenPinCounts` is now `unordered_map<DataBuffer*, unordered_map<const void* /*plan*/, int>>`.
- `trackFrozenPin(plan, db)` counts `[db][plan]`.
- `untrackFrozenPin(plan, db)` returns false when the buffer is gone or when this plan never pinned it.
- `dropDestroyedFrozenPins(plan, pinned, protectedWeightBuffers)` removes this plan's pins on destroyed buffers from both containers and returns how many it removed.
- `notifyFrozenPinTrackerOfDestruction(db)` erases the buffer. If the buffer was tracked, it bumps the global epoch `std::atomic<uint64_t> g_frozenPinDestructions` (release).
  - `~DataBuffer` calls it when `isFrozenPlanRegistered()`, in `array/impl/DataBuffer.cpp` L855-869. That seam was already committed.

**Plan-aware helpers**

- `releasePlanFrozenRefsForTeardown` and `replacePlanFrozenRefsForCurrentState` take `const void* plan`.
- Every call site passes `this`:
  - destructor (L1702)
  - AUTO_SEAL (L4379)
  - phaseWarmup (L6150)
  - phaseReplayCaptureRehome (L7824)
  - releaseGpuIntermediates (L8353)

**Refresh pre-pass in `execute()`** (≈L3136-3165)

- If the epoch has moved past `frozenPinDestructionsSeen_`, the pre-pass runs `dropDestroyedFrozenPins` first and then resyncs.
- A same-address successor then reads as `added`. `destroyed > 0` forces `changed`, so the existing path invalidates the captures (`PROTECTED_EXT_REBIND`) and re-pins the successor.
- `PROTECTED_EXT_REFRESH` now also logs `destroyed=`.

**Steady-state gate** (≈L4610-4622)

- `executeSteadyState()` falls back to `execute()` while `g_frozenPinDestructions != frozenPinDestructionsSeen_`. It logs `[DSP_GATE] FALLBACK execute() — … pinDestroyed=1`.
- This also closes the steady-state use-after-free.
- After a destruction in another plan, a plan pays at most one benign fallback.
- Output-pin vectors are deliberately not scanned.

**`libnd4j/include/graph/NativeDynamicShapePlan.h`**

- L3381-3385: `uint64_t frozenPinDestructionsSeen_ = 0;` and its comment.

**Hunks the previous agent did not write.** The transcript was checked, and no edit in it wrote these. Attribute them before any commit:

- `.cpp` L5737: the `phaseWarmup` failure path, `platformCleanupMigratedInputs()`.
- `.cpp` L6982-6997: the `phaseShapeInferenceOnly` alias publication ("Alias identity is a shape/ownership contract…").
- `.h` ≈L3925 (hunk `@@ -3920,2 +3925,3 @@`): the segment-device binding comment.
- `.h` ≈L4144 (hunk `@@ -4137,0 +4144,8 @@`): the plan-owned per-device segment streams and events.

An earlier summary listed L107-108, L1702, L4379, L6150, L7824 and L8353 as someone else's. That was wrong: they are this change's registry comment and its `this,` arguments.

### DL4J Java: `nd4j/nd4j-backends/nd4j-api-parent/nd4j-api/src/main/java/org/nd4j/autodiff/samediff/execution/DynamicShapePlanExecutor.java`

This is installed in the nd4j-api jar (proc-172, 05:03 JST).

- **L837:** `nativeConstantReplicaCache` widened from private to package-private, for the test-access class.
- **L1580-1603:** a new `unbindNativeConstantReplicas()`. It nulls every `externalInputs[i]` and every `retainedExternalInputsByPlanHandle` entry that is identical to a cached replica.
- **L1610-1630:** `clearReplicaCaches()` now runs these steps in order:
  1. lock
  2. `requireNoNativeBindings`
  3. `invalidateCompletedExecution`
  4. `ensureExecutionDevice`
  5. `unbindNativeConstantReplicas()`
  6. `closeNativeConstantReplicaCache()`
  7. `cachedInputArrays = null; cachedInputOpaques = null; frozenExtInputsWorkingCopy = null;`

  Step 7 forces a full re-resolve, re-migrate and rebind on the next call.

**Hunks the previous agent did not write.** They were already in the diff when this sub-task started, and no edit in the transcript wrote them. Attribute them before any commit:

- ≈L4440-4443: the `try (MemoryWorkspace ignored = Nd4j.getMemoryManager().scopeOutOfWorkspaces())` around `executeNativeLocked`.
- ≈L5803 and ≈L6113: comment rewrites saying that `SameDiff.output()` results belong to the caller.

### DL4J tests

**`platform-tests/src/test/java/org/eclipse/deeplearning4j/nd4j/autodiff/samediff/DspMultiGpuShardingTest.java`** (+194 lines; needs both GPUs)

Three tests:

- `testClearReplicaCachesBetweenPages` (L2404).
- `testClearReplicaCachesBetweenPagesUnderView` (L2413): the bias feeds a reshape view slot.
- `testClearReplicaCachesBetweenPagesSteadyState` (L2423): each page's first and last calls go through the public `executeSteadyState`.

All three run `runClearReplicaCachesBetweenPages(viewOfBias, steadyState)` (L2427):

- It runs 5 pages × `REPLAY_ITERATIONS` calls, with the bias constant on device 1 and every slot on device 0.
- Each page asserts:
  - the output is correct;
  - the page's first call does not increase any segment's replay count ("replayed a capture taken over the freed replica");
  - shapes are frozen;
  - the replica is fresh and is not the previous page's;
  - the native context binds the live replica (checked through `getInputArrayNative`);
  - graph replay is reached;
  - `clearReplicaCaches` frees the replica.
- At the end it calls `assertNoCaptureFailures`.

Helper `executeSteadyState(sd, x, pinDestroyed, label)` (L2562):

- The test enables the EXECUTE diagnostic category, and the helper reads `DspDiagnostics.getJsonReport()`. It requires exactly one `[DSP_GATE]` line per call.
- On pages 1-4, the first call must log `FALLBACK … pinDestroyed=1`. The last call of every page must log `FAST`.
- These gate checks are deterministic: they follow the epoch.
- The replay-count checks only fire when the allocator actually recycles the freed replica's addresses. That happens often, but it is not guaranteed.

**`platform-tests/src/test/java/org/nd4j/autodiff/samediff/execution/DynamicShapePlanExecutorTestAccess.java`**

- 5 new lines: `nativeConstantReplicas(executor)`.

**Also uncommitted, from before this sub-task:** `platform-tests/src/test/java/org/eclipse/deeplearning4j/llm/generation/TestNativeDecodeLoopRegression.java` (+136/−41).

- The previous transcript shows one `edit_batch` on it, at 04:18 JST, just before proc-166. It shows no other DL4J edit on 10-01 or 10-02 before that run.
- proc-166's label calls it an "NDArray* identity fix". That is most likely the test's own wrapper-identity check, because neither the executor nor `OpaqueNDArray.java` has a matching uncommitted hunk. This is an inference, not verified.
- Attribute the whole diff before any commit. Other sessions may have edited the file too.

### Kompile

This sub-task changed nothing in Kompile.

The parent's output-budget-floor fix in `DirectLlmClient` (a small `--max-output-tokens` is no longer raised to 1024) is committed in `10fa36ee8`.

- Another session's redeploy rebuilt `~/.kompile/lib/kompile-cli.jar` at 2026-10-02 04:42 JST, after that commit, so the fix should be live.
- Confirm with the fake-serving capture (`/tmp/kompile-vlm-test/fake_serving.py`): `--max-output-tokens 512` must arrive as 512.

## Build and installed state (verified 06:17-06:40 JST)

**Superseded; re-verified at 10:24 JST.**

- The continuation session rebuilt libnd4j CUDA at 08:00 and installed it at 08:05. That build adds the in-place alias deferral, the capacity-shift CUDA 400 fix and the VERIFY-probe stream fix.
- It also reinstalled nd4j-api with Gap A, Gap B and `migrateConstantReplica`.
- `libnd4jcuda.so` is now md5 `ff725d2d0496f3558ab8c3aac3986efa` in all three copies.
- The nd4j-api jar is md5 `4b67a970d9c28164a8c71d25f7ae5867`.

The original record follows.

- proc-176 built and installed libnd4j CUDA between 05:38 and 05:46 JST.
- Three copies of the library have md5 `6d6ce86d226cc71a713aeb00f069abd2`:
  - `libnd4j/blasbuild/cuda/libnd4jcuda.so`
  - the copy in the installed nd4j-cuda-12.9 jar
  - the shared JavaCPP cache copy
- If a peer rebuilds libnd4j, these md5s change. Then:
  - Update `SHARED_MD5` in `run-gate.sh`, or it aborts with "SHARED CACHE CHANGED".
  - Re-run S0, because the mutants link against whatever objects `blasbuild/cuda` then holds.
- No shadow files are left behind:
  - `platform-tests/target/test-classes/org/nd4j/linalg/` holds only `devices`.
  - `…/org/nd4j/autodiff/samediff/execution/` holds only the two real test classes.
- This session holds no edit locks. Every lock that `edit_coordinator query_edits` lists belongs to session `067b9081-7891-4cf0-bc7f-ccc69c61c821`: Hexagon publication files under `/tmp/dl4j-hexagon-publication/` and `nd4j-hexagon*`, plus CI and release files. None of them covers the DSP executor or `NativeDynamicShapePlan.*`. Leave them alone.
- The scratch directories `/tmp/native-mutants/S0`, `S1` and `javacpp-cache` are deleted.

## Evidence so far

Logs are at `/home/agibsonccc/Documents/GitHub/kompile/.kompile/process-output/c9f48d8c-c6b8-487f-80e8-c4b486f46f14/proc-NNN.log`.

| proc | JST | What ran | Result |
|---|---|---|---|
| 166 | 04:20 | Baseline before both fixes: `TestNativeDecodeLoopRegression#testOpaqueContextInputFreshness` (run label "CUDA P1 freshness test with NDArray* identity fix") | 1/1 pass. Not post-fix evidence; rerun it in Step 3 |
| 167 | 04:26 | Baseline mutation check of that test: `run-p1.sh 0 1` | M0 passes. M1 (a fresh native wrapper over the same buffers) fails with `P1 (first generate): 601 context inputs are not the arrays' current wrappers ==> expected: <0> but was: <601>`. 0 shadow files left |
| 172 | 05:03 | nd4j-api install with the Java fix | BUILD SUCCESS |
| 173 | 05:05 | 2 tests, before the native fix | 1 error: `LIFECYCLE_ERROR … NDArray wrapper identity changed` (partial recycle) |
| 175 | 05:30 | strengthened tests, before the native fix | Both fail: `page 2: segment 0 replayed a capture taken over the freed replica (9 -> 10 replays)` |
| 176 | 05:46 | libnd4j CUDA build and install with the native fix | BUILD SUCCESS |
| 177 | 05:51 | the 2 page tests | 2/2 pass |
| 178 | 06:00 | all 3 tests | 3/3 pass. FAST at executeCount 9/19/29/39/49; FALLBACK `pinDestroyed=1` at 10/20/30/40 |
| 179 | 06:15 | `run-gate.sh` preflight | 3/3 pass; a harness maps-regex bug wrongly reported PREFLIGHT_FAILED (since fixed) |
| 180 | 06:17 | `run-gate.sh P 0 1 2` | Preflight OK, see below. **S1 BUILD_FAILED** (harness bug, see Step 1) |

proc-180 detail:

- Preflight: the private cache was proven loaded and the shared cache was intact.
- The 3 tests passed, with `PROTECTED_EXT_REBIND` at execute 10/20/30/40.
- S0 compiled in 6 s and linked in 6 s. Its object's `.text` size matched the build's object: 290391 bytes each (proc-180.log L43). That is a size check, not a byte comparison.
- S1 then reported BUILD_FAILED because of the harness bug fixed in Step 1.

## Step 1: Native mutation check (`/tmp/native-mutants/run-gate.sh`)

How the harness works:

- **Build:** each mutant reuses every `blasbuild/cuda` object except `NativeDynamicShapePlan.cpp.o`.
  - It compiles the mutated copy with the build's own command from `compile_commands.json`, with `-o` and `-c` redirected.
  - It links with `CMakeFiles/nd4jcuda.dir/link.txt` through a private `objects1.rsp` that swaps in that one object.
- **Shadow and run:** it symlinks the library at `platform-tests/target/test-classes/org/nd4j/linalg/jcublas/bindings/linux-x86_64/libnd4jcuda.so`, then runs the tests with `-Dorg.bytedeco.javacpp.cachedir=/tmp/native-mutants/javacpp-cache`.
- **Checks:** it polls `/proc/<fork>/maps` to prove which library loaded, and checks the shared-cache md5 after every run.
- **Cleanup:** it removes the shadow on exit.

| Mode | Change | Expected |
|---|---|---|
| `P` | preflight, no shadow; the private cache must reach the fork | 3/3 pass |
| `0` | control: the unmodified source | 3/3 pass |
| `1` | the gate loses `\|\| pinDestroyed` | only `…SteadyState` fails, at page 1 call 0: `… follows the freed replica and must fall back to execute(): [[DSP_GATE] FAST …]` |
| `2` | the pre-pass is disabled (`if (false && pinDestructions != frozenPinDestructionsSeen_)`) | all 3 fail; see the notes below the table |

Mode 2 notes:

- The page tests fail with `replayed a capture taken over the freed replica` or a `LIFECYCLE_ERROR`. Both depend on recycling: if a page test survives, check whether any page recycled the replica's addresses before calling it a surviving mutant.
- `…SteadyState` fails with `must take the native FAST path`, because `frozenPinDestructionsSeen_` never resyncs. Which assertion fires first depends on test order.

**First fix the harness bug.** `mutate()` ends with `diff -- "$SRC" "$3"` at L88. `diff` exits 1 when the files differ, so `build` reports `S1 BUILD_FAILED`. Change L88 to:

```bash
  diff -- "$SRC" "$3"; return 0
```

**Then run it through the kompile `process` tool.** Run nothing else meanwhile, and check that no other platform-tests or root fork is running:

```bash
bash /tmp/native-mutants/run-gate.sh P 0 1 2 2>&1 | tee /tmp/native-mutants/run.log
```

- Expect about 1 minute per test run, plus about 12 s per mutant build.
- Each mutant must print `S<n> PROVEN LOADED`.
- Each run must be followed by `shared cache intact`.
- The whole run must end with `=== shadow left behind: 0 ===`.
- Check each failure message against the table, not just the exit code. A mutant that dies some other way (a crash or a CUDA error) is a finding, not a kill.

## Step 2: Java mutation check (`/tmp/owner-mutants/run-crc.sh`)

The script is prepared but has never been run.

- It copies the mutated `DynamicShapePlanExecutor*.class` files into `platform-tests/target/test-classes/org/nd4j/autodiff/samediff/execution/`. Test classes come before jars on the classpath, so these shadow the installed executor.
- It deletes only the files it copied.

```bash
bash /tmp/owner-mutants/run-crc.sh 0 1 2 2>&1 | tee /tmp/owner-mutants/crc-run.log
```

| Mutant | Change | Expected |
|---|---|---|
| `M0` | control | 3/3 pass |
| `M1` | `Arrays.fill(…, null)` on the caches instead of dropping them; `frozenExtInputsWorkingCopy` is kept | fails on page 1 (see below) |
| `M2` | the `unbindNativeConstantReplicas()` call is removed | fails on page 0: `clearReplicaCaches must free the replica` |

M1 detail:

- With no re-migration, the most likely failure is `page 1 must migrate the device-1 bias`. The output check or `the native context must bind the live replica` may fail first instead.
- If the fork crashes, it is a native use-after-free through the stale binding. Read the `hs_err` before counting it as a kill.

Other notes:

- The run must end with `=== shadow left behind: 0 executor class files ===`.
- `/tmp/owner-mutants/nd4j-api.cp` dates from 2026-10-01 21:30. If javac reports a missing class, regenerate it from the deeplearning4j root:

  ```bash
  mvn -o -q -pl :nd4j-api dependency:build-classpath -Dmdep.outputFile=/tmp/owner-mutants/nd4j-api.cp
  ```

- This harness does not shadow native libraries, so the shared JavaCPP cache is not at risk.

## Step 3: Regression sweep (from `platform-tests`, one at a time)

```bash
cd /home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests
set -o pipefail
/home/agibsonccc/dev-apps/mvn/bin/mvn -o -B -ntp test -Dtest='<selection>' \
  -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee /tmp/dsp-rebind-<name>.log
```

1. `DspMultiGpuShardingTest`: the whole class.
2. `DspTeardownOrderTest`: its cross-plan cases (≈L123-146) and free-before-close case (≈L213-219) exercise the per-plan registry directly.
3. `TestFrozenPhaseDriftDetection`.
4. `DspExtInputStalenessTest`: it also calls `executeSteadyState`.
5. `DspMultiPlanShapeSwitchTest#testStableAddressMultiPlanSteadyStatePromotion`. The epoch is global, so one destruction costs every plan one fallback. This test asserts promotion to FAST; watch it.
6. `SteadyStatePlanApiCoverageTest` and `DspExtInputDeviceStreamTest`: the other `executeSteadyState` callers.
7. `TestNativeDecodeLoopRegression#testOpaqueContextInputFreshness`: the earlier freshness fix in the same executor. Its mutation harness is `/tmp/owner-mutants/run-p1.sh`. proc-166 and proc-167 are its pre-fix baseline. The executor still has the 7 bind sites `run-p1.sh` rewrites (checked 06:55 JST), so the harness applies unchanged.
8. `WorkspaceInferenceSessionTest`: the whole class, 76 tests. Only 2 have been re-run since the proc-139 failure. This is a parent item.

`TestGgufMtpCapturedReplay` and `TestQwenMtpPredictorLifecycle` also call `executeSteadyState`, but their model and GGUF reference are not cached on this machine. A skip is not a pass; say so in the report.

## Step 4: Two known gaps (fixed 10-02)

### A. The public `executeSteadyState` runs without DSP routing suppression

- The ordinary path, executor L3949-3955, wraps `executeNative(plan, placeholders)` in `DeviceAwareOpExecutioner.setDspRoutingSuppressed(true)`, with `false` in a `finally`.
  - The comment there gives the reason: a DeviceAware-triggered migration of a plan-internal buffer fights the plan's allocator. That was seen as `MIGRATION_ADMISSION_REJECT` → "copy failed: invalid argument" during Gemma serving prefill.
- The public `executeSteadyState` (L4420) calls the private `executeNative(plan, placeholders, true)` (L4425) without suppression.
- Today only tests call it. Neither repository has a production caller.
- `isDspRoutingSuppressed()` is the ThreadLocal OR the native `dspIsOwned()`.

**Fix:**

1. Move the set and restore into the private three-argument `executeNative`, around `executeNativeLocked`.
2. Restore the previous ThreadLocal value instead of `false`, so nested calls stay correct. Check whether a ThreadLocal-only getter exists.
3. Add a test that fails without the fix.
4. Reinstall nd4j-api and rerun Step 3.

**Resolution:**

- Every native entry now goes through the private `executeNative(plan, ph, steadyState)`. That covers `execute`, the public `executeSteadyState` and the test access.
- It saves `isDspRoutingSuppressionMarked()`, the ThreadLocal-only getter. It sets suppression under `nativeExecLock` and restores the caller's value in the `finally`.
- `DspRoutingSuppressionScopeTest` covers 3 entries × 2 caller markers. On the old jar it failed 3/3 (proc-013); after the fix it passes 3/3 (proc-015 and proc-018).

### B. Replicas are orphaned on the other close paths

- `closeNativeConstantReplicaCache()` also runs in three places without calling `unbindNativeConstantReplicas()` first:
  - PLAN_CHANGED (~L985)
  - executor teardown (~L6389)
  - `close()` (~L6412)
- In each place, `externalInputs` still names the replicas. The close treats them as protected caller inputs and drops them unfreed, so they are left to GC. Parked plans' `retainedExternalInputsByPlanHandle` entries keep their VRAM alive.
- This was not fixed because the fix interacts with:
  - parked-plan lifetimes: a parked plan's context may still bind the replica;
  - native view slots minted over a replica: the UnderView case.
- It needs a design, for example closing only replicas that no live or parked plan retains, or refcounting replicas per plan handle. Each close path also needs a test.

**Resolution:**

- **Unbind before every close.** `unbindNativeConstantReplicas()` now runs before every `closeNativeConstantReplicaCache()`: on PLAN_CHANGED, at the `freeNativePlanHandle` tail and in `close()`.
- **Parked plans keep their own lease.** A parked prefill plan takes an independent lease (`retainNativePlan`) and returns the executor's dispatch lease (`unpinNativePlan`).
  - A displaced or evicted record releases its lease through `releaseRetainedFrozenPlan`.
  - A restore adopts the parked lease.
  - A redispatch returns the duplicate dispatch lease when the handle is already pinned. Before this, park plus restore leaked about 2 leases per cycle.
  - `close()` releases the parked leases before `EXECUTOR_CLOSE`.
- **Freed replica under a resumed plan.** The native pre-pass handles a parked plan whose replica was freed: `destroyed=1` → `PROTECTED_EXT_REBIND`.
- **Tests:** `DspConstantReplicaCloseTest` has 8 cases over 6 routes.
  - On the old jar it failed 8/8 (proc-016), with the predicted leaked-replica lists and `[1, 1]` leases outstanding on the parked routes.
  - After the fix it passes 8/8 (proc-017).
  - With full diagnostics (proc-018 `rcd`) it shows `PROTECTED_EXT_REBIND`=4 and `leasedRemaining=0` at every cache clear.
- **Rejected for now:** a shared, refcounted replica pool. That is open item 9 in the memory note.

## Step 5: Memory

Pointers were added before handoff:

- a line in `index_dsp.md`
- a 10-02 line in `project_multimodal_chat_audit_2026_09_30.md`
- the JavaCPP cache hazard in `reference_mutation_check_shared_tree.md`

After validation, write `reference_dsp_constant_replica_rebind.md`. Cover the mechanism, the fix, the code anchors, the diagnostic lines, the mutant results and the state of the two gaps. Link it from `index_dsp.md` and update the descriptions in `MEMORY.md`.

**Done (10-02).** The note is written and linked from `index_dsp.md`. The `MEMORY.md` entries and the multimodal note's 10-02 line are updated.

## JavaCPP cache hazard (read before shadowing any native library)

How JavaCPP 1.5.13 loads native libraries:

- `target/test-classes` comes before the jars on the surefire classpath.
- A `file:` resource loads in place.
- A jar resource is extracted into the cache dir.
- JavaCPP also symlinks every other library it has already loaded into the cache dir it extracts to, replacing any regular file of the same name.

The danger: a run that shadows `libnd4jcuda.so` while using the shared `~/.javacpp/cache` replaces that cache's `libnd4jcuda.so` with a link to the mutant. That poisons every later JVM on the box.

To stay safe:

- Always pass `-Dorg.bytedeco.javacpp.cachedir=<private dir>` on the Maven command line. It does reach the surefire fork; proc-179 and proc-180 confirmed this through `/proc/<pid>/maps`.
- Check the md5 of the shared copy after every run.

## Back to the parent goal

### 1. SmolVLM-Instruct ~2B live acceptance

Run it through both the CLI and the web chat, with DSP and the optimizer on.

- The SmolDocling live command and the debug kit are described in `project_multimodal_chat_audit_2026_09_30.md`. The kit lives in `/tmp/kompile-vlm-test/`; its `sample.sh` caps RSS.
- **Redeploy first.** The installed jars predate this sub-task's DL4J installs (nd4j-api 05:03 and libnd4j 05:46 JST on 10-02):
  - `~/.kompile/lib/kompile-cli.jar` and its sibling CLIs: 10-02 04:42.
  - `kompile-model-serving.jar` and `kompile-pipeline-serving.jar`: 10-01 19:44.
  - `kompile-chat.jar`: 10-01 11:30.

  Check that no peer build is running, then run `./redeploy.sh --all` (cli + chat + serving) from the kompile root. The serving exec jar nests the ND4J backend under `BOOT-INF/lib`; there is no `~/.kompile/lib/backends`. During the live run, use `/proc/<serving pid>/maps` to see which `libnd4jcuda.so` the serving JVM mapped, and compare its md5 with `ff725d2d0496f3558ab8c3aac3986efa`, unless a peer has rebuilt libnd4j since. That is the 08:00 build; `6d6ce86d…` is superseded.
- **History.** Turn 1 first ran at 14:47 JST on 10-01 and died of a heap OOM during ONNX external-data import. A mmap fix was installed at 14:53 JST. After the fix, session `ce72e62c-f611-4e27-a8ce-bcdc947ff292` ran turn 1 twice. Both runs hit the `sample.sh` guard's 40 GiB RSS cap (argument `40`), and the guard killed them:
  - **t1b**, started 15:07 JST: killed at t=457 s with `rssMB=41724`.
    - Serving log: `~/.kompile/logs/subprocesses/model-serving/8202c305-824b-436b-a586-9cc6d48a029d.log`.
    - Guard log: `/tmp/kompile-vlm-test/smolvlm-t1b-rss.log`.
  - **t1c**, started 15:56 JST: killed at t=196 s with `rssMB=41727` (anon 20707, file 15752, shmem 5267 MiB).
    - Serving log: `…/model-serving/ab204755-4ed4-4b0c-898c-c49e812b743c.log`.
    - Guard log: `smolvlm-t1c-rss.log`, with the `eu-stack` dumps beside it.
    - CLI output: `/home/agibsonccc/Documents/GitHub/kompile/.kompile/process-output/c9f48d8c-c6b8-487f-80e8-c4b486f46f14/proc-117.log`.
- **What that session found.** The source is its compaction summaries after 16:00 JST on 10-01. It labelled the RSS causes A–H, then later reused "(A)" and "(G)" for unrelated fixes, so go by the descriptions, not the letters.
  - **Fixed:**
    - the FIRST_LOOP workspace;
    - a double detach;
    - the BaseNDArray `to*Vector` view-dup leak (~197 KB per token);
    - session teardown closing the caller-owned arrays that `SameDiff.output()` returns (green in that session's proc-127).
  - **Open:**
    - A soft limit hardcoded at 80, where the managed default is 70. Route the hardcoded watchdog thresholds through `SubprocessConfigService`. They are in `KompileLocalServingBootstrap.writeServingArgs` (from L792) and `managedNd4jConfigJson` (from L878), both in kompile-cli-main, and in `ServingSubprocessLauncher:445-472` (kompile-app-main; that line range comes from that session).
    - The prefill plan footprint. Release the prefill DSP intermediates.
    - Full-sequence prefill logits. Compute only the last position.
    - Prompt padding from 3678 to 4608 tokens.
    - The failover log flood. Rate-limit it.
  - **Found later, unresolved:**
    - glibc arena retention: `malloc_trim` returned 246 MB after warm-up. A deallocation backlog amplifies it (the thread-0 `autoGcWindow` sleep, and the ArrayDeallocator → buffer chain).
    - About 60–90 KB of host RSS per outstanding small CUDA buffer. The cause is unidentified.
    - The javaWs creep.
    - An InteropDataBuffer wrapper leak.
    - Constant OpaqueNDArray wrappers.
    - A per-thread CUDA TLS leak: streams and cuBLAS/cuBLASLt handles at thread exit.
- **Acceptance (shared todo #7).**
  1. CLI turn 1 with the image.
  2. Turn 2 with `--resume`, without re-attaching the image.
  3. The web route.

  Verify `ZEBRA-7319`, EOS, decoder DSP replay and peak RSS and GPU memory, and check that no `/tmp/samediff_dup_*` files are left behind.
- **Never launch a serving child without the guard.** Start the guard first, then the turn, both through the kompile `process` tool. These are t1c's commands with the output files renamed to t1d. Give the turn a fresh `--session-id` UUID.

  ```bash
  TEST_DIR=/tmp/kompile-vlm-test/smolvlm-chat STACK_MARKS_GB="16 28" GPU_STACK_MARKS_MB="20000" DIAG_FILE=/tmp/kompile-vlm-test/dsp-diag-t1d.log DIAG_CAP_MB=3000 bash /tmp/kompile-vlm-test/sample.sh /tmp/kompile-vlm-test/smolvlm-t1d-rss.log 40 300 3000
  ```

  ```bash
  cd /tmp/kompile-vlm-test/smolvlm-chat && JAVA_TOOL_OPTIONS="-Xmx2g -Dorg.bytedeco.javacpp.maxbytes=32G -Dorg.bytedeco.javacpp.maxphysicalbytes=38G -Dkompile.model.serving.heap=8g '-Dkompile.nd4j.config.json={\"optimizerEnabled\":true,\"optimizerFp16\":false,\"maxThreads\":4,\"maxMasterThreads\":4,\"openBlasThreads\":1,\"ompNumThreads\":4}' -Dnd4j.dsp.diagnostics=MEMORY,COLORING,MULTI_DEVICE -Dnd4j.dsp.diagnostics.level=full -Dnd4j.dsp.diagnostics.file=/tmp/kompile-vlm-test/dsp-diag-t1d.log" /home/agibsonccc/.kompile/bin/kompile chat --local --provider kompile-local --model /tmp/kompile-vlm-test/smolvlm-instruct --session-id <fresh-uuid> --attachment /tmp/kompile-vlm-test/report.png --working-dir /tmp/kompile-vlm-test/smolvlm-chat --max-output-tokens 512 --no-rag --no-memory --timeout 1500 --startup-timeout 1500 'What is the code in this report?'
  ```

  `-Dnd4j.dsp.diagnostics.level=full` also turns on the slow NODE_AUDIT pass (see `reference_node_audit_query_fail_triton.md`). t1b ran with only that diagnostics flag.
- **Old crash dumps.** `/tmp/kompile-vlm-test/hs_err_pid1361314.log` (09-30 18:21) and `hs_err_pid1372713.log` (18:25) are SIGSEGVs in `instantiateAndStoreMergedCapture`. They are the known NODE_AUDIT driver re-read crash:
  - serving logs `8b1b52b7-23fe-4a95-acc4-1eda1da2ed97.log` (L15841) and `ff83f6f5-0411-409e-aefd-4a4eb27a9970.log` (L15840) print their paths;
  - `reference_node_audit_query_fail_triton.md` records the fix (09-30), which todo #11 says was installed at 19:05 that day.

  Treat any new `hs_err` as a new crash.

### 2. Open audit gaps

- Server mode drops attachments (`HeadlessAgentRunner:493`).
- The CHAT_MODEL pipeline has its own image table and gate.
- Local serving cannot decode WebP.
- Serving caps a request at 1 MiB. The plan is `MAX_INLINE_IMAGE_BYTES` = 5 MiB.
- There is no capability-driven prompt policy. A local VLM image turn still carries the ~4k-token CLI system prompt and tools.
- The Radius gateway's text-only check uses its own catalog, not `ModelContextWindows`, so images in Radius/PI history can reach text-only models.
- The summarizer drops attachment mentions.
- The `.attachments` directories are never garbage-collected.

The full lists are the "New follow-ups" and "Open gaps" lines of `project_multimodal_chat_audit_2026_09_30.md`.

## Where things are

- **Previous transcript:** `~/.claude/projects/-home-agibsonccc-Documents-GitHub-kompile/828420ec-45aa-4ffd-a073-d1b7309913dd.jsonl`. It contains more than 20 compaction summaries; the last few cover this sub-task.
  - `grep` on this machine is ugrep, which rejects long bounded repeats. Use jq `match()` to search the transcript.
- **Process logs:** `/home/agibsonccc/Documents/GitHub/kompile/.kompile/process-output/c9f48d8c-c6b8-487f-80e8-c4b486f46f14/proc-NNN.log`.
- **Tool results:** `~/.kompile/conversations/c9f48d8c-c6b8-487f-80e8-c4b486f46f14/tool-results/`.
- **SmolVLM RSS analysis (another session):** `~/.claude/projects/-home-agibsonccc-Documents-GitHub-kompile/ce72e62c-f611-4e27-a8ce-bcdc947ff292.jsonl`. See its compaction summaries after 16:00 JST on 10-01.
- **Native mutation harness:** `/tmp/native-mutants/run-gate.sh`, with the last preflight's `P-report.xml`, `P-mvn.log` and `P-maps.txt` next to it.
- **Java mutation harnesses:** `/tmp/owner-mutants/run-crc.sh`, `run-p1.sh` and `run-ctx.sh`.
