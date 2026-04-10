# CUDA Graph Replay Fix — Handoff Instructions

## Part 1: Fix CUDA Graph Replay Hang (DL4J platform-tests)

### Problem
VLM decoder hangs on step 2 (the **first CUDA graph replay** of the decode plan). Steps 0 (prefill/capture) and 1 (first decode/capture) succeed. Step 2 attempts to **replay** the captured graph and hangs indefinitely on seg[1320-1323] (attention matmuls + Triton softmax).

### Root Cause
cuBLAS workspace is being zeroed (`cudaMemsetAsync`) before segment captures. When segment N captures, cuBLAS caches plan/descriptor data in the workspace. Later segments (like seg[1320-1323]) inherit those cached plans — their captured graphs **omit** H2D re-upload nodes. On replay, the pre-segments workspace zeroing destroys the cached plans, so GEMM kernels read zeros and hang.

A partial fix was applied in `NativeDynamicShapePlan.cpp` (pre-segments zeroing → preservation when `frozenSteadyState`), but there are **separate** pre-capture zeroing calls in `NativeDynamicShapePlan_gpubackend.cpp` that still execute ~100+ times during the capture phase.

### What's Been Done
1. **frozenSteadyState guard** in `NativeDynamicShapePlan.cpp` lines 1048-1398: entire cleanup block wrapped in `if (!frozenSteadyState)`
2. **Release schedule guards** in `NativeDynamicShapePlan_segments.cpp` (4 sites) and `NativeDynamicShapePlan_cudagraph.cu` (1 site): `!shapesFrozen_ && !streamIsCapturing`
3. **cuBLAS workspace preservation** in `NativeDynamicShapePlan.cpp` lines 1435-1450: changed `cudaMemsetAsync` to skip when `shapesFrozen_ && executeCount_ > 0`
4. **NPE fix** in `DynamicShapePlanExecutor.java:1392`: added `fresh.data() != null &&` null check

### What Needs to Be Done

#### Step 1: Write reproducer tests in platform-tests

Write tests in `/home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests/` that replicate what the kompile VLM server does — specifically the multi-step decode loop that triggers CUDA graph capture on steps 0-1 then replay on step 2+. The tests should:

- Load SmolDocling-256M-preview model via `VisionLanguageModel`
- Process a single page from `pathfinder-mythic.pdf` (already at `/home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests/pathfinder-mythic.pdf`)
- Run the `StaticKvCacheDecodeLoop` with `maxNewTokens=50`
- Verify step 2+ (first CUDA graph replay) completes without hanging
- Use a timeout (e.g. 60s) so the test fails fast rather than hanging forever

Look at existing VLM tests in platform-tests for patterns — there should be tests like `testFullVlmTwoPageSimulation` and vision encoder tests already there. The key is exercising the **decode loop replay path** (step 2+), not just prefill.

#### Step 2: Fix the cuBLAS workspace zeroing

Find and eliminate **ALL** `cudaMemsetAsync` / `cudaMemset` calls on `cublasWorkspaceBuffer_` across these files:
- `/home/agibsonccc/Documents/GitHub/deeplearning4j/libnd4j/include/graph/impl/NativeDynamicShapePlan.cpp`
- `/home/agibsonccc/Documents/GitHub/deeplearning4j/libnd4j/include/graph/impl/NativeDynamicShapePlan_gpubackend.cpp`
- `/home/agibsonccc/Documents/GitHub/deeplearning4j/libnd4j/include/graph/impl/NativeDynamicShapePlan_segments.cpp`
- `/home/agibsonccc/Documents/GitHub/deeplearning4j/libnd4j/include/graph/impl/NativeDynamicShapePlan_cudagraph.cu`

The key principle: **once shapes are frozen (`shapesFrozen_` is true), NEVER zero the cuBLAS workspace**. The workspace content must be preserved across all executions because captured CUDA graphs depend on the plan data already being present.

Search for `cudaMemset` and `cublasWorkspace` across all `NativeDynamicShapePlan*` files. The "pre-capture" zeroing in `_gpubackend.cpp` (around lines 1299-1304 area, search for "cuBLAS workspace" or "cublasWorkspace") is the likely remaining culprit.

#### Step 3: Build and run the reproducer tests

```bash
# Build CUDA backend
cd /home/agibsonccc/Documents/GitHub/deeplearning4j
mvn -Pcuda -Dlibnd4j.triton=ON -Dlibnd4j.chip=cuda -Dlibnd4j.buildthreads=12 -Dlibnd4j.log=libnd4j-build.log -pl libnd4j,:nd4j-cuda-12.9 clean install -DskipTests 2>&1 | tee /tmp/cuda-build.log

# Run the reproducer test
cd /home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests
mvn test -Dtest=YourNewTestClass#testDecodeReplay 2>&1 | tee /tmp/test.log
```

