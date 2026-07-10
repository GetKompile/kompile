# dl4j handoff — DSP executor ~10s per cached [32×512] bge-base forward pass (100× too slow)

**Owner:** dl4j (ND4J `DynamicShapePlanExecutor`). NOT a kompile issue — ruled out below.
**Severity:** dominates FP&A crawl wall-clock. 22 files → ~9189 entity embeddings × (10s / 32-batch) ≈ tens of minutes of pure dispatch overhead. A healthy forward pass would make this seconds.
**Date:** 2026-07-05. Box: RTX 3070 Ti (GPU0) + RTX 4090 (GPU1), 125GB RAM, 32 cores.

## Symptom
Steady-state (AFTER the one-time plan warmup), each `model.output()` on a **cached, shape-frozen** `[32×512]` bge-base-en-v1.5 encoder plan takes ~10s:
```
DynamicShapePlanExecutor -- Native executor: exec=10031ms copy=16ms (1 outputs)
DynamicShapePlanExecutor -- Native executor: exec=9869ms  copy=15ms (1 outputs)
DynamicShapePlanExecutor -- Native executor: exec=10152ms copy=28ms (1 outputs)   # consistent ~10s
```
Between calls: `STALE_BUFFER_SCAN: total=3 constants=0 placeholders=3 other=0 resolved=3` (cheap). One-time warmup: `DSP plan ready: [32 x 512] (27809ms)` then `plan cache intact`, `shapesFrozen=true` — so NO per-batch recompilation.

## The decisive evidence: GPU is boosted but IDLE during the 10s
`nvidia-smi` sampled DURING an active `exec`:
```
GPU1 RTX 4090:  P0,  2760 MHz (max 3105),  util 1%,  107W / 450W,  59°C
```
- **P0** = full boost clock (not throttled).
- **1% util / 107W** = the GPU is essentially idle during the 10 seconds.
So the 10s is **host-side dispatch/orchestration overhead in the executor**, not GPU compute and not a stall waiting on the device. The graph is large (log: `FROZEN_INPUT_OPT: built placeholderIndices[3], derivedIndices[0], controlIndices[376] (extInputs=689)`) — 376 control indices + 689 external inputs suggests per-op Java↔native dispatch cost dominates.

## Ruled out (so this is squarely the executor)
- **NOT power state:** measured at P0 boost, not P5/P8. (P5 idle was only seen when NOT embedding.)
- **NOT fp16:** reproduced identically with `-Dnd4j.optimizer.fp16=false` (we set it false to fix an unrelated all-NaN bug). Speed unchanged → fp16 is not the cause.
- **NOT plan recompilation:** plan is compiled once (27.8s warmup) then cached/frozen; the 10s is steady-state on the cached plan.
- **NOT kompile batching:** kompile feeds proper 32-row batches (`SHAPE BATCH CREATE: ... [32 x 512], totalTokens=16384`). Fixed shape `[32×512]` is used specifically to enable plan caching.
- **NOT CPU fallback:** the process holds 16GB VRAM on GPU1 (`nvidia-smi --query-compute-apps` shows the embedding pid at 16006 MiB) and the executor reports the DSP/native path succeeded.

## The ask
Profile `DynamicShapePlanExecutor.exec` for a cached, shape-frozen plan where the device is idle. A ~10s host-side cost for a 12-layer 768-dim BERT forward at batch 32 is ~100× off — expected ~50–150ms on a 4090. Likely suspects: per-op JNI dispatch / arg-table rebuild / control-index traversal / stale-buffer scan running on the critical path; or a missing "fast replay" path for an already-captured frozen plan (the flags `-Dnd4j.triton.graphCapture=true -Dnd4j.triton.consolidatedArgTable=true -Dnd4j.dsp.freezeMergeSegments=true` are set — is graph-capture replay actually being taken, or is it re-walking the graph each call?).

## Repro (minimal)
1. Load bge-base-en-v1.5 as a SameDiff model (the encoder kompile uses is `io.anserini.encoder.samediff.GenericDenseSameDiffEncoder`).
2. Warm up once at fixed shape `[32, 512]` (int64 input_ids/attention_mask/token_type_ids).
3. Loop `sd.batchOutput`/`model.output()` on `[32,512]` inputs; log `System.nanoTime()` around the call and sample `nvidia-smi dmon`/`--query-gpu=utilization.gpu,pstate,power.draw`.
4. Observe ~10s/call at P0 + ~1% util. Env in use: `nd4j-cuda-12.9` (org.eclipse.deeplearning4j 1.0.0-SNAPSHOT), JDK17, `-Dnd4j.optimizer.enabled=true -Dnd4j.dsp.batchedGemm=true -Dnd4j.dsp.captureWorkspaceMb=2048 -Dnd4j.triton.graphCapture=true`.

