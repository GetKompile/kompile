# SmolDocling Fresh-Server Validation Handoff

## Agent prompt

Work in:

- `/home/agibsonccc/Documents/GitHub/kompile`
- `/home/agibsonccc/Documents/GitHub/deeplearning4j`

Read both repositories' `AGENTS.md` files first. Also load the `/libnd4j-math-kernels` skill before reviewing or changing any DL4J/VLM/DSP code.

## Important constraints

- There are unrelated uncommitted changes in both repositories. Do not revert, overwrite, clean, stash, or reset them.
- Never use `git checkout`, `git reset`, `git clean`, or `git stash`.
- DL4J tests belong exclusively in `deeplearning4j/platform-tests`.
- Run DL4J tests from `platform-tests` and pipe output through `tee`.
- Do not introduce an arbitrary `maxNewTokens` workaround.
- Do not enable adaptive-region fallback to hide failures.
- Never broadly kill processes. Terminate only exact PIDs started by this task.

## Objective

Using a freshly rebuilt and restarted MCP server, validate the complete MCP-only SmolDocling image-PDF workflow after the current source changes:

1. Full-page generation runs until the model EOS within its declared context.
2. No arbitrary `maxNewTokens` is injected.
3. Two image-rich PDF pages process successfully without DSP/lifecycle errors.
4. `crawl_control` reports page-level progress instead of remaining at 30%.
5. Cancelling a crawl terminates its exact pipeline runtime, remains `CANCELLING` during cleanup, then becomes `CANCELLED`.
6. A crawl started after cancellation does not block on or reuse a poisoned child.
7. The resulting searchable knowledge base returns extracted content.

## Relevant implemented changes

### DL4J

- SmolDocling context is read from `tokenizer_config.json` `model_max_length`.
- Auto generation budget is `contextWindow - actual combined prompt tokens`.
- Direct image generation counts both image and text tokens.
- All configured EOS token IDs are honored.
- `</doctag>` is content, not a fake EOS.
- Cached generation pipelines rebuild when prompt token count changes.
- DL4J tests are only under `platform-tests`:
  - `VisionLanguageModelContextBudgetTest`
  - `MultiPartModelLoaderConfigTest`

### Kompile

- `OcrPipelineConfig.maxNewTokens` defaults to `0`, meaning EOS/context mode.
- `MAX_TOKENS` partial output is rejected.
- Adaptive region recovery is explicit opt-in and defaults off.
- `maxResponseBytes` is an independent `long` UTF-8 byte guard, default 16 MiB.
- Page progress flows through request-scoped pipeline `PROGRESS` messages.
- Sequence, graph, and graph-loop contexts propagate progress.
- Interrupted waits cancel and terminate the exact pipeline child.
- Cancelled runtimes are tainted and cannot return to the pool.
- Local jobs remain `CANCELLING` until cleanup completes.
- Start/cancel, terminal/progress, and late-callback races were addressed.
- Crawl loops and graph/persistence boundaries have cancellation checkpoints.

## Build and test first

Build the authoritative local DL4J Java module:

```bash
cd /home/agibsonccc/Documents/GitHub/deeplearning4j
set -o pipefail
/home/agibsonccc/dev-apps/mvn/bin/mvn install -DskipTests \
  -pl :samediff-vlm 2>&1 | tee /tmp/samediff-vlm-fresh-server-build.log
```

Run DL4J tests only from `platform-tests`:

```bash
cd /home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests
set -o pipefail
/home/agibsonccc/dev-apps/mvn/bin/mvn test \
  -Dtest=VisionLanguageModelContextBudgetTest,MultiPartModelLoaderConfigTest \
  2>&1 | tee /tmp/vlm-context-fresh-server-tests.log
```

Build Kompile from its repository root:

```bash
cd /home/agibsonccc/Documents/GitHub/kompile
./mvnw -pl :kompile-cli-main -am -DskipTests install \
  -Dkompile.backend=cuda-12.9 \
  -Dnd4j.backend=nd4j-cuda-12.9 \
  -Dkompile.cuda=true
```

Run focused Kompile tests:

```bash
./mvnw \
  -pl :kompile-pipelines-framework-api,\
:kompile-pipelines-framework-runtime,\
:kompile-ocr-core,\
:kompile-ocr-models,\
:kompile-pipelines-steps-vlm,\
:kompile-pipeline-serving,\
:kompile-cli-main \
  -Dtest=OcrPipelineConfigTest,\
VlmDocumentPipelineResponseLimitTest,\
VlmDocumentStepRunnerFactoryTest,\
PipelineRuntimeProtocolTest,\
LocalCrawlJobRegistryTest,\
PipelineRuntimeSupervisorTest,\
ComponentInstallerTest,\
CrawlDiscoveryToolTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkompile.backend=cuda-12.9 \
  -Dnd4j.backend=nd4j-cuda-12.9 \
  -Dkompile.cuda=true test
```

Stage the newly built pipeline-serving executable through the CLI so the atomic update targets the
same distribution path used by the subprocess launcher:

