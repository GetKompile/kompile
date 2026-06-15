# REST API

The kompile-server exposes ~100+ REST endpoints. This page lists the major endpoint groups.

## Chat

| Method | Endpoint | Description |
|--------|----------|------------|
| POST | `/api/chat` | Send a chat message |
| POST | `/api/chat/stream` | Streaming chat (SSE) |

## RAG and search

| Method | Endpoint | Description |
|--------|----------|------------|
| POST | `/api/retriever/search` | Direct vector search (bypass LLM) |
| POST | `/api/graph-rag/search` | Graph-augmented retrieval |

## Crawl and ingestion

| Method | Endpoint | Description |
|--------|----------|------------|
| POST | `/api/crawlers/start` | Start a crawl job |
| GET | `/api/crawlers/jobs/{jobId}` | Job status |
| POST | `/api/crawlers/jobs/{jobId}/pause` | Pause a job |
| POST | `/api/crawlers/jobs/{jobId}/resume` | Resume a job |
| POST | `/api/crawlers/jobs/{jobId}/cancel` | Cancel a job |
| POST | `/api/unified-crawl/start` | Multi-source crawl with graph extraction |

## Agents

| Method | Endpoint | Description |
|--------|----------|------------|
| POST | `/api/agents/chat/stream` | Agent chat with streaming |
| GET | `/api/agents/passthrough/*` | Interactive agent terminal |
| GET | `/api/agents/api-config` | Agent API configurations |
| GET | `/api/react-agent` | ReAct agent configuration |
| GET | `/api/agents/diagnostic` | Agent diagnostic state |

## A2A (Agent-to-Agent)

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/.well-known/agent-card.json` | Agent card discovery |
| POST | `/a2a` | A2A task submission |
| POST | `/a2a/stream` | A2A streaming |
| GET | `/api/a2a` | A2A management |

## Knowledge graph

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/graph-extraction` | Graph extraction configuration |
| GET | `/api/graph-extraction/models` | Extraction model providers |

## Fact sheets

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/fact-sheets` | List fact sheets |
| POST | `/api/fact-sheets` | Create a fact sheet |
| POST | `/api/fact-sheets/{id}/derive` | Auto-derive facts |

## Skills and prompts

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/skills` | List skills |
| POST | `/api/skills` | Create a skill |
| GET | `/api/prompts` | List prompt templates |
| POST | `/api/prompts` | Create a template |
| POST | `/api/prompts/{name}/render` | Render a template |

## MCP

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/mcp/sse` | MCP SSE transport |
| GET | `/api/mcp` | MCP server configuration |

## Configuration

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/config/k-app` | Application config |
| POST | `/api/auto-configure/apply` | Auto-detect hardware and apply settings |
| GET | `/api/auto-configure/detect` | Preview auto-config changes |
| POST | `/api/config-archives/export` | Export config archive |
| POST | `/api/config-archives/import` | Import config archive |
| GET | `/api/nd4j/environment` | ND4J runtime config |

## Setup and projects

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/setup/status` | Setup wizard state |
| GET | `/api/projects/current` | Current project manifest |

## Guardrails and evaluation

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/guardrails` | Guardrail status |
| POST | `/api/guardrails/toggle` | Toggle a guardrail |
| GET | `/api/evaluation` | Evaluation configuration |
| GET | `/api/experiments` | Experiments |

## Code indexer

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/code-indexer` | Code indexer status |
| POST | `/api/code-indexer/index` | Trigger indexing |
| POST | `/api/code-indexer/search` | Code search |

## KV cache

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/kvcache` | List caches |
| POST | `/api/kvcache` | Create a cache |
| GET | `/api/kvcache/{id}` | Get cache details |
| DELETE | `/api/kvcache/{id}` | Delete a cache |
| GET | `/api/kvcache/{id}/stats` | Cache statistics |
| POST | `/api/kvcache/{id}/checkpoint` | Create a checkpoint |
| POST | `/api/kvcache/{id}/prefix` | Prefix cache lookup |

## VLM (Vision-Language Models)

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/vlm/models` | List VLM model sets |
| POST | `/api/vlm/models/download` | Download a model set |
| GET | `/api/vlm/config` | VLM pipeline configuration |
| POST | `/api/vlm/config` | Update pipeline config |
| POST | `/api/vlm/test` | Run VLM test workflow |
| GET | `/api/vlm-orchestration` | Orchestration state |
| POST | `/api/vlm-orchestration/reset` | Reset orchestration |

## SDX model serving

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/sdx` | List loaded models |
| POST | `/api/sdx/load` | Load a model |
| POST | `/api/sdx/unload` | Unload a model |
| POST | `/api/sdx/invoke` | Run inference |
| GET | `/api/sdx/{model}/schema` | Model input/output schema |

## Backup

| Method | Endpoint | Description |
|--------|----------|------------|
| POST | `/api/backup` | Trigger a backup |
| GET | `/api/backup` | List backups |
| GET | `/api/backup/{name}` | Download a backup |
| POST | `/api/backup/{name}/restore` | Restore from backup |
| DELETE | `/api/backup/{name}` | Delete a backup |
| POST | `/api/backup/cleanup` | Clean old backups |

## Benchmark

| Method | Endpoint | Description |
|--------|----------|------------|
| GET | `/api/benchmark` | List benchmark configs |
| POST | `/api/benchmark` | Create a benchmark config |
| POST | `/api/benchmark/run` | Run a benchmark |
| POST | `/api/benchmark/matrix` | Run a matrix benchmark |
| GET | `/api/benchmark/results` | Get benchmark results |
| POST | `/api/benchmark/apply-optimal` | Apply optimal config |

## Process and workflows

| Method | Endpoint | Description |
|--------|----------|------------|
| POST | `/api/process/diagrams/generate` | Generate process diagram (streaming) |
| POST | `/api/process/diagrams` | Create a diagram session |
| GET | `/api/process/diagrams/{id}` | Get diagram session |
| POST | `/api/process/diagrams/{id}/finalize` | Finalize a diagram |
| DELETE | `/api/process/diagrams/{id}` | Delete a diagram session |
| POST | `/api/process/diagrams/{id}/bpmn` | Render BPMN preview |
