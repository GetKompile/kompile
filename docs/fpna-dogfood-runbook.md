# FP&A dogfood runbook (canonical — stop rediscovering these)

Living runbook for deploying + running + verifying the FP&A crawl on the real GPU box.
Update this the moment a fact is rediscovered or a step changes.

## Hard facts (verified 2026-07-05, do NOT re-hallucinate)

| Thing | Value |
|---|---|
| Latest FP&A project | `kompile-fpna-v11` (repo root). Predecessors v2–v10 alongside. |
| FP&A source docs | `kompile-fpna-v11/data/input_documents/uploads/*.html` (14 HTML: ingestion/process-map/workflow/inbox/email AMER/EMEA/APAC + `_ja` variants). |
| App port | **8080** |
| Staging (model-staging sidecar) port | **8090** |
| App readiness endpoint | **`/api/setup/status`** → 200. There is **NO Spring Actuator** — `/actuator/health` **404س** on generated apps. `/api/scheduler/status` also 200. |
| **Crawl status endpoints** | Active jobs (rich counters): **`GET /api/unified-crawl/jobs/active`**. Per-job: `GET /api/unified-crawl/jobs/{jobId}` (sparse while running — use `/active`). Cancel: `POST /api/unified-crawl/jobs/{jobId}/cancel`. List: `GET /api/unified-crawl/jobs`. (NOT `/status/{jobId}` — 404.) Graph subprocess health: `GET http://localhost:8094/health` → `ok` (plain HTTP, NOT actuator). |
| **Stop/restart** | `kompile project service stop --all` (Stop/Status are under `service`, NOT double-registered under `project` yet — `kompile project stop` fails "Unmatched arguments" until the CLI rebuild lands). Kills by REGISTERED pid from `~/.kompile/instances/*.json`. Start supervisor blocks on `appProcess.waitFor()` (no respawn), so stopping the app cascades. |
| **ND4J env config (fp16 etc.)** | Managed JSON at **`<dataDir>/config/nd4j-environment-config.json`** (e.g. `kompile-fpna-v11/config/…`). `optimizerFp16` → `-Dnd4j.optimizer.fp16`. Edit + restart app to change subprocess flags (no rebuild). **fp16=true caused all-NaN embeddings + DSP plan-swap slowness → set false for embedding.** |
| Logs | `~/.kompile/logs/subprocesses/<id>.log` (embedding.log, graph-matrix.log, …), `~/.kompile/logs/mcp-activity.log`. App stdout → the `kompile project start` redirect (I use `/tmp/v11-start-N.log`). `kompile project logs` command built (needs native CLI rebuild to go live). **Subprocess logs are NOT rotated (embedding.log 480MB, graph-matrix.log 243MB, appended across runs) — a bug.** |
| Backends in .m2 | groupId `org.eclipse.deeplearning4j` (NOT `org.nd4j`): `nd4j-cuda-12.9`, `nd4j-native`, `nd4j-api`, all `1.0.0-SNAPSHOT`. |
| Backend select | `-Dnd4j.backend=nd4j-cuda-12.9` (maven property; default `nd4j-native`). |

## Deploy the app component (with local changes)

1. Build the runnable uber (all changed modules via `-am`, skip test *compile* — pre-existing broken enrichment tests: `GraphNode.builder().id(long)` removed in the working tree):
   ```
   mvn -o install -Dmaven.test.skip=true -Dkompile.uber -Dnd4j.backend=nd4j-cuda-12.9 -pl :kompile-app-main -am -T1C
   ```
   Produces `kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app-main-0.1.0-SNAPSHOT-exec.jar` (~886MB CUDA).
2. Refresh the component (canonical name + drop stale extraction so new code re-extracts):
   ```
   cp <exec.jar> ~/.kompile/components/kompile-app-main/0.1.0-SNAPSHOT/kompile-app-main-0.1.0-SNAPSHOT.jar
   rm -rf ~/.kompile/components/kompile-app-main/0.1.0-SNAPSHOT/.boot-inf-extracted
   ```
3. Verify bundle: `unzip -l <jar> | grep nd4j-cuda-12.9.*x86_64` (backend) and the changed module jars' timestamps.

## Run the crawl (the SELF-DESCRIBING way — no curl, no monkey-patching)

```
cd kompile-fpna-v11
kompile project start          # staging(:8090) + app(:8080). If "Component not installed: staging" → kompile install kompile-model-staging
kompile project crawl --watch  # runs the auto-ingest crawl profile/workflow, streams progress
```