Follow the dsp-700 handoff convention (a `CONTEXT.md` with REQUIRED BEHAVIOR). REQUIRED BEHAVIOR: a cached shape-frozen encoder plan replays in device-bound time (GPU util ≫ 1% during exec), not ~10s of host overhead.

## It also CASCADES into a subprocess liveness failure (observed 2026-07-05)
The slowness is not merely slow — it progressively worsened and killed the lane. Over one crawl, `[32×512]` batch spacing degraded **10s → ~60-73s → >300s** while host RSS climbed toward the 57GB javacpp cap. A single batch then exceeded the 300s lane timeout → `Embedding lane timed out after 300s — lane-dead` → `Process unresponsive (heartbeat timeout)` → the health monitor declared the subprocess crashed and the circuit breaker **declined restart** ("embedding stalled … Restarts exhausted after 1 attempt"). Net: embedding died mid-crawl and did not recover. The progressive slowdown + host-memory growth on a cached plan points at DSP workspace/buffer accumulation not being freed between `exec` calls (flags in play: `-Dnd4j.dsp.captureWorkspaceMb=2048 -Dnd4j.dsp.proactiveEvict=true -Dnd4j.dsp.lruEviction=true`). fp32 (`optimizerFp16=false`, set to avoid the NaN bug) doubles the footprint vs fp16 and accelerates the degradation — but fp16 is not a fix (it just trades the crash for NaN dead-letters). REQUIRED BEHAVIOR addendum: steady-state per-batch time and host memory must be FLAT across a long run (no per-batch accumulation), so the lane never trips the liveness timeout.

## SCOPE CORRECTION (2026-07-06) — most of the observed per-batch cost was a KOMPILE bug, now fixed (and VERIFIED)
A thread dump showed the ~28-37s of the batch cycle was NOT dl4j: `GenericDenseSameDiffEncoder.extractAllEmbeddings` read the `[batch×dim]` output with a per-element `getFloat` loop, each triggering `AtomicAllocator.synchronizeHostData → CudaExecutioner.commit` — ~24k GPU→host syncs per forward pass.

**First attempt was wrong and worth recording:** switching to `INDArray.toFloatVector()` did NOT fix it. In this dl4j build `BaseNDArray.toFloatVector()` (BaseNDArray.java:3561) does `ensureLocation(HOST)` and then **still loops `getFloat(i)`** — and on a CUDA buffer every `getFloat` re-enters `synchronizeHostData → commit` regardless of the prior ensureLocation. So toFloatVector has the identical per-element-sync cost as the original `getFloatsAt`. A second thread dump caught it red-handed: `...toFloatVector(3561) → getFloat(4362) → synchronizeHostData(310) → CudaExecutioner.commit(2269)`.

**Correct fix (verified):** `DataBuffer.asFloat()`. `BaseCudaDataBuffer.asFloat()` does `lazyAllocateHostPointer()` + **one** `allocator.synchronizeHostData(this)` for the whole buffer, then `super.asFloat()` which reads via `getFloatUnsynced` (no per-element sync). kompile's `safeToFloatVector` now does: compact to c-order with `dup('c')` only if the array is a view, then `data().asFloat()`. This is the established kompile bulk idiom (AnseriniVectorStoreImpl / VlmExecutionService / TransEModel / INDArrayConverter all use `data().asFloat()`).

**Measured impact (fpna-v11, 22 docs, backfill of ~5836 nodes):** distinct-batch CREATE→CREATE dropped from ~38s (exec 10s + ~28s getFloat commits) to **~10.3s (≈ exec only)** — the per-element commit gap is eliminated. exec stayed FLAT at ~10.3-10.6s across the whole backfill (el=104s→965s) with no progressive degradation (the earlier 10→60→300s→lane-death climb is gone).

**dl4j item (still open):** `DynamicShapePlanExecutor.exec` is the remaining ~10s at ~1% GPU util on a cached shape-frozen `[32×512]` plan — the numbers in the Symptom section. Separately, **dl4j's own `INDArray.toFloatVector()` should delegate to `data().asFloat()`** (or use `getFloatUnsynced` after `ensureLocation`) instead of looping synced `getFloat` — it is a latent O(n)-GPU-sync footgun for every CUDA caller, not just this encoder.
