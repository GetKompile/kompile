# Qwen ModelToCrawl DSP / `gated_delta_rule` NaN regression handoff

**Date:** 2026-08-28  
**Repositories:**
- Kompile: `/home/agibsonccc/Documents/GitHub/kompile`
- DL4J: `/home/agibsonccc/Documents/GitHub/deeplearning4j`

## Repository handling constraints

- Work only in the existing checkout and branch. **Do not create a worktree.**
- Do not use Git reset, checkout, restore, stash, clean, or other Git mutations. The user requested manual, minimal edits only.
- Do not run Maven `clean`; preserving `libnd4j/blasbuild` is required for iterative Triton builds.
- Serialize native builds and GPU tests. Do not overlap this reproduction with another native build or GPU workload.
- Do not broadly kill processes. If a process must be stopped, identify the exact process started by this investigation.

## Executive summary

A recent, broad DSP segment shape-key change was confirmed to cause deterministic recompilation churn: it hashed every small integral external tensor used by a value-dependent segment, including unrelated token/payload values. That `VALUECTR` behavior has been manually backed out. After rebuilding:

1. The focused native decoder replay test passes and reaches `REPLAYING` with no compile/recompile violation.
2. The exact Qwen ModelToCrawl integration test still fails on its **second model request**.
3. The second request is a long prefill (`actual_sequence_length=1014`). Every reported input to the first-layer `gated_delta_rule` is finite, but slot 460 produces 1,984 NaNs. Final logits are all NaN before constraint masking.
4. The failure strongly correlates with DSP/CUDA pool pressure: free VRAM falls from 8,218 MiB before request 2 to 52 MiB after it, while pool reservation rises from 14,944 MiB to 23,104 MiB.
5. A later internal retry produces the correct entities and relations, but the crawl correctly retains the earlier model failure and the Maven test fails. Retrying or swallowing the first error is not an acceptable fix.

The remaining issue is therefore **not the already-removed broad `VALUECTR` recompilation churn**. The leading hypothesis is shape-specific DSP plan/intermediate retention across consecutive requests, but this is not yet proven. Numerical instability at sequence length 1014, stale recurrent state, or bad argument/pointer reuse remain possible.

## Current intentional DSP source state

The only DSP source change intentionally retained from this recovery is in:

`deeplearning4j/libnd4j/include/graph/impl/NativeDynamicShapePlan_segments.cpp`

The manual rollback:

- removed the segment-wide `VALUECTR` block that scanned and hashed all small integral external inputs;
- restored cached/symbolic shape-key reuse for value-dependent segments when there is no dynamic boundary input;
- keeps segment identity shape/layout based, while value-dependent slots validate bounded controls on the live slot path.

An experimental change in `NativeDynamicShapePlan_slotexec.cpp` that called `forceSyncToHost()` for internal controls was **manually removed**. It made a synthetic churn case pass but broke the existing mutable-scalar slice regression:

```text
DspExtInputDecoderPatternTest.testFrozenMultiPlanMutableScalarSliceRefresh
TRITON: stale actual_sequence_length at prefill generation 3
expected: 248.0, actual: 264.0
```

Do not reintroduce that experiment. The synthetic test added during that attempt was also removed.

## Historical correlation

Forensic reconstruction found these anchors:

- `f74e8a4eca`: last exhaustive DSP known-good result, 755/755 tests and 32.2 tok/s.
- `9240cf7039`: known-good Aug-15 Qwen snapshot.
- Build stamp `2026-08-24T06:54:22Z`: the same Qwen graph and 49 requested outputs completed successfully.
- Build stamp `2026-08-25T02:40:44Z`: the same 1,854-slot strict-Triton graph failed around a later `gated_delta_rule` path (`gate_2_accum`, around slot 605).
- `c67c24b03a` falls between those build stamps, but later runs with the same generic-plan source completed the call. It is correlated, not proven causal.
- `24745571cd` introduced the broad segment external-integral hashing (`VALUECTR`) on Aug 26. That behavior was independently proven to cause per-token shape-key churn and is the code manually backed out now.