## Verify (crawls are GUILTY until proven innocent — ALWAYS check logs)
- `kompile project logs` (once implemented) / else tail `~/.kompile/logs/subprocesses/*.log`.
- WS7: NO embedding subprocess whose PPID is the graph-matrix subprocess (grandchild lane). `ps -eo pid,ppid,args | grep EmbeddingSubprocessMain`.
- WS3: no `CUDA_VISIBLE_DEVICES` in any subprocess cmdline.
- Zero non-indexable dead-letters; entity/doc similarity edges present; graph-matrix RSS ~8g.

## Known issues / fixes in flight
- **[BUILD GOTCHA] scoped module tests (`mvn -pl <module> test`) resolve STALE `~/.m2` kompile jars.** Symptom: `NoSuchMethodError` for a method you just added (e.g. a fresh `VectorStore.addStoredOnlyDocuments`) — the `~/.m2` kompile jars can be months stale, and a scoped test resolves the dep from `.m2` not the reactor. Fix: `mvn install -pl <changed-upstream-module> -DskipTests` to refresh `.m2` before scoped tests, OR use the full uber build (`-pl app-main -am`) which rebuilds everything in-reactor. Also add `-Dsurefire.failIfNoSpecifiedTests=false` when using `-Dtest=` across a reactor. (The uber/deployed path is unaffected — it's a scoped-test-only trap.)
- **[BUILD RULE] vintage-check dl4j `.m2` before EVERY uber** (`stat ~/.m2/.../deeplearning4j/**/*.jar | sort | uniq -c`): if not a single build vintage (e.g. a few jars stamped today against a bulk from days ago = a dl4j rebuild in flight), do NOT build the uber — it bundles a mixed vintage = CUDA-700-class bugs. Wait for `.m2` to settle. See `feedback_dl4j_m2_vintage_check`.
- **[FIXED 07-06] weight/fact stores dropped in the graph subprocess.** `LuceneWeightStore`/`LuceneInferredFactStore` run in the graph subprocess (`embeddingDisabled=true`), where plain `vectorStore.add()` silently returns 0 — so learned PSL/MEBN weights + grounded facts never reached the queryable Lucene store (only the JSON side-channels persisted). Routed all 5 stored-only sites through `addStoredOnlyDocuments`; `GroundingStoreDurabilityTest` guards it. (Same root cause as the node-embedding durability bug; the 3 snapshot writers were fixed first.)
- **[FIXED 07-06] `kompile project start` from a non-project dir → cryptic Jackson `FileInputStream` stack.** Root: `ProjectCommandUtils.resolveProjectRoot` did `findProjectRoot(candidate).orElse(candidate)` → `store.load()` on a manifest-less dir. Now `requireExistingProjectRoot` throws a clear "No kompile project found in <dir> … cd in or pass --root" (31 load-existing call sites; init/create left as-is). **Always run `start`/`crawl` from the project dir (e.g. `kompile-fpna-v11/`), not the repo root** — resolution is cwd-walk-up.
- **[FIXED 07-06] embedding extract per-batch ~38s → ~10.3s.** The encoder read `[batch×dim]` via a per-element `getFloat` loop (`toFloatVector` has the SAME bug in this dl4j build — it loops synced `getFloat`); replaced with `data().asFloat()` (one `synchronizeHostData` + `getFloatUnsynced` bulk read). Remaining ~10s/batch = dl4j DSP exec (handoff).
- **[FIXED 07-06] subprocess logs never rotated** (`CREATE, APPEND` across every launch → graph-matrix.log ~1.5 GB, cumulative greps bogus). `DurableSubprocessLogSink` now rotates on open (fresh `<id>.log` per launch, prior kept as `.log.1`) + intra-run size cap (`-Dkompile.subprocess.log.maxBytes`, default 100 MB). NOTE: `embedding.log` is one file appended across launches — historically a grep like "N batches" is cumulative, not per-run.
- **[dl4j / external] staging model CONVERSION fails for ONNX encoders**: `bge-base-en-v1.5` + `ms-marco-MiniLM-L-6-v2` → *"Conversion failed: Another variable with the name input_ids already exists"* (dl4j ONNX-importer dup-`input_ids` bug — the #3 dl4j route). Does NOT block the crawl (the embedding subprocess loads the raw model; the failure is only the staged `.sdz` used by the inert serving lane). `smoldocling-256m` → *"Download failed: No downloader available for source: local"* (kompile staging downloader gap for `source: local`).
- **[FIXED earlier] crawl `wait-for-app` HEALTH_CHECK probed `/actuator/health` (404).** Must use `/api/setup/status` (KompileHttpClient http/KompileHttpClient.java:208).
- **[FIXED earlier] crawl step "Crawl null: missing profile"** — auto-ingest workflow crawl-step profile resolution.
- **[DONE] `kompile project logs` + `kompile project status`/doctor** — surface app+subprocess logs; report project/ports/staging/subprocesses.
