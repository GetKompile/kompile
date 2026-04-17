# kompile-demo

A self-contained, end-to-end demo of Kompile's RAG-over-markdown flow:

1. Boot `kompile-model-staging` on port 8090.
2. Stage a Qwen GGUF model from HuggingFace, exercising the new GGUF -> SameDiff
   conversion path on the staging server.
3. Boot `kompile-app-main` on port 8080, pointed at staging as its model registry.
4. Load the staged Qwen model into the running app as the active `LanguageModel`.
5. Ingest the four sample markdown files under `sample-docs/` via the documents
   upload endpoint.
6. Ask a question and print the RAG-augmented answer.

Everything is driven by plain `bash`. There is no Python, no Docker, and no
external services beyond the model file pulled from HuggingFace.

## Prerequisites

- Java 17 (the `java` binary on `PATH`, or `JAVA_HOME` set).
- Maven 3.9+ on `PATH` (used to launch `kompile-app-main` via `spring-boot:run`).
  Override with `MVN_BIN=/path/to/mvn` if needed.
- A built `kompile-model-staging` jar. From the repo root:
  ```bash
  mvn -DskipTests -pl kompile-app/kompile-model-staging -am package
  ```
- A built `kompile-app-main` (its dependencies must be installed in the local Maven
  repo so that `mvn spring-boot:run` resolves them):
  ```bash
  mvn -DskipTests -pl kompile-app/kompile-app-main -am install
  ```
- ~1 GB of free disk under `~/.kompile/models/.staging/` for the Qwen download and
  its converted SameDiff bundle.
- Network access to `https://huggingface.co` for the model download.

## Running the demo

```bash
cd kompile-demo
./run-demo.sh
# Or with a custom question:
./run-demo.sh "Which vector stores does Kompile support?"
```

`run-demo.sh` chains the six numbered scripts and pauses briefly between ingest and
query so the asynchronous ingest task has time to commit. To stop everything when
you are done:

```bash
./scripts/stop-all.sh
```

## What each step does

- **`scripts/01-start-staging.sh`** — Locates the repackaged
  `kompile-model-staging-*.jar` under `kompile-app/kompile-model-staging/target/`,
  launches it with `java -jar`, layers `conf/staging.yml` on top via
  `--spring.config.additional-location`, writes the PID to `.pids/staging.pid`, and
  blocks until `http://localhost:8090/actuator/health` reports `UP`.

- **`scripts/02-stage-model.sh`** — Posts to
  `POST http://localhost:8090/api/staging/stage` with
  `{"source":"http","repository":"<gguf url>","modelId":"qwen","type":"llm_ggml","format":"gguf"}`.
  Then polls `GET /api/staging/status/qwen` until the status is `READY`/`COMPLETED`
  or `FAILED`. The staging server downloads the file, runs the `GgmlImporter`
  conversion to SameDiff, validates it, and moves the result to its `verified/`
  directory. Override the URL with `KOMPILE_DEMO_MODEL_URL`.

- **`scripts/03-start-app.sh`** — Launches `kompile-app-main` via `mvn -o
  spring-boot:run -Dskip.ui` from the module directory, layering
  `conf/app.properties` on top so the app is configured with the embedded Anserini
  vector store and `kompile.staging.url=http://localhost:8090`. Waits for
  `http://localhost:8080/actuator/health`.

- **`scripts/04-load-llm.sh`** — Posts `{"modelId":"qwen"}` to
  `POST http://localhost:8080/api/llm/load`. This endpoint is being added in
  parallel with the demo; the payload shape mirrors the staging-side
  `LlmLoadModelRequest` DTO.

- **`scripts/05-ingest-docs.sh`** — For each `*.md` under `sample-docs/`, posts a
  multipart upload to `POST http://localhost:8080/api/documents/upload` with form
  fields `file=@<path>;type=text/markdown`, `processImmediately=true`, and
  `trackProgress=false`. Endpoint is implemented in
  `DocumentManagementController.handleFileUpload`.

- **`scripts/06-query.sh`** — Posts a JSON `RagQuery` (`query`, `useToolCalling`,
  `searchType`, `k`) to `POST http://localhost:8080/api/rag/query` and prints the
  raw response.

- **`scripts/stop-all.sh`** — Reads the pids from `.pids/`, sends SIGTERM, waits up
  to 10 seconds, then SIGKILLs anything still alive. Also sweeps any leftover
  process bound to `:8080` or `:8090`.

## Configuration knobs

Environment variables understood by the scripts:

| Variable | Default | Used by |
| -------- | ------- | ------- |
| `KOMPILE_DEMO_MODEL_ID` | `qwen` | 02, 04 |
| `KOMPILE_DEMO_MODEL_URL` | Qwen3.5-0.8B-Q4_K_M.gguf on HuggingFace | 02 |
| `MVN_BIN` | `mvn` | 03 |
| `JAVA_HOME` | unset (uses `java` on PATH) | 01 |

The two config files under `conf/` are the only place you need to edit if you want
to change ports, the vector store backend, or chunking.

## Troubleshooting

**01 fails: "no kompile-model-staging jar found"**
Build it first: `mvn -DskipTests -pl kompile-app/kompile-model-staging -am package`.
Spring Boot's `repackage` goal writes the executable jar to
`kompile-app/kompile-model-staging/target/kompile-model-staging-*.jar`.

**02 fails with HTTP error or hangs in DOWNLOADING**
Check `logs/staging.log`. The most common causes are no network, an HTTP redirect
the server cannot follow, or HuggingFace returning 401 (the demo URL does not
require auth, but a corporate proxy might inject one). You can override the URL
with a local path-style HuggingFace mirror by setting `KOMPILE_DEMO_MODEL_URL`.

**02 fails in CONVERTING**
The GGUF -> SameDiff conversion path lives in
`kompile-app/kompile-model-staging/src/main/java/ai/kompile/staging/conversion/ggml/GgmlImporter.java`.
The staging log will include the importer's error message. Try a smaller quant
(e.g. `Q4_0`) by changing the URL.

**03 hangs or fails to become healthy**
First run is slow because `kompile-app-main` initializes ND4J and loads native
libraries. The script waits up to 10 minutes. If it still fails, check `logs/app.log`
for missing beans (often a `kompile.embedding.type` or `kompile.vectorstore.type`
mismatch) or port 8080 already being in use.

**04 returns 404**
The `/api/llm/load` endpoint is implemented by a parallel branch. If you are running
this demo against a kompile-app-main that does not yet have it, the endpoint will
404 and step 06 will return without an LLM. The retrieval part of the pipeline
still works (you'll get the retrieved chunks back even without generation).

**05 returns "Uploads directory is not configured correctly"**
Make sure `kompile.uploads.path` (or whatever the active profile sets) points to a
writable directory. The default path lives under the running JVM's working
directory.

**06 returns an empty answer or only retrieved chunks**
Either the LLM is not loaded (re-run 04) or ingest is still running. Re-run 06
after a few seconds. You can also check `GET /api/documents` to see how many
documents made it into the index.
