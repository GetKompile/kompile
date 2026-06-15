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

## Process and workflows

| Method | Endpoint | Description |
|--------|----------|------------|
| POST | `/api/process/diagrams/generate` | Generate process diagram |
| GET | `/api/sdx` | SDX model serving |