The current failure is earlier in the same recurrent-op family—first-layer `gated_delta_rule`, slot 460—but occurs specifically on request 2 under very high pool reservation.

## Build used for the reproduction

From the DL4J repository root, with no concurrent GPU/native job:

```bash
cd /home/agibsonccc/Documents/GitHub/deeplearning4j

CMAKE_ARGUMENTS="-DCMAKE_C_COMPILER_LAUNCHER:FILEPATH=/home/agibsonccc/miniconda3/bin/ccache -DCMAKE_CXX_COMPILER_LAUNCHER:FILEPATH=/home/agibsonccc/miniconda3/bin/ccache -DSD_EXPECTED_COMPILER_CACHE:FILEPATH=/home/agibsonccc/miniconda3/bin/ccache" \
SD_REQUIRE_COMPILER_CACHE=ON \
DL4J_COMPILER_CACHE=/home/agibsonccc/miniconda3/bin/ccache \
/home/agibsonccc/dev-apps/mvn/bin/mvn \
  -Pcuda \
  -Dlibnd4j.triton=ON \
  -Dlibnd4j.chip=cuda \
  -Dlibnd4j.buildthreads=12 \
  -Dlibnd4j.log=libnd4j-build.log \
  -Dassembly.skipAssembly=true \
  -Dlibnd4j.cuda.assembly.skip=true \
  -pl libnd4j,:nd4j-cuda-backend-common,:nd4j-cuda-12.9 \
  install -DskipTests
```

Observed result:

```text
libnd4j                    SUCCESS
nd4j-cuda-backend-common   SUCCESS
nd4j-cuda-12.9             SUCCESS
BUILD SUCCESS
Total time: 5m13s
```

Build log: `/tmp/dl4j-minimal-rollback-cuda-build.log`

## Focused native replay control — passes

```bash
cd /home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests

/home/agibsonccc/dev-apps/mvn/bin/mvn --batch-mode --no-transfer-progress test \
  -Dtest=TestNativeDecodeLoopRegression#testReplayStateAfterNativeDecode \
  -Dbackend.artifactId=nd4j-cuda-12.9 \
  -Dlibnd4j.triton=ON \
  -Dnd4j.dsp.diagnostics=EXECUTE,SEGMENT,COMPILE,GRAPH_REPLAY,FALLBACK,MEMORY \
  -Dnd4j.dsp.diagnostics.level=full \
  -Dnd4j.dsp.diagnostics.file=/tmp/dsp-real-decoder-replay-minimal-rollback.json
```

Observed result:

- plan phase: `REPLAYING`;
- pointers stable;
- total graph replays: 7;
- segment internal replay count: 726;
- `needsArgRefresh=0`;
- tests: 1 run, 0 failures, 0 errors;
- no `COMPILE_VIOLATION`, `RECOMPILE_TRIGGERED`, phase violation, illegal access, or OOM.

Logs:

- `/tmp/dsp-real-decoder-replay-minimal-rollback.log`
- `/tmp/dsp-real-decoder-replay-minimal-rollback.json`

This control is important: any proposed fix must preserve it.

## Exact ModelToCrawl reproduction — fails

Use the already-built CUDA artifacts. Do not overlap this with another GPU process.

