# SmolDocling CUDA DSP Failure Handoff

## Full serving log

```text
/home/agibsonccc/.kompile/logs/subprocesses/serving/ef709096-5855-4ee8-a90e-c65d7633c211.log
```

Metadata:

```text
/home/agibsonccc/.kompile/logs/subprocesses/serving/ef709096-5855-4ee8-a90e-c65d7633c211.meta.json
```

## Prompt for a dedicated DL4J agent

```text
Investigate and fix a SmolDocling CUDA DSP execution failure in the local
Deeplearning4j checkout at:

/home/agibsonccc/Documents/GitHub/deeplearning4j

Read the COMPLETE serving log first:

/home/agibsonccc/.kompile/logs/subprocesses/serving/ef709096-5855-4ee8-a90e-c65d7633c211.log

Metadata:

/home/agibsonccc/.kompile/logs/subprocesses/serving/ef709096-5855-4ee8-a90e-c65d7633c211.meta.json

Observed failure:

- SmolDocling loads successfully from SDZ components.
- vision_encoder.opt.sdz has an optimizer-fingerprint mismatch, so the runtime
  reoptimizes vision_encoder.sdz.
- Page 1 reaches CUDA DSP execution.
- DSP plan slot 393, op `Where`, fails.
- An array with shape [992, 1, 2], length 1984, is reshaped to [1024, 2],
  length 2048.
- Native execution returns status -1; the Where operation returns status 50.
- DynamicShapePlanExecutor disallows fallback.
- The containing PDF has 330 pages, but only page 1 is needed to reproduce.
- This is not an OOM: metadata records oomDetected=false and
  gpuOomDetected=false.

Relevant log message:

reshapeShapeInfo FAIL: array shape=[992, 1, 2] len=1984 dtype=10
newShape=[1024,2] arrLength=2048 order=c

DynamicShapePlan-based execution failed — no fallback allowed:
Native plan execution failed with status -1: slot 393 (Where) failed with
status 50: slot 393 (Where) exec exception: reshapeShapeInfo: bad length
of new shape!

Reproduction inputs:

PDF:
/home/agibsonccc/Documents/GitHub/kompile/kompile-vlm-demo/sample-pdfs/sample.pdf

Model directory:
/home/agibsonccc/Documents/GitHub/kompile/data/models/vlm-pipelines/smoldocling-256m

Execution parameters:

- pdfRenderDpi=300
- pageBatchSize=1
- outputFormat=DOCTAGS
- CUDA 12.9 backend
- Java heap 8 GiB

Tasks:

1. Trace slot 393 back to the original SameDiff/ONNX variables and determine
   why the plan expects 1024 entries but receives 992.
2. Determine whether the bad shape comes from:
   - SmolDocling image tiling or patch count,
   - ONNX dynamic-shape import,
   - GraphOptimizer shape specialization,
   - DSP plan construction/caching,
   - or native Where/reshape execution.
3. Compare standard SameDiff execution against DSP execution on page 1.
4. Check whether optimization incorrectly turns a runtime-derived dimension
   into the constant 1024.
5. Reproduce with the existing SmolDocling platform test where possible.
6. Fix the root cause rather than enabling silent fallback.
7. Add a focused regression test covering the 992-versus-1024 shape case.
8. Run the narrow relevant DL4J tests and report exact commands/results.

Follow local DL4J source as the authority. Do not download or replace the local
1.0.0-SNAPSHOT artifacts from remote repositories. Do not modify Kompile merely
to suppress or bypass the DSP failure.
```

## Follow-up failure: DSP replay demotion

The slot-393 `Where` reshape failure above did **not** recur after the latest
DL4J changes. A fresh run now fails on a different DSP lifecycle invariant.

Pipeline run:

```text
84164087-29b4-4f62-98d4-6480df1d020a
```

Complete serving log:

