# Distributed Crawl — Resilience Hardening Plan

Status: proposed. The current distributed-crawl coordinator (see
[distributed-crawl-cluster.md](distributed-crawl-cluster.md)) is a solid **v1 data-partition coordinator**:
strong live aggregation/observability, idempotent worker lifecycle, assignment-time capacity routing
(`WorkerWeightFunction`), per-worker LLM circuit breakers, and in-worker OOM avoidance (AIMD batch sizing +
heap-pressure parallelism cuts). It is **not yet durable or self-healing**. This plan closes the four gaps,
in priority order, keeping every behavior change **additive and opt-in** (matching the existing
`cluster*` flags in `ResourceSchedulerConfig`).

## Verified gaps (today)

| Gap | Evidence |
|---|---|
| Sessions are in-memory only → coordinator restart loses all in-flight crawls | `DistributedCrawlCoordinator` sessions = `ConcurrentHashMap`; no store/boot-restore |
| Reassignment cold-restarts the partition (full `crawlRequestJson` re-sent; worker checkpoint discarded) | `reassignWorkerPartition` rebuilds metadata from scratch |
| Only **silent** loss (heartbeat-evicted / progress-stalled) is reassigned; a **reported** failure is terminal | `PartitionLossReaper.scan` only touches `WorkerStatus.RUNNING`; `handleWorkerCallback(success=false)` → `workerFailed` |
| Stall reaction is coarse — flat 300s progress timeout; GC overhead is collected but never an input | `PartitionLossReaper.lossReason` progress timeout; GC time only in telemetry/UI |
| Circuit breakers are per-JVM; a shared flaky backend trips N times; cap-scaling divides concurrency statically | `CrawlLlmDispatcher` per-instance `ConcurrentHashMap<String,CircuitBreaker>` |

---

## Phase 1 — Durable sessions + checkpoint-aware reassignment  *(highest value)*

**Goal:** a coordinator restart recovers in-flight sessions; reassignment reuses what the lost worker already
finished instead of re-crawling it.

- **Persist** a lightweight `DistributedCrawlSession` manifest (workers, partitions, per-partition
  `crawlRequestJson`, status, `externalRef`, `lastProgressAt`, `reassignmentCount`, last `sourceProgress`) as JSON
  under the versioned data dir (`data/distributed-crawl/sessions/<id>.json`). Write on **lifecycle transitions**
  (dispatch / complete / fail / reassign / status) + a throttled periodic flush (~30s) of latest snapshots —
  **not** on every ~4s progress tick. New `DistributedCrawlSessionStore` (write/load/delete); reuse the existing
  data-dir + Jackson conventions.
- **Boot reconcile** (orchestrator-only `ApplicationReadyEvent`): load non-terminal sessions; for each worker
  partition query `delegate.getJobStatus(externalRef)` — still running → resume tracking; gone → queue for
  reassignment (or fail if reassign disabled).
- **Checkpoint-aware reassignment (80/20):** workers already write graph/vector output to the **shared** stores
  scoped by `factSheetId`, and report `sourceProgress`. On reassign, drop sources the lost worker already
  *completed* from the re-dispatched `UnifiedCrawlRequest` so finished sources aren't re-crawled. (Full
  document-cursor resume is a later refinement.)
- **Config:** `clusterSessionPersistenceEnabled` — **proposed default ON** (orchestrator-scoped, pure IO safety,
  no crawl-behavior change). ⚠️ This deviates from the all-opt-in convention; flagged for your call — a
  default-off persistence layer protects nobody from the crash it exists for.
- **Files:** `DistributedCrawlSession` (toManifest/fromManifest), new `DistributedCrawlSessionStore`,
  `DistributedCrawlCoordinator` (persist hooks + boot reconcile), `reassignWorkerPartition` (drop completed
  sources), `ResourceSchedulerConfig` (flag + dir).
- **Tests:** manifest round-trip; boot reconcile (live external → resumed, dead → reassigned/failed);
  reassignment omits completed sources; persistence-disabled → no IO, in-memory behavior unchanged.
- **Risk:** hot-path IO (mitigate: transition-triggered + throttled async writes); shared-store double-write on
  resume (already idempotent via `factSheetId` + entity-resolution dedup).

## Phase 2 — Reassign on reported failure (failure symmetry)  *(small, high leverage)*

**Goal:** a worker that *reports* FAILED gets its partition retried (bounded), not just a worker that vanishes.