Success = step 2+ decode steps complete without hanging.

### User's Directive
"No fallbacks. We need to *ELIMINATE* all this state churn." — Do not add fallback paths or save/restore mechanisms. The workspace must simply be preserved.

### Build Commands (from CLAUDE.md — FOLLOW EXACTLY)
- **CUDA build**: `mvn -Pcuda -Dlibnd4j.triton=ON -Dlibnd4j.chip=cuda -Dlibnd4j.buildthreads=12 -Dlibnd4j.log=libnd4j-build.log -pl libnd4j,:nd4j-cuda-12.9 clean install -DskipTests 2>&1 | tee /tmp/cuda-build.log`
- **Java-only**: `mvn install -DskipTests -pl <module>`
- Tests run from `platform-tests/` ONLY: `cd platform-tests && mvn test -Dtest=TestClass 2>&1 | tee /tmp/test.log`
- NEVER use `make` directly, NEVER set `-Dlibnd4j.compute`, NEVER clear ccache
- ALWAYS pipe through `tee`, NEVER use `tail`

---

## Part 2: Kompile VLM Integration Test

**Only proceed once Part 1 platform-tests reproducers pass.**

### Steps

#### 1. Install the fixed nd4j modules into local Maven

```bash
cd /home/agibsonccc/Documents/GitHub/deeplearning4j
mvn install -DskipTests -pl nd4j/nd4j-backends/nd4j-api-parent/nd4j-api
# Also install the CUDA backend if rebuilt:
mvn install -DskipTests -pl nd4j/nd4j-backends/nd4j-backend-impls/nd4j-cuda-12.9
```

#### 2. Rebuild the kompile sample project to pick up nd4j changes

```bash
cd /home/agibsonccc/Documents/GitHub/kompile/kompile-rag-builds/kompile-sample/project
/home/agibsonccc/dev-apps/mvn/bin/mvn install -DskipTests -Dskip.ui
```

#### 3. Start kompile-model-staging (port 8090)

This serves model downloads to the main app.

```bash
cd /home/agibsonccc/Documents/GitHub/kompile/kompile-app/kompile-model-staging
/home/agibsonccc/dev-apps/mvn/bin/mvn spring-boot:run -Dskip.ui
```

Wait for `{"status":"UP"}` on `http://localhost:8090/actuator/health`.

#### 4. Start kompile-app-main via sample project (port 8085)

```bash
cd /home/agibsonccc/Documents/GitHub/kompile/kompile-rag-builds/kompile-sample/project
/home/agibsonccc/dev-apps/mvn/bin/mvn spring-boot:run -Dskip.ui
```

Wait for `EMBEDDING MODEL READY` in logs. The app runs on port 8085 (no actuator endpoint — 404 on root is expected).

#### 5. Run the VLM test

```bash
curl -X POST 'http://localhost:8085/api/vlm/test/run' \
  -F "file=@/home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests/pathfinder-mythic.pdf" \
  -F "modelId=SmolDocling-256M-preview" \
  -F "maxNewTokens=50" \
  -F "outputFormat=DOCTAGS"
```

This returns a taskId immediately (async). Monitor progress in the main app log.

### What Success Looks Like

- Step 0 (prefill): ~2200-2500ms, generates token like 'User' (id=11126)
- Step 1 (1st decode): ~800-900ms, generates token like ' count' (id=985)
- Step 2+ (replay): Should complete in similar time to step 1, NOT hang
- ~50 decode steps total, generating DOCTAGS output
- Log shows page processing completing, no NPE/hang/OOM

### Known Issues Already Fixed

1. **NPE at DynamicShapePlanExecutor.java:1392**: `fresh.data()` can be null. Fixed by adding `fresh.data() != null &&` guard. Already installed to nd4j-api.
2. **cuBLAS workspace zeroing**: Partial fix applied in `NativeDynamicShapePlan.cpp` (pre-segments preservation). Full fix needs all zeroing sites eliminated in `_gpubackend.cpp` too — this is Part 1.

### What to Watch For

- **GPU hang at step 2**: If still happening, the DL4J fix is incomplete. Go back to Part 1 platform-tests reproducer.
- **OOM**: The pathfinder PDF is 255 pages. If GPU memory runs out, try with `maxNewTokens=10` or a smaller PDF.
- **NPE on `data().wasClosed()`**: Should be fixed, but if it recurs at a different line, apply the same `data() != null` pattern.
