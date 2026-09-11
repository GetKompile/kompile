# Novel reader OCR CUDA failure — investigation handoff

## Request and scope

The user will assign a dedicated agent to diagnose/fix this failure. Do not treat the remote Luna success as a CUDA fix. The novel-translation agent is continuing application work using Kompile remote model pipelines. Preserve concurrent work in Kompile/DL4J; coordinate before edits or GPU runs. Do not rebuild native code reflexively, change optimizer settings to conceal the problem, or delete caches. No Maven clean for iterative native builds.

## Observed failure (2026-09-11)

A real Chromium-rendered Spanish prose screenshot was processed through the production Java image-to-PDF adapter and Kompile pipeline-serving JAR using installed SmolDocling-256M artifacts. Native DSP execution completed warmup decode, then the worker aborted with an uncaught C++ runtime_error:

```text
17:58:47.346 [pipeline-runtime-execution] INFO org.nd4j.autodiff.samediff.execution.DynamicShapePlanExecutor -- Native executor: mode resolution requested=TRITON resolved=TRITON effective=TRITON tritonAvailable=true fallbackToAuto=false
17:58:47.385 [pipeline-runtime-execution] INFO org.nd4j.autodiff.samediff.execution.DynamicShapePlanExecutor -- DSP device selection: dev0(data=311MB free=20098MB total=24084MB) dev1(data=0MB free=6272MB total=7851MB) -> execution on device 0
17:58:52.221 [pipeline-runtime-execution] INFO org.nd4j.autodiff.samediff.execution.DynamicShapePlanExecutor -- Native executor: exec=1935ms copy=0ms (1 outputs)
17:58:52.222 [pipeline-runtime-execution] INFO org.nd4j.autodiff.samediff.internal.InferenceSession -- [EXEC-PATH] DSP path succeeded for 1 outputs
17:58:52.223 [pipeline-runtime-execution] INFO org.eclipse.deeplearning4j.llm.generation.GenerationPipeline -- [Lifecycle] Retained 60 static KV buffers and fixed decode inputs for embedding generation
17:58:52.223 [pipeline-runtime-execution] INFO org.eclipse.deeplearning4j.llm.generation.GenerationPipeline -- [Perf] Shapes frozen after warmup decode (planPhase=SHAPES_FROZEN pointersStable=false)
terminate called after throwing an instance of 'std::runtime_error'
  what():  thread-local cuBLAS handle creation failed; Error code: [1]
```

No recognized text or translation was returned. This is the observed abort location, NOT a proven root cause. Prior successful GPU execution and the reported free memory make a generic 'GPU unavailable/out of memory' explanation unjustified. Investigate installed-artifact consistency, actual loaded native libraries, execution/thread/device context and post-warmup lifecycle against the known-good tested path. No native debugger backtrace or core analysis was captured in this attempt. The Java stack below is the parent observing worker EOF, not the C++ throw stack.

## Captured Java stack (application frames)

```text
java.util.concurrent.ExecutionException: java.io.IOException: Pipeline runtime stdout closed:
[worker stderr tail ending in the C++ exception above]
    at java.base/java.util.concurrent.CompletableFuture.reportGet(CompletableFuture.java:396)
    at java.base/java.util.concurrent.CompletableFuture.get(CompletableFuture.java:2096)
    at ai.kompile.pipeline.serving.launcher.PipelineRuntimeSession$Execution.await(PipelineRuntimeSession.java:113)
    at org.opennovel.executor.DirectBackend.run(DirectBackend.java:70)
    at org.opennovel.executor.RealPipelineSmokeTest.capturedPage(RealPipelineSmokeTest.java:36)
    at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
    at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)
    at java.base/jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
    at java.base/java.lang.reflect.Method.invoke(Method.java:569)
[JUnit/Surefire frames — full original trace linked below]
Caused by: java.io.IOException: Pipeline runtime stdout closed:
[repeated worker stderr tail]
    at ai.kompile.pipeline.serving.launcher.PipelineRuntimeSession.readOutput(PipelineRuntimeSession.java:270)
    at java.base/java.lang.Thread.run(Thread.java:840)
```

Full captured exception including all JUnit/Surefire frames and duplicated stderr (263 lines):
`../novel-translation/.kompile/dogfood-20260911/first-error.txt`

Full retained Maven attempt output (includes bounded worker stderr, not necessarily the entire child log):
`../novel-translation/.kompile/process-output/f49489fa-f790-41e3-be64-f8339fbc37aa/proc-015.log`

Attempt duration ~79 seconds, exit 1. Since the launcher embeds a bounded stderr tail, do not describe either file as a complete native trace. Search the private runtime log files or reproduce under a debugger if native frames are needed.

## Inputs and artifact identities

Paths are relative to this Kompile checkout unless absolute:

