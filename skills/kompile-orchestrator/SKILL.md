---
name: kompile-orchestrator
description: Plan and orchestrate every task through the live kompile MCP tool surface and installed skill ecosystem. Use when Codex must work in a kompile-managed project, apply a planning-first workflow, obey an AGENTS.md kompile mandate, choose dedicated tools and skills, decide between local execution and agent delegation, coordinate multi-step or multi-agent work, or replace shell/file/web operations with the required kompile MCP equivalents.
---

# Kompile Orchestrator

Operate kompile as a live capability router. Discover current tools and skills before relying on the bundled snapshot, then execute through the narrowest dedicated tool.

## Core workflow

Apply this workflow to every task. Do not begin execution before completing the planning and orchestration preflight.

1. Read the project `AGENTS.md` with `mcp__kompile__read`. Treat the closest applicable instructions as authoritative.
2. Define the outcome, scope, constraints, risks, dependencies, acceptance criteria, and validation evidence. When prior decisions, preferences, investigations, or cross-session continuity could affect the task, retrieve them using [memory-and-transcript-search.md](references/memory-and-transcript-search.md) and verify retrieved claims against current primary evidence.
3. Inspect the live tool and skill surface needed for the plan. Map every step to the smallest applicable skill set and read each selected `SKILL.md` completely before task actions.
4. Choose an orchestration shape deliberately: direct execution, sequential phases, parallel independent work, cost-aware delegation, quorum review, or a hybrid.
5. Record the plan:
   - For a truly atomic task, keep a concise one-step plan and state the skill/tool choice and validation check.
   - For every multi-step task, create or update `mcp__kompile__todowrite`; identify each step's owner, required skills, output, audit, and validation; keep one item in progress and revise it when facts change.
6. Assign each step using [skill-and-agent-routing.md](references/skill-and-agent-routing.md). For every delegated step, discover and inspect a live role, then pass it through the dispatch tool's actual `role` field as specified in [mcp-role-dispatch.md](references/mcp-role-dispatch.md). Split mixed-role work into schema-compatible dispatches. Do not assume subagents inherit skills or context: name required skills and locations, inject essential constraints, define file ownership and the output contract, then apply [cost-aware-delegation.md](references/cost-aware-delegation.md).
7. Execute while maintaining the plan. Read existing files before mutation, coordinate concurrent edits, and stop or re-plan when scope, risk, or dependencies change.
8. After every subagent returns, perform the independent audit in [post-subagent-audit.md](references/post-subagent-audit.md). Do not accept, integrate, or relay worker output before the audit reaches an explicit disposition.
9. Validate every acceptance criterion, integrate only audited and accepted results, and report the outcome, changed artifacts, orchestration used, audit disposition, and evidence.

## Routing guide

- Known file: `read`; many known files: `read_batch`
- File-name discovery: `glob` or `explore`; directory listing: `list`
- Text search: `grep` or `grep_batch`
- Code semantics: `local_code_index`, `code_search`, `code_graph`, or `lsp`
- File mutation: `edit`, `edit_batch`, `edit_patch`, `patch`, or `write`
- Long-running command: `process`; short build/test/git command: `bash`
- Web lookup/fetch: `websearch` / `webfetch`; browser interaction: `browser`
- Persisted work tracking: `todowrite` / `todoread`
- Delegation: `task`, `multi_task`, or `quorum_task`
- Concurrent edits: acquire and release locks with `edit_coordinator`
- Persistent project/user knowledge: `memory`; semantic memory retrieval: `semantic_memory`
- Prior conversation evidence: `transcript_search`; session browsing/migration: `resume` / `conversation_import`
- Indexed documents: `rag_search`; relationships and structured reasoning: graph tools
- Skill operations: `skill_manager`; configuration: `project_config`, `enforcer_config`, or `config_archive`
- Historical evidence: `transcript_search`, `conversation_import`, `resume`, or `tool_call_catalog`
- Test and agent evaluation: `test_milestone` / `performance_harness`

## Hard rules

- Never substitute shell file I/O, search, or web commands for dedicated kompile tools.
- Never edit or overwrite an existing file before reading it.
- Never use `write` to modify an existing file when a focused edit or patch is sufficient.
- Never delegate through a raw subprocess.
- In multi-agent work, lock files before edits and release locks afterward.
- Do not assume the bundled catalogs are exhaustive; they are a dated baseline.

## References

- Read [default-planning.md](references/default-planning.md) before executing any workflow.
- Read [memory-and-transcript-search.md](references/memory-and-transcript-search.md) when prior decisions, preferences, investigations, or cross-session context may affect the workflow, and before persisting new memory.
- Read [skill-and-agent-routing.md](references/skill-and-agent-routing.md) when selecting skills or delegating any plan step.
- Read [tool-catalog.md](references/tool-catalog.md) when selecting among tool families or auditing the installed MCP surface.
- Read [skill-catalog.md](references/skill-catalog.md) when choosing, installing, or reconciling kompile and Codex skills.
- Read [mcp-role-dispatch.md](references/mcp-role-dispatch.md) before every agent dispatch; a role label in prompt text does not replace the MCP `role` argument.
- Read [cost-aware-delegation.md](references/cost-aware-delegation.md) before assigning work to cheaper agents from a stronger orchestrator.
- Read [post-subagent-audit.md](references/post-subagent-audit.md) after every subagent result and before integration.