```bash
cd /home/agibsonccc/Documents/GitHub/kompile

JAVA_HOME=/home/agibsonccc/.sdkman/candidates/java/17.0.12-graal \
/home/agibsonccc/dev-apps/mvn/bin/mvn --batch-mode --no-transfer-progress \
  -f /home/agibsonccc/Documents/GitHub/kompile/kompile-e2e-tests/pom.xml verify \
  -Dkompile.backend=cuda-12.9 \
  -Dtest=NoUnitTestsForThisInvocation \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ModelToCrawlJvmIT#parentStartsModelCrawlsThroughItAndStopsIt \
  -Dkompile.model.runtime.it.modelId=qwen3.5-2b-instruct \
  -Dkompile.model.runtime.it.model=/home/agibsonccc/.cache/dl4j-llm-models/Qwen3.5-2B-Q4_K_M.gguf \
  -Dkompile.model.runtime.it.tokenizer=/home/agibsonccc/.cache/dl4j-llm-models/qwen35-2B-tokenizer.json \
  -Dkompile.model.runtime.it.maxTokens=768 \
  -Dkompile.model.runtime.it.enableThinking=true \
  -Dkompile.model.runtime.it.maxOutputBlockTokens=256 \
  -Dkompile.model.runtime.it.structuredOutputTokenReserve=384 \
  -Dkompile.model.runtime.it.doSample=false \
  -Dkompile.model.runtime.it.temperature=0.0 \
  -Dkompile.model.runtime.it.topK=1 \
  -Dkompile.model.runtime.it.topP=1.0 \
  -Dkompile.model.runtime.it.presencePenalty=0.0 \
  -Dlicense.skip=true
```

The reproduced run took 4m48s and failed one integration test.

Full log: `/tmp/model-to-crawl-minimal-rollback.log`

### Request sequence and memory

| Point | Free VRAM | Pool used | Pool reserved | DSP plan |
|---|---:|---:|---:|---|
| Before call 1 | 16,538 MiB | 5,584 MiB | 6,784 MiB | none |
| After call 1 / before call 2 | 8,218 MiB | 13,670 MiB | 14,944 MiB | 1,848 slots, 1 segment, 345 steps |
| After failed call 2 | **52 MiB** | **23,059 MiB** | **23,104 MiB** | 1,854 slots, 1 segment, 1 step |
| After later call 3 | 4,812 MiB | 17,423 MiB | 18,336 MiB | memory partially recovered |

Call behavior:

1. Call 1 correctly emits `submit_typed_entities`:
   - Alex Rivera / PERSON
   - Morgan Chen / PERSON
   - Acme Robotics / COMPANY
   - Nova Labs / COMPANY
2. Call 2 starts the relation phase and fails before generating any token.
3. Call 3 retries entities successfully after pool usage falls.
4. Call 4 emits the correct relations:
   - `0 -> 2 WORKS_AT`
   - `1 -> 3 WORKS_AT`
5. Maven still fails, correctly, because call 2's model error remains attached to the crawl job.

### Exact native failure

```text
Model produced no finite logits before constraint masking
constraint=OutputBlockSequenceConstraint
generatedTokens=0
finite=0
nan=248320
firstNonFiniteSlot=460
firstNonFiniteOp=gated_delta_rule
actual_sequence_length=1014
```

The first non-finite output is:

```text
gdn_out_0 / gdn_state_out_0
shape=[1, 1014, 16, 128]
finite=2,074,688
nan=1,984
min=-7.1309636E13
max=2.14344021E12
```

All reported inputs to slot 460 are finite:

- `gdn_q_scaled_0`
- `gdn_k_compute_0`
- `gdn_v_compute_0`
- `gdn_beta_compute_0`
- `gdn_gate_decay_0`
- `past_gdn_state.0` (all zero, finite)
- `actual_sequence_length=1014`

The stack enters the failure from prefill:

```text
GenerationPipeline.sampleToken
GenerationPipeline.prefillWarmupAndFreeze
GenerationPipeline.generateSimpleWithInGraphKvCache
```

There is no explicit CUDA OOM exception in the log. The 52 MiB free-memory state is a strong correlation, not proof that allocation pressure causes the NaNs.

## Leading hypotheses, ordered by current evidence