- Screenshot: `../novel-translation/.kompile/dogfood-20260911/screenshot.png` (900x571).
- Actual adapter PDF: `../novel-translation/.kompile/dogfood-20260911/screenshot.pdf`.
- OCR prompt: `../novel-translation/.kompile/dogfood-20260911/ocr-prompt.txt`.
- Complete recorded paths/hashes: `../novel-translation/.kompile/dogfood-20260911/identity.json` (read this before reproducing).
- Installed pipeline JAR: `/home/agibsonccc/.kompile/lib/kompile-pipeline-serving.jar`.
- Recorded JAR SHA-256: `b4b953ebc86789f492c9388d43b14f21699f496ee5ccf4e3be13da02a2d9f2b8`.
- OCR directory: `/home/agibsonccc/Documents/GitHub/kompile/data/models/vlm-pipelines/smoldocling-256m`.
- Recorded OCR manifest: `sha256:adbaf10d55e4a77c137d7a7ea63a3e89559d3e9213f728247b91db69a1229954`.
- Java: `/home/agibsonccc/.sdkman/candidates/java/17.0.17-amzn/bin/java`.

Original screenshot prose includes: `María abrió la ventana. La lluvia había terminado y el jardín olía a flores.` Source Spanish, intended target English. The OCR prompt asks for language/text JSON; SmolDocling compliance with that prompt remains unverified because it crashed first.

The original configured translation artifact `~/.cache/dl4j-llm-models/qwen35-0.8b-staged/model.sdz` was missing. The OCR diagnostic supplied `~/.kompile/models/llm-ggmls/lfm2.5-1.2b-instruct/model.sdz` and its tokenizer ONLY to satisfy configuration fingerprinting. It did not execute that translation model. Do not conflate this separate missing-path problem with CUDA.

## Reproduction

Acquire the shared GPU resource lane first. Run from `/home/agibsonccc/Documents/GitHub/novel-translation`. Use a fresh output directory: diagnostic evidence uses CREATE_NEW. These commands are the documented recipe; inspect the test and identity file for current drift before running.

```sh
OUT="$PWD/.kompile/dogfood-cuda-repro"
desktop-shell/node_modules/.bin/electron desktop-shell/test/capture-real.cjs "$OUT"
env -u KOMPILE_INSTALL_DIR -u KOMPILE_PIPELINE_SERVING_EXECUTABLE \
  -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  KOMPILE_JAVA=/home/agibsonccc/.sdkman/candidates/java/17.0.17-amzn/bin/java \
  /home/agibsonccc/dev-apps/mvn/bin/mvn -o -nsu -f desktop-executor/pom.xml test -Dtest=RealPipelineSmokeTest \
  -Dreal.output="$OUT" \
  -Dreal.runtime=/home/agibsonccc/.kompile/lib/kompile-pipeline-serving.jar \
  -Dreal.model=/home/agibsonccc/.kompile/models/llm-ggmls/lfm2.5-1.2b-instruct/model.sdz \
  -Dreal.tokenizer=/home/agibsonccc/.kompile/models/llm-ggmls/lfm2.5-1.2b-instruct/tokenizer.json \
  -Dreal.ocr=/home/agibsonccc/Documents/GitHub/kompile/data/models/vlm-pipelines/smoldocling-256m
```

## Relevant code and evidence

- `../novel-translation/desktop-executor/src/test/java/org/opennovel/executor/RealPipelineSmokeTest.java`
- `../novel-translation/desktop-executor/src/main/java/org/opennovel/executor/DirectBackend.java`
- `../novel-translation/desktop-executor/src/main/java/org/opennovel/executor/Pipelines.java`
- `../novel-translation/desktop-executor/src/main/java/org/opennovel/executor/Ocr.java`
- `../novel-translation/desktop-shell/test/capture-real.cjs`
- `kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/src/main/java/ai/kompile/pipeline/serving/launcher/PipelineSubprocessLauncher.java`
- Same module: `launcher/PipelineRuntimeSession.java`, `subprocess/PipelineServingSubprocessMain.java`.
- `../novel-translation/docs/DESKTOP_BATCH.md` — exact attempt narrative and known app limitations.

The screenshot helper used software composition after an earlier Chromium GPU compositor `UnknownVizError`; it still produced actual Chromium pixels. That capture-side issue is distinct from the CUDA model-worker abort.

## Remote comparison (not a local fix)

Actual Kompile CHAT_MODEL tests with `openai-codex` / `gpt-5.6-luna` succeeded on the SAME screenshot: language `es`, recognized prose, then English translation with completion marker. OCR run `26f0512b-e32c-404b-b653-78a451e398b6`; translation run `705002fb-83f7-4c82-8aea-05ae41cc4d27`. Evidence: `../novel-translation/.kompile/dogfood-20260911/luna-remote-result.json`. These were separate real remote pipeline tests, not an app-integrated CUDA qualification.

Desired investigation outcome: reproduce with exact artifact/native-library identities, obtain native throw/backtrace if needed, compare with the already-tested SmolDocling runtime/lifecycle, fix the actual discrepancy and rerun the captured-page input through local OCR. Do not declare fixed based on Luna, document-only PDF generation or fixture tests.
