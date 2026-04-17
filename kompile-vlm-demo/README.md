# kompile-vlm-demo

A self-contained, end-to-end demo of Kompile's RAG-over-PDF flow using
**SmolDocling** as a vision-language model for PDF extraction.

This is the VLM variant of `kompile-demo/`. The generated app, staging server,
vector store, LLM load endpoint, RAG query path — all of those are identical.
The only difference is how the input documents get turned into text: instead of
hand-written markdown, each PDF under `sample-pdfs/` is fed to the
`/api/vlm/test/run` endpoint, SmolDocling runs the full vision-encoder →
embed → decoder pipeline on GPU, and the extracted markdown is then uploaded
through the normal `/api/documents/upload` path for chunking + embedding +
indexing.

## Flow

1. Boot `kompile-model-staging` on port 8090.
2. Verify the SmolDocling-256M VLM model set is present under
   `~/.kompile/models/vlm/smoldocling-256m/` and stage the Qwen GGUF LLM used
   for answering the RAG question.
3. Boot `kompile-app-main` on port 8080 with a demo-local `kompile.data.dir`.
4. Load the staged Qwen model as the active `LanguageModel`.
5. **VLM extraction** — for each PDF in `sample-pdfs/`, POST to
   `/api/vlm/test/run`, poll `/api/vlm/test/status/{taskId}`, fetch
   `/api/vlm/test/results/{taskId}`, and write one markdown file per PDF to
   `var/extracted/`.
6. Upload the extracted markdown via `/api/documents/upload`.
7. Ask a question and print the RAG-augmented answer.

## Prerequisites

- Java 17 on `PATH` (or `JAVA_HOME` set).
- `jq` and `curl` on `PATH`.
- A built `kompile-cli` shaded jar:
  ```bash
  (cd kompile-cli && mvn -o -DskipTests install)
  ```
- A built `kompile-model-staging`:
  ```bash
  mvn -DskipTests -pl kompile-app/kompile-model-staging -am package
  ```
- A built `kompile-app-main` (its dependencies must be installed in the local
  Maven repo so `spring-boot:run` / fat jar launch resolves them):
  ```bash
  mvn -DskipTests -pl kompile-app/kompile-app-main -am install
  ```
- **SmolDocling-256M** already staged. The `02-stage-model.sh` script only
  verifies its presence — it does not trigger the catalog-driven VLM download.
  Stage it via the model-staging UI:
  ```
  http://localhost:8090/   -> Model Catalog -> VLM -> SmolDocling 256M -> Stage
  ```
  or set `KOMPILE_VLM_MODEL_DIR` to a directory that already contains the five
  component files listed below.
- A working CUDA GPU (nd4j-cuda-12.9 backend) with enough VRAM to hold the
  SmolDocling components (~1 GB) plus activation memory.

## Running the demo

```bash
cd kompile-vlm-demo
./run-demo.sh
# Or with a custom question:
./run-demo.sh "What does the bestiary say about dragons?"
```

`run-demo.sh` chains the seven numbered scripts and pauses briefly between
ingest and query so the asynchronous ingest task has time to commit. To stop
everything when you're done:

```bash
./scripts/stop-all.sh
```

## What each step does

- **`scripts/00-generate-app.sh`** — Regenerates the demo RAG project under
  `generated/vlm-demo/project/` using `kompile build app --preset=samediff-rag
  --backend=nd4j-cuda-12.9 --skipMavenBuild`. Same generator as the markdown
  demo; the VLM demo does not need a different app.

- **`scripts/01-start-staging.sh`** — Verbatim copy of
  `kompile-demo/scripts/01-start-staging.sh`. Boots
  `kompile-model-staging-*-exec.jar` on port 8090 with `conf/staging.yml`
  layered on via `-Dspring.config.additional-location`.

