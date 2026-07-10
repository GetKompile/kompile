# Distributed crawl cluster

Run crawls across a cluster of HTTP-connected **CrawlWorker** peers, with automatic
GPU → CPU → remote-peer failover. Each worker advertises its capabilities; the
orchestrator routes work to peers that can actually run it and have spare capacity.

This is opt-in. With the default config (`clusterRole=none`) a node is standalone and
incurs zero cluster overhead.

## Topology

```
            +----------------------------+
            |        Orchestrator        |   clusterRole=orchestrator
            |  - CrawlWorkerRegistry     |   externalSchedulerMode=cluster
            |  - partitions + routes     |
            +-------------+--------------+
                          | HTTP (register/heartbeat, submit, callback)
          +---------------+----------------+
          |                                |
  +-------v--------+              +--------v-------+
  |   Worker A     |              |   Worker B     |   clusterRole=worker
  |  GPU + CPU     |              |  CPU only      |   clusterOrchestratorUrl=<orchestrator>
  +----------------+              +----------------+
```

A node can be `both` (orchestrates **and** runs delegated work). Workers heartbeat their
live capabilities (backends, GPU devices + VRAM, CPU cores, supported job types, free
slots, CPU/GPU pressure) to the orchestrator every `clusterHeartbeatSeconds`; the
orchestrator evicts a worker after `clusterWorkerTimeoutSeconds` of silence.

## Configuration

All cluster settings live in the kompile JSON config
`~/.kompile/config/resource-scheduler-config.json` (edit via the kompile UI/CLI — **not**
Spring properties).

| Key | Default | Meaning |
|-----|---------|---------|
| `clusterRole` | `none` | `none` \| `orchestrator` \| `worker` \| `both` |
| `clusterOrchestratorUrl` | `""` | Orchestrator base URL a worker registers to (e.g. `http://host:8080`) |
| `clusterAdvertiseBaseUrl` | `""` | This node's externally-reachable URL (blank = auto from host + `server.port`) |
| `clusterWorkerId` | `""` | Stable worker id (blank = auto `host:port`) |
| `clusterHeartbeatSeconds` | `15` | Worker → orchestrator capability heartbeat period |
| `clusterWorkerTimeoutSeconds` | `45` | Evict a worker after this much silence |
| `clusterSupportedJobTypes` | `[ingest, vectorPopulation, graph, embedding]` | Job types this worker may run (intersected with the runner beans actually present) |
| `clusterMaxConcurrentJobs` | `4` | Max concurrent delegated jobs accepted |
| `clusterOffloadMode` | `failover` | `failover` = offload only when the host is saturated; `always` = whenever a peer exists |
| `externalSchedulerMode` | `none` | Set to `cluster` on the orchestrator to activate remote-peer delegation |
| `externalAuthToken` | `""` | Shared Bearer token for register/submit/cancel (blank = open, local/dev only) |

## Setup

### Orchestrator node

```json
{
  "clusterRole": "orchestrator",
  "externalSchedulerMode": "cluster",
  "externalAuthToken": "<shared-secret>"
}
```

`externalSchedulerMode=cluster` makes the scheduler delegate work to peers via the
`RemotePeerJobSchedulerDelegate`; `DistributedCrawlCoordinator` also uses it to scatter
crawl partitions.

### Worker node(s)

```json
{
  "clusterRole": "worker",
  "clusterOrchestratorUrl": "http://orchestrator-host:8080",
  "clusterAdvertiseBaseUrl": "http://this-worker-host:8080",
  "externalAuthToken": "<shared-secret>"
}
```

Set `clusterAdvertiseBaseUrl` whenever the worker's auto-detected address isn't reachable
from the orchestrator (containers, NAT). A worker only advertises job types it has a
runner bean for — out of the box that is `crawl` (needs a `UnifiedCrawlService`).

## Verify

- `GET /api/cluster/capabilities` on any node — that node's live capabilities.
- `GET /api/cluster/workers` on the orchestrator — the live cluster view; a freshly
  started worker should appear within one heartbeat (~15 s).
- `GET /api/scheduler/external/status` — confirms the `cluster` delegate is registered and
  whether it's currently available.

## Running a distributed crawl

`POST /api/distributed-crawl/start` with a `distribution` block. Set `workerCount: 0`
("auto") to size the fan-out to the live cluster and split sources **proportional to each
worker's free-slot capacity** (a worker with 3 free slots gets ~3× the sources of one with
1). Each partition is pinned to a distinct worker.

```json
{
  "name": "Multi-source distributed crawl",
  "sources": [
    {"label": "S3 docs", "sourceType": "S3", "pathOrUrl": "bucket/docs"},
    {"label": "SFTP reports", "sourceType": "SFTP", "pathOrUrl": "/reports"}
  ],
  "distribution": { "partitionStrategy": "ROUND_ROBIN", "workerCount": 0, "mergeResults": true },
  "graphExtraction": {},
  "vectorIndex": { "enabled": true }
}
```

A positive `workerCount` fixes the partition count instead; `PER_SOURCE` makes one
partition per source. Track progress at `GET /api/distributed-crawl/sessions/{id}`; workers
report completion to `POST /api/distributed-crawl/callback`.

> Shared storage assumption: like the Kubernetes/webhook delegates, only the job descriptor
> crosses the wire — workers run against a shared data store (the partition references, not
> the bytes). Point the cluster at shared DB/object storage.

## The failover ladder

Work falls through three tiers automatically (driven by the resource governor):

1. **Local GPU** — normal execution.
2. **Local CPU** — when worst-GPU VRAM is *sustained* `HIGH`+ **and** CPU/RAM have headroom,
   `GpuToCpuMigrationService` flips the stage's device route to CPU (the multi-backend's CPU
   side). Tunable via `gpuToCpuMigrationEnabled` / `gpuToCpuMigrateAfterSeconds` (30) /
   `gpuToCpuRestoreAfterSeconds` (20).
3. **Remote peer** — when the host is **saturated** (CPU/RAM ≥ `HIGH`, so tier 2 can't help)
   and a capable peer exists, the `cluster` delegate offers to take scheduler work and it's
   delegated over HTTP. Gated by `clusterOffloadMode=failover` (the default); `always`
   offloads whenever a peer exists. Explicit distributed crawls (the coordinator) always
   scatter, regardless of this gate.

Observe the state at `GET /api/scheduler/status` → `governor.localSaturated` and
`governor.gpuToCpuMigration`.

## Endpoint reference

| Method | Path | Side | Purpose |
|--------|------|------|---------|
| POST | `/api/cluster/workers` | orchestrator | Worker register / heartbeat |
| GET | `/api/cluster/workers` | orchestrator | Live cluster view |
| DELETE | `/api/cluster/workers/{id}` | orchestrator | Graceful deregister |
| GET | `/api/cluster/capabilities` | any | This node's capabilities |
| POST | `/api/cluster/jobs` | worker | Accept a delegated job |
| GET | `/api/cluster/jobs/{id}` | worker | Delegated-job status |
| DELETE | `/api/cluster/jobs/{id}/cancel` | worker | Cancel a delegated job |
| POST | `/api/distributed-crawl/start` | orchestrator | Start a distributed crawl |
| POST | `/api/scheduler/callback` | orchestrator | Generic job completion callback |
| GET | `/api/scheduler/status` | any | Governor + migration + saturation state |

## Security

When `externalAuthToken` is set, register/submit/cancel require
`Authorization: Bearer <token>`; with no token the endpoints are open (local/dev only).
Use the same token across the cluster and front the nodes with TLS in production.