```bash
java -jar kompile-cli/kompile-cli-main/target/kompile-cli-main-0.1.0-SNAPSHOT-shaded.jar \
  install kompile-pipeline-serving --local \
  kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/target/kompile-pipeline-serving-0.1.0-SNAPSHOT-exec.jar
```

The old runtime previously launched:

```text
~/.kompile/lib/kompile-pipeline-serving-exec.jar
```

Verify the fresh child's metadata/log records point to the newly built artifact. Do not assume a successful Maven build automatically replaced the installed runtime.

Restart only the MCP/server processes owned by this task. Confirm fresh PIDs and that no old pooled `pipeline-serving` child remains.

Verify project config resolves:

```text
config/nd4j-environment-config.json
optimizerFp16=false
```

## MCP validation crawl

Confirm model and pipeline readiness with:

- `model_runtime status` for `smoldocling-256m`
- `pipeline list`
- `crawl_discover`
- `crawl_documents dryRun=true`

Use this image-rich PDF:

```text
/home/agibsonccc/Documents/GitHub/kompile/docs/beyond-rag-part2-presentation.pdf
```

Use pages 4-5. They both contain raster images.

Create a unique KB such as:

```text
smoldocling-fresh-eos-multipage-<timestamp>
```

Request shape:

- `pipelineId`: `vlm-ocr-pdf`
- `pipelineType`: `VLM`
- `registeredPipelineId`: `vlm-ocr-pdf`
- `modelId`: `smoldocling-256m`
- `loaderName`: `pdf`
- `chunkerName`: `sentence`
- `chunkSize`: `800`
- `chunkOverlap`: `100`
- `outputFormat`: `MARKDOWN`
- `pageRange`: `4-5`
- `maxPages`: `2`
- `pdfRenderDpi`: `144`
- `pageBatchSize`: `1`
- `temperature`: `0.0`
- `doSample`: `false`
- `maxResponseBytes`: `16777216`
- **Do not set `maxNewTokens`.**
- `adaptiveRegionFallbackEnabled`: `false`
- Steps:
  - `LOADING`
  - `MARKDOWN_EXTRACTION`
  - `CHUNKING`
  - `LEXICAL_INDEX`
- `strictSteps`: `true`
- `deriveOntology`: `false`
- `embeddingTraining.enabled`: `false`
- `reasoningLearning.enabled`: `false`
- `runtimeConfig.runReasoningLearning`: `false`
- `modelRuntime.autoBootstrap`: `false`

Run `dryRun` first, inspect effective resolution, then start asynchronously.

Poll `crawl_control status`. Confirm at least one nonterminal status includes:

- `pipelineProgress`
- `currentPage`
- `totalPages`
- `phase`/`currentStep`
- `progressPercent` greater than 30 and below completion

After completion verify:

- Status is `COMPLETED`.
- `failedDocumentCount = 0`.
- `markdownCount >= 1`.
- `chunkCount >= 1`.
- `extractionStatus = EXTRACTED`.
- Output includes markers/content from both pages 4 and 5.
- `knowledge_search` retrieves:
  - `16 components`
  - `2,310 chat sessions`
  - `359 docs loaded`
  - `6,729 entities`
  - `9,943 relationships`

Inspect the fresh `pipeline-serving` log. Confirm:

- Generation was configured from actual model context, not a fixed 4096/768 cap.
- Both pages completed.
- Decoder reached replay with stable pointers where applicable.
- Finish reason is `EOS`, not `MAX_TOKENS`.
- No adaptive-region retry.
- No `CLOSED DataBuffer`.
- No lifecycle violation.
- No DSP path failure.
- No NaN.
- No `RuntimeFailure`.

## Cancellation validation

Start a separate crawl using the 330-page fixture:

```text
/home/agibsonccc/Documents/GitHub/kompile/kompile-vlm-demo/sample-pdfs/sample.pdf
```

Use a unique KB and page 2 or pages 1-3, with no `maxNewTokens`. Cancel promptly after `pipelineProgress` confirms VLM generation has begun.

Verify:

1. Initial cancel response/status is `CANCELLING` and `terminal=false` while cleanup is active.
2. Final status becomes `CANCELLED` and `terminal=true` only after cleanup.
3. Identify the exact `pipeline-serving` child PID from its metadata.
4. Confirm that PID exits.
5. Start a new compatible crawl afterward.
6. Confirm it starts with a healthy/new runtime and does not queue behind the cancelled request.

Do not manually kill the child unless cancellation itself fails. If it fails, record the exact PID, protocol state, status payload, and logs before taking any targeted cleanup action.

## Failure policy

If anything fails:

- Diagnose the real root cause.
- Do not add a hardcoded token cap.
- Do not enable adaptive region fallback.
- Do not accept partial `MAX_TOKENS` output.
- Do not disable DSP.
- Do not weaken tests.
- Do not claim success from top-level `COMPLETED` alone; verify document records, Markdown, chunks, retrieval, progress, and subprocess logs.

## Final report

Return:

- Build/test results
- Fresh MCP/server and child PIDs
- Effective crawl request
- Page-level progress samples
- Crawl/document/chunk counts
- Retrieval evidence
- DSP/EOS log evidence
- Cancellation timing and child termination evidence
- Whether the subsequent crawl started without blocking
- Any remaining usability or correctness issues