> **2026-08-28 elimination update (session 2).** A systematic isolation campaign has
> **eliminated** the following hypotheses. All results below are from instrumented runs
> (`[DSP-TRACE]` pool probes at boundary/prefill/warmup/decode-loop granularity):
>
> 1. **Memory pressure does NOT cause the NaN.** Phase probes show the failing call 2
>    reaches `prefill-done` at the *same* pool reservation (20,544 MB) as the passing runs;
>    the +8 GB extra reservation appears only AFTER the abort (the aborted prefill plan is
>    retained until the next boundary clear). Failures happen at identical memory as passes.
>    (`/tmp/fix-v8-run1.log` PASS vs `/tmp/fix-v8-run2.log` FAIL — identical prefill-done
>    numbers, run 2 aborts between prefill-done and warmup-done.)
> 2. **The boundary clear (v3 fix) WORKS.** Per-boundary post-clear returns to
>    baseline+~0.9 GB; `PLAN_CACHE_CLEAR deleting=2 leasedRemaining=0` confirmed in
>    MEMORY diagnostics. The "stacked 23 GB" signature is a *symptom* of the aborted call
>    (its plan survives until the next boundary), not the cause.
> 3. **CUDA graph capture/replay is NOT the mechanism.** Two chained runs with
>    `-Dnd4j.dsp.cudaGraphs.enabled=false`: run 1 still NaN'd (identical lineage:
>    `past_gdn_state.0` all zeros, `actual_sequence_length=1014`, all GDR inputs finite),
>    run 2 passed. (`/tmp/fix-v9-nocapture-{1,2}.log`)
> 4. **DSP itself is NOT required.** Two chained runs with `-Dnd4j.dsp.noFreeze=true`
>    (DSP disabled entirely): BOTH still NaN'd on call 2 with the same signature.
>    The bug lives in the eager/op execution path, not the DSP plan machinery.
>    (`/tmp/fix-v10-nodsp-{1,2}.log`)
> 5. **Synthetic isolation tests of the op all PASS** (6/6 finite each):
>    - raw `Nd4j.exec(GatedDeltaRule)` production shapes L=1241→1014 ×3 rounds
>      (`GatedDeltaRuleSequenceIsolationTest`),
>    - per-call SameDiff FLOAT graphs (`GatedDeltaRuleSameDiffIsolationTest`),
>    - per-call SameDiff HALF→FLOAT cast-chain graphs matching the model dtype topology
>      (`GatedDeltaRuleHalfCastIsolationTest`),
>    - ONE shared graph with shape churn 1241→1014 ×3 rounds
>      (`GatedDeltaRuleSharedGraphIsolationTest`).
>    So the kernel, the op wrapper, and SameDiff invocation are fine in isolation.
> 6. **Remaining differentiator = the real model context** (conv1d/l2norm input
>    character, workspace state, 345-step replay history, exact q/k/v values).
>    Fixture capture is wired: rerun the IT with
>    `-Dnd4j.dsp.nonFiniteFixtureDir=/tmp/gdr-fixture` until it fails (~50–60% per run);
>    then replay via `GatedDeltaRuleProductionFixtureTest
>    -Dgdr.production.fixture.dir=/tmp/gdr-fixture`.
>
> **2026-08-28 FIXTURE REPLAY VERDICT (session 2, decisive).** A real fixture was
> captured (run 4 of the capture loop, `/tmp/gdr-fixture`: 7 input .bin files +
> captured non-finite outputs + fixture.properties). Replayed via
> `GatedDeltaRuleProductionFixtureTest -Dgdr.production.fixture.dir=/tmp/gdr-fixture`:
>
> - Captured in-model GDR output: firstNonFinite=104468 (t=51,h=0,dv=20),
>   nonFiniteCount=1926, maxAbsFinite=2.78e12 — garbage.
> - **Double-precision reference over the same inputs: fully finite**
>   (maxAbsOutput=0.014, maxAbsState=1.63). The inputs are mathematically GOOD.
> - **Native direct replay (JCublas): finite, maxAbs=1.16 — correct.**
> - **Fresh one-slot DSP replay: finite — correct.**
> - **Fresh mixed Triton DSP replay: finite — correct.**
>
> Conclusion: the `gated_delta_rule` kernel and op wrapper are NOT the bug. The NaN
> exists ONLY inside the model's long-lived fused plan executor: the prefill plan is
> ONE Triton segment covering all ~1848 slots, and on its (re)played execution the
> GDR slot reads/writes through addresses that don't hold the live values — while the
> plan's slot buffers (what diagnostics/fixture capture read afterwards) DO hold
> correct values. This is the stale-baked-address class documented by
> `test39_RmsNormLinearFp16AfterPlanSwap`, now triggered through the fused Triton
> segment path (capture disabled still fails; DSP `noFreeze` still fails because the
> plan is still built and executed fused). Next code bit: how
> `TritonGraphBackend::executeSegment` / segment replay resolves input/intermediate
> addresses for a fused segment after the executor's external views change across
> shape-churn calls (call 1 → boundary clear → call 2 fresh plan is NOT required for
> the bug — the 1,241→1,014 shape churn within ONE plan build is the suspect window).
> 7. Git history: `93375459a3` ("Anchor SDX CUDA linkage to toolkit target") silently
>    reverted `24745571cd`'s `GdrScratch` RAII guard in the CUDA kernel, but the revert is
>    semantically neutral on the happy path (same alloc/free points; pool failover returns
>    valid pointers), so it is unlikely to be the mechanism. The BROKEN→revert→restored
>    cycle (`528c0005d4` → `84915ef2e2` → `ceecd3530f`) touched only the CPU helper.
>
> **2026-08-28 SESSION 3 — bit #7 evidence chain (stale-address mechanism).** SEGMENT
> diagnostics on the real model pinned down the fused replay structure and a prime-suspect
> regression in `24745571cd`:
>
> - The 1,848-slot plan is NOT one fused Triton blob. It is a fine ISLAND/GAP
>   interleaving (Triton islands 2–16 with native gap slots between). `gated_delta_rule`
>   runs as a **live native gap** (its gap unit refuses capture: UNSUPPORTED in
>   `OpCategoryTable.h` + `OP_TRAIT_EXTERNAL_WORKSPACE` in `OpTraitTable.cpp:434` —
>   which also mis-registers the op as `UNARY_ACT`). Its upstream view slots
>   (434/445/447/460 reshapes/slices) ARE capture-safe gap units.
> - Verified eliminated: pool failover (0 FAILOVER events in any failing run), capture
>   workspace OOM (throws loudly), chunked-vs-sequential kernel dispatch (model takes
>   sequential due to `actualLen != nullptr`; synthetic tests cover it), managed-memory
>   failover kinds.
> - Prime suspect: `24745571cd` refactored the per-replay **slot-address drift hash**
>   (`computeSlotAddrHash`) and the stale-slot invalidation sweep from a plain
>   slot-index sweep `[startSlot..endSlot]` onto
>   `dsp::forEachSegmentOutputSlot` / `dsp::computeSegmentSlotAddrHash`
>   (`DspSegmentOutputUtils.h` / `DspHashUtils.h`), which (a) walk **op wiring**
>   (`wiring.outputSlotIndices`) instead of slot indices — slots whose wiring is
>   null/stale at hash time silently drop out of drift detection — and (b) the
>   invalidation sweep now **skips frozen-constant slots entirely** (new early-return).
>   Combined with the fast-replay skip thresholds
>   (`kAddrStableSkipThreshold=3`, recheck every 64 steps), undetected address drift on
>   view/gap slots lets replay proceed against stale baked addresses → GDR's live gap
>   reads recycled memory → NaN, while post-mortem lineage reads the correct current
>   buffers (matches every observation incl. the fixture verdict).
> - Next: capture MEMORY+SEGMENT diagnostics on a FAILING run (loop running) and check
>   for SLOT_ADDR_DRIFT / MERGED_GRAPH_LIFECYCLE events at the call-2 window; then apply
>   the minimal fix (restore full-slot-index coverage in the drift hash + invalidation
>   sweep) and re-verify ≥2 consecutive passing ITs.
>
> **2026-08-28 SESSION 3 — bit #9 addendum: diagnostics mask the bug; tripwire phase.**
> 1. Failing-run phase confirmed (v13 MEMORY+SEGMENT run): call 2 fails between
>    `prefill-done` and `warmup-done` — first warmup executions of the freshly rebuilt
>    L=1014 plan. Lineage: ALL GDR inputs finite/correct (q/k/beta/gate/state/conv1d
>    slots 414–462), ONLY GDR output slot 460 garbage (nan=1922, min −1.79e11) and
>    state-out slot 461 has exactly 256 NaNs = one full (b,h) slice.
> 2. v14 (SEGMENT+EXECUTE, 4/4 PASS) + v15 (SEGMENT+EXECUTE+fixture, 5/5 PASS) = 9
>    consecutive passes under full diagnostics vs ~50% failure rate undiagnosed →
>    diagnostic writes serialize/sync the stream enough to hide the race. STOP looping
>    with SEGMENT/EXECUTE/MEMORY diag enabled.
> 3. Tripwires added (env-gated, zero-sync, host-side only):
>    - `[GDR-TRACE]` in gated_delta_rule.cu `gatedDeltaRuleFromArrays` via
>      `ND4J_GDR_LAUNCH_TRACE=1`: per-call pointers (Q/K/V/beta/gate/state/out/ws),
>      host mirror of actualLen, stream + previous-call stream/ws — catches stale input
>      pointers, pool-block reuse across stream changes, stream switches mid-prefill,
>      and stale len mirror without perturbing timing.
>    - `GDR_DRIFT_TRIPWIRE` in NativeDynamicShapePlan_gpubackend.cu compositeReplay:
>      fail-closed drift detection — if the wiring-walk hash visits ZERO output slots
>      over a non-empty range (vacuous hash == FNV offset basis "validates" as stable),
>      force `markArgsStale()` refresh instead of trusting the hash; partial null-wiring
>      coverage logged as `GDR_DRIFT_TRIPWIRE_PARTIAL`.
> 4. Failure loop for the tripwire build: fixture hook ONLY (no SEGMENT/EXECUTE diag),
>    ND4J_GDR_LAUNCH_TRACE=1 — expect ~50% failure per run; on failure, diff [GDR-TRACE]
>    call-1 vs call-2 prefill lines against the captured fixture lineage.
> 5. v16 outcome (tripwire build, run 5 failed, 10-file fixture captured):
>    - ZERO `GDR_DRIFT_TRIPWIRE` firings → wiring-walk drift hash had full coverage at
>      the failing call; the 24745571cd vacuous-hash theory is evidence-weakened (failure
>      is at call-2 WARMUP slot-by-slot execution, before compositeReplay runs anyway).
>    - `[GDR-TRACE]` diff of the failing call-2 prefill (calls 6246–6263, 18 GDR calls
>      at L=1014): fresh input pointers every call, `lenHost=1014` correct, stream
>      constant after the normal boundary switch, NO later GDR call touches layer-0
>      buffers (`out/sOut/ws` disjoint across calls in both passing and failing runs).
>      Note: ~18 GDR calls per prefill (hybrid attention/GDN layers), not 48.
>    - The apparent "extra L=1241 burst" at calls 6264–6281 is the NEXT CRAWL CHUNK
>      (request 3) after call 2 failed at generatedTokens=0 — not part of call 2.
>    - Conclusion: GDR was handed exactly what the fixture proved is good, yet produced
>      garbage in-model. Remaining discriminators: (a) garbage existed AT GDR's return
>      (in-call race: workingState/convertStateKernel/scan overlap — fixture can't
>      reproduce in-model pool/queue state), or (b) post-call clobber of slot 460 by a
>      non-GDR op before the lineage dump.
> 6. `GDR_POSTCHECK` added (env `ND4J_GDR_POSTCHECK=1`): after each L>1 GDR call, ONE
>    cudaStreamSynchronize + finite scan of output/stateOut, logged as `[GDR-POSTCHECK]`.
>    Distinguishes (a) from (b) on the next failing run. CAVEAT: adds a sync per call —
>    if all runs pass under it, the race is in async overlap across GDR's boundary
>    (host-launch ordering), not inside the call.