```text
/home/agibsonccc/.kompile/logs/subprocesses/serving/af9185d5-9493-45bb-886a-a404429e9666.log
```

Metadata:

```text
/home/agibsonccc/.kompile/logs/subprocesses/serving/af9185d5-9493-45bb-886a-a404429e9666.meta.json
```

### Observed behavior

- SmolDocling loads successfully and page 1 reaches tiled CUDA vision encoding.
- Frames 1 through 28 of 31 execute successfully.
- Frame 28 reports `image_features` shape `[1, 64, 576]`.
- Frame 29 enters DSP execution with vision input `[1, 3, 512, 512]`.
- Execution aborts before the former slot-393 `Where` failure.
- The pipeline stops page processing immediately and reports terminal `FAILED`.
- No `[992, 1, 2] -> [1024, 2]` reshape error appears in the new log.

Exact error:

```text
DynamicShapePlan-based execution failed — no fallback allowed:
Native plan execution failed with status -1:
[PHASE_TRANSITION] plan REPLAYING -> SHAPES_FROZEN
reason=segment no longer satisfies replay steady state:
seg[56-116] backend=3 execPhase=BUILDING:WARMUP
segExecCount=1 handleReady=0 compositeReady=0 argStable=0
execCount=1 frozenExec=5 kind=demotion
```

Additional model-cache behavior:

- `vision_encoder.opt.sdz`, `embed_tokens.opt.sdz`, and `decoder.opt.sdz` all
  report optimizer-fingerprint mismatches and are reoptimized at load time.
- Persisting each refreshed optimized SDZ fails non-fatally with
  `Input/output error`.

### Prompt for the DL4J agent

```text
Investigate and fix the new SmolDocling CUDA DSP replay-state failure in:

/home/agibsonccc/Documents/GitHub/deeplearning4j

Read the complete new serving log first:

/home/agibsonccc/.kompile/logs/subprocesses/serving/af9185d5-9493-45bb-886a-a404429e9666.log

Also compare it with the former slot-393 failure log:

/home/agibsonccc/.kompile/logs/subprocesses/serving/ef709096-5855-4ee8-a90e-c65d7633c211.log

The former `[992,1,2] -> [1024,2]` reshape failure no longer occurs. The current
run successfully encodes 28 of 31 page tiles, then aborts on the next identical
`[1,3,512,512]` vision input because segment `[56-116]` transitions from
REPLAYING to SHAPES_FROZEN during BUILDING:WARMUP. Its diagnostic reports
`segExecCount=1`, `handleReady=0`, `compositeReady=0`, `argStable=0`,
`execCount=1`, and `frozenExec=5`.

Tasks:

1. Reproduce the failure using page 1 of the same PDF and the SmolDocling vision
   encoder. The complete PDF is not required.
2. Determine why a segment marked REPLAYING is still in BUILDING:WARMUP with no
   ready handle/composite and unstable arguments.
3. Trace the state transition and identify whether the defect is in segment
   lifecycle bookkeeping, replay eligibility, warmup counters, plan cache
   restoration, or dynamic-shape handling between otherwise identical tiles.
4. Explain why the first 28 tiles succeed but tile 29 causes replay demotion.
5. Verify whether `frozenExec=5` is stale or inconsistent with `execCount=1` and
   `segExecCount=1`.
6. Fix the root lifecycle/state-machine defect rather than enabling fallback or
   suppressing the invariant.
7. Add a focused regression test that repeatedly executes the SmolDocling vision
   graph across at least 31 `[1,3,512,512]` tiles and covers the transition from
   warmup/frozen execution into replay.
8. Confirm that both this replay-demotion failure and the previous slot-393
   reshape failure remain fixed.
9. Run the narrow relevant DL4J tests and report exact commands and results.

Reproduction inputs and runtime parameters remain the same as documented above.
Use local DL4J source and locally built 1.0.0-SNAPSHOT artifacts as the only
authority. Do not work around the failure in Kompile.
```