- In `handleWorkerCallback(success=false)`: if enabled and `reassignmentCount < max`, route to
  `reassignWorkerPartition` instead of `workerFailed`. Classify **retriable** (OOM/transient) vs **fatal**
  (bad request/auth) — only retriable retries. Add a `retriable` flag (or reason code) to
  `CrawlClusterJobRunner.Result` so the worker, which knows the local failure, drives the classification.
- **Config:** `clusterReassignOnFailure=false` (default off, sibling of `clusterReassignOnLoss`).
- **Files:** `DistributedCrawlCoordinator.handleWorkerCallback`, `CrawlClusterJobRunner.Result`.
- **Tests:** retriable → bounded reassign; fatal → immediate terminal; max-reassign → terminal.
- **Risk:** retry storms (bounded by `maxReassignments` + fatal classification).

## Phase 3 — Fast, GC-aware stall signal (congestion reaction)

**Goal:** detect a churning/stalled worker in ~seconds, not the flat 300s; feed both assignment and the reaper.

- Worker advertises `gcOverheadFraction` on `WorkerCapabilities` (Δ`GarbageCollectorMXBean.getCollectionTime` /
  wall over the heartbeat window — the worker already reads these beans).
- `WorkerWeightFunction`: GC overhead > `clusterGcOverheadHighFraction` → down-weight; > `...CriticalFraction`
  → weight 0 (treat like CRITICAL). Assignment avoids churning nodes immediately.
- `PartitionLossReaper`: a `gcStall` loss reason — sustained high GC overhead **with** no snapshot progress
  delta across K heartbeats is reaped on a short `clusterFastStallSeconds` (~60s), distinct from the 300s
  progress timeout.
- **Config:** `clusterGcOverheadHighFraction=0.3`, `clusterGcOverheadCriticalFraction=0.5`,
  `clusterFastStallSeconds=60` (fast path active only when reassignment is enabled).
- **Files:** `WorkerCapabilities` (+field), `CrawlWorkerCapabilityService.localCapabilities` (compute),
  `WorkerWeightFunction`, `PartitionLossReaper`, telemetry GC delta.
- **Tests:** weight down-weight/zero on GC overhead; reaper fast-stall path; capability advertises the fraction.
- **Risk:** GC-fraction noise (smooth over a window; conservative thresholds; advisory until sustained).

## Phase 4 — Cluster-shared backend breaker / budget  *(largest lift)*

**Goal:** a flaky/rate-limited shared LLM backend trips **once for the cluster**, not once per worker.

- **4a (cheaper, big win):** coordinator-hosted per-backend health. Workers `POST` failure/rate-limit/quota
  events to `/api/distributed-crawl/backend-health`; coordinator maintains a cluster-level `CircuitBreaker` per
  backend and publishes open/closed state (heartbeat response or `/topic/cluster/...`). `CrawlLlmDispatcher`
  consults an optional `ClusterBackendHealthClient` **advisorily** alongside its local breaker — cluster-open →
  skip the backend without independently tripping. Fail-open if the coordinator is unreachable.
- **4b (later):** replace static cap-scaling with coordinator-issued per-backend concurrency **leases** (a true
  shared rate budget) instead of `global / workerCount`.
- **Config:** `clusterSharedBackendBreakerEnabled=false`.
- **Files:** new `CoordinatorBackendHealthService` + endpoint; `CrawlLlmDispatcher` (optional advisory client);
  worker event reporting.
- **Tests:** worker failures → cluster breaker opens → peers observe open → closes after cooldown; coordinator
  down → fail-open to local breakers.
- **Risk:** coupling on the LLM hot path (keep advisory + fail-open); static cap-scaling stays as the floor.

---

## Sequencing & verification

Order is by value/effort: **1 → 2 → 3 → 4**. Phases 1–2 remove the worst data-loss/retry gaps with modest code;
3 sharpens reaction; 4 is the distributed-systems-hardest piece and is cleanly deferrable.

Per phase: unit tests + the existing 63-test distributed harness must stay green; a two-node local smoke
(orchestrator + worker, kill/restart mid-crawl) validates the integration paths that unit tests can't.

## Config summary (all new)

| Property (`ResourceSchedulerConfig`) | Default | Phase |
|---|---|---|
| `clusterSessionPersistenceEnabled` | `true` (proposed) | 1 |
| `clusterReassignOnFailure` | `false` | 2 |
| `clusterGcOverheadHighFraction` | `0.3` | 3 |
| `clusterGcOverheadCriticalFraction` | `0.5` | 3 |
| `clusterFastStallSeconds` | `60` | 3 |
| `clusterSharedBackendBreakerEnabled` | `false` | 4 |