### 1. Shape-specific plan/intermediate retention across requests

Call 1 leaves a 1,848-slot plan resident. Call 2 uses an 1,854-slot shape and raises pool reservation by another ~8.2 GiB to almost the entire 24 GiB device. Memory later falls enough for the retry to succeed. Inspect:

- plan-cache ownership and eviction between calls;
- whether both shape-specific plans retain capture workspaces/intermediates;
- `releaseGpuIntermediates`, plan destruction, passivation, and session cleanup at the call boundary;
- whether a cached generation session unintentionally keeps request-specific prefill buffers alive.

### 2. Long-prefill `gated_delta_rule` numerical failure

The first bad tensor is produced by `gated_delta_rule` with finite inputs at sequence length 1014. Reproduce the relation/long prompt as the **first request in a fresh process**. If it still fails with ample free VRAM, the memory-retention hypothesis is weakened and the op/kernel path becomes primary.

### 3. Recurrent-state or argument/pointer contamination

Although `past_gdn_state.0` is reported as finite zeros, inspect whether other internal recurrent/conv state or captured argument tables survive request 1. Compare a long-prefill-first run with the current short-request-then-long-request sequence.

### 4. Backend/capture path regression

Compare recent known-good and current behavior for the 1,854-slot strict-Triton plan, especially native-range capture, direct backend admission, argument refresh, and first prefill. Do not infer causality from a commit merely because it falls between build stamps.