- **`scripts/02-stage-model.sh`** — Two jobs:
  1. Confirms the five SmolDocling component files are present under
     `~/.kompile/models/vlm/smoldocling-256m/`:
     `vision_encoder.onnx`, `decoder_model_merged.onnx`, `embed_tokens.onnx`,
     `tokenizer.json`, `tokenizer_config.json`.
  2. Stages the Qwen GGUF LLM via `POST /api/staging/stage` (same payload as
     the markdown demo's `02-stage-model.sh`). The LLM is needed by step 7.

- **`scripts/03-start-app.sh`** — Launches the generated `vlm-demo-*.jar` with
  `-Dkompile.data.dir=<demo-dir>/var` so AppIndexConfigService and all
  fact-sheet / anserini index paths are isolated to this demo.

- **`scripts/04-load-llm.sh`** — Same as the markdown demo. Posts
  `{"modelId":"qwen"}` to `POST /api/llm/load`.

- **`scripts/05-extract-pdfs.sh`** — **The VLM-specific step.** For each PDF
  under `sample-pdfs/`:
  1. `POST /api/vlm/test/run` multipart with
     `file=@<pdf>`, `modelId=smoldocling-256m`, `outputFormat=MARKDOWN`,
     `maxPages=<KOMPILE_VLM_MAX_PAGES>` (default 3), `pdfRenderDpi=150`,
     `maxNewTokens=2048`.
  2. Polls `GET /api/vlm/test/status/{taskId}` every few seconds until
     `COMPLETED` (or `FAILED`).
  3. `GET /api/vlm/test/results/{taskId}`, pulls `.pages[].text` with `jq`,
     and writes one markdown file per PDF to `var/extracted/<stem>.md`.
  4. Also keeps the raw result JSON at `var/extracted/<stem>.result.json` for
     inspection.

  VLM extraction runs in an isolated subprocess (`VlmTestSubprocessMain`)
  launched by `VlmTestSubprocessLauncher` — that process is the one actually
  loading the vision encoder + decoder + embed_tokens ONNX models and running
  the end-to-end pipeline on GPU. The parent kompile-app-main JVM is kept free
  of the large VLM graphs.

- **`scripts/06-ingest-extracted.sh`** — Uploads every file under
  `var/extracted/*.md` via `POST /api/documents/upload`. Identical flow to the
  markdown demo's ingest step, just pointed at the VLM-produced output
  directory instead of `sample-docs/`.

- **`scripts/07-query.sh`** — Same as the markdown demo's `06-query.sh`.
  Posts a `RagQuery` JSON to `POST /api/rag/query` and prints the response.

## Configuration knobs

| Variable                     | Default                             | Used by |
| ---------------------------- | ----------------------------------- | ------- |
| `KOMPILE_DEMO_MODEL_ID`      | `qwen`                              | 02, 04 |
| `KOMPILE_DEMO_MODEL_URL`     | Qwen3.5-0.8B-Q4_K_M.gguf on HuggingFace | 02 |
| `KOMPILE_VLM_MODEL_DIR`      | `~/.kompile/models/vlm/smoldocling-256m` | 02 |
| `KOMPILE_VLM_MODEL_ID`       | `smoldocling-256m`                  | 05 |
| `KOMPILE_VLM_OUTPUT_FORMAT`  | `MARKDOWN`                          | 05 |
| `KOMPILE_VLM_MAX_PAGES`      | `3`                                 | 05 |
| `KOMPILE_VLM_PDF_DPI`        | `150`                               | 05 |
| `KOMPILE_VLM_MAX_NEW_TOKENS` | `2048`                              | 05 |
| `KOMPILE_VLM_POLL_SECONDS`   | `3`                                 | 05 |
| `JAVA_HOME`                  | unset (uses `java` on PATH)         | 01, 03 |

The two config files under `conf/` are the only place you need to edit if you
want to change ports, the vector store backend, or chunking.

## Troubleshooting

**`02` fails with "SmolDocling-256M is not staged"**
Stage it through the model-staging UI at `http://localhost:8090/`, or set
`KOMPILE_VLM_MODEL_DIR` to a directory that already contains all five
component files.

**`05` fails with HTTP 500 from `/api/vlm/test/run`**
Check `logs/app.log` for subprocess errors. The VLM subprocess is launched via
`VlmTestSubprocessLauncher.launchTest` — look for lines starting with
`Launching VLM test subprocess for task`. A common cause is insufficient GPU
memory; lower `KOMPILE_VLM_PDF_DPI` (e.g. 96) or `KOMPILE_VLM_MAX_NEW_TOKENS`.

**`05` completes but `var/extracted/*.md` is empty**
One or more pages failed inside SmolDocling. Inspect the raw result at
`var/extracted/<stem>.result.json` — each entry in `.pages` has `success` and
`error` fields. Re-running with `KOMPILE_VLM_MAX_PAGES=1` often isolates the
failing page.

**`07` returns only retrieved chunks, no LLM answer**
The Qwen LLM is not loaded. Re-run `04-load-llm.sh`. This is the same
failure mode as the markdown demo.
