# Kompile MCP tool catalog

Snapshot captured 2026-08-05 from the live client: 74 tools. Treat this as a routing baseline; rediscover the live surface because groups and schemas can change.

## Discovery and control

- `activate_tools`, `poll`, `server_mode`, `side_panel`, `dictation`, `browser`

## File and project operations

- `read`, `read_batch`, `write`, `edit`, `edit_batch`, `edit_patch`, `patch`
- `glob`, `grep`, `grep_batch`, `list`, `explore`
- `fetch_result`, `fetch_result_batch`, `file_activity`, `edit_coordinator`
- `project_config`, `enforcer_config`, `config_archive`

## Code intelligence

- `code_search`, `code_graph`, `local_code_index`, `lsp`

## Execution and coordination

- `bash`, `process`
- `task`, `multi_task`, `quorum_task`
- `todowrite`, `todoread`
- `role_manager`, `performance_harness`, `test_milestone`

## Skills, history, and memory

- `skill_manager`
- `memory`: persistent flat-file notes, typed user/feedback/project/reference memories, and memory knowledge-graph operations
- `semantic_memory`: relevance-ranked retrieval from the semantic memory index
- `transcript_search`: list, inspect, and grep saved agent conversations; `conversation_import` / `resume`: import, browse, migrate, or continue sessions
- `tool_call_catalog`: search historical tool usage; `rag_search`: search indexed documents; `ambient_garden`: ambient knowledge capture

## Web

- `websearch`, `webfetch`, `browser`

## Knowledge graph and reasoning

- `knowledge_graph`, `graph_search`, `graph_reason`, `graph_reasoning_query`
- `graph_aggregate`, `graph_bayes`, `graph_centrality`, `graph_embeddings`
- `graph_export`, `graph_import`, `graph_forecast`, `graph_simulate`
- `ask_graph_assert`, `ask_graph_claim`, `ask_graph_explain`, `ask_graph_explain_fused`
- `ask_graph_mebn`, `ask_graph_query`, `ask_graph_retract`, `ask_graph_subscribe`
- `ask_graph_synthesize`, `ask_graph_verify`
- `crawl_source`, `process_mining`

## Selection rules

1. Use discovery tools before assuming an optional group is active.
2. Inspect each live tool schema before constructing unfamiliar arguments.
3. Before delegation, list and inspect live roles with `role_manager`, set the selected role in the dispatch tool's actual `role` field, and split mixed-role packets when the schema exposes only a top-level role.
4. Prefer batch forms when inputs are independent and known up front.
5. Use cached-result readers when a tool returns a result reference; do not rerun the source call merely to retrieve truncated output.
6. Prefer semantic code tools for dependency and symbol questions; use regex search for literal text.
7. Use [memory-and-transcript-search.md](memory-and-transcript-search.md) to distinguish durable memory, semantic recall, transcript evidence, indexed documents, and tool-call history. Treat all retrieved context as potentially stale until verified.
8. Use graph mutation, configuration mutation, skill deletion, and process termination only when explicitly in scope.