## Recommended minimal isolation sequence

Do not edit first. Collect these controls in separate fresh JVMs, one at a time:

1. **Long relation prompt first:** run the failing 1014-token prefill without call 1. Record free/used/reserved memory and whether slot 460 remains finite.
2. **Two calls with lifecycle diagnostics:** reproduce call 1 then call 2 while logging plan creation, cache hits, passivation, `releaseGpuIntermediates`, plan destruction, and pool deltas.
3. **Same shape twice:** determine whether the memory jump comes from a second shape-specific plan or every new request.
4. **Known-good runtime comparison:** compare the same two-call sequence against the recent known-good ModelToCrawl run, focusing on plan count and memory after call 1—not only final output.
5. Only after a cause is demonstrated, make the smallest manual source edit and rerun both the native replay control and exact ModelToCrawl test.

## Required behavior / success criteria

A valid fix must satisfy all of the following:

- the exact ModelToCrawl test succeeds in the intended two model calls—one entity call and one relation call;
- no NaN or non-finite tensor appears in `gated_delta_rule` or logits;
- no failed model call is hidden by retry/error swallowing;
- consecutive request memory is bounded; a second prompt shape must not reserve effectively all VRAM;
- the native decoder control remains in `REPLAYING` with no mid-execution recompile or argument-refresh churn;
- the existing mutable-scalar slice regression remains correct.

## Key log locations

- CUDA/Triton rebuild: `/tmp/dl4j-minimal-rollback-cuda-build.log`
- Passing native decoder gate: `/tmp/dsp-real-decoder-replay-minimal-rollback.log`
- Native diagnostics JSON: `/tmp/dsp-real-decoder-replay-minimal-rollback.json`
- Failing exact ModelToCrawl run: `/tmp/model-to-crawl-minimal-rollback.log`
