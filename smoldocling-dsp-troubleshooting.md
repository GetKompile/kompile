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
