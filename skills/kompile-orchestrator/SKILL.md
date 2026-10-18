---
name: kompile-orchestrator
description: Plan and orchestrate every task through the live kompile MCP tool surface and installed skill ecosystem. Use when Codex must work in a kompile-managed project, apply a planning-first workflow, obey an AGENTS.md kompile mandate, choose dedicated tools and skills, decide between local execution and agent delegation, coordinate multi-step or multi-agent work, or replace shell/file/web operations with the required kompile MCP equivalents.
---

# Kompile Orchestrator

Operate kompile as a live capability router. Discover current tools and skills before relying on the bundled snapshot, then execute through the narrowest dedicated tool.

## Compact preflight and context discipline

Before task actions, establish the outcome, scope, applicable instructions, evidence, risks, validation and execution shape. A small direct task needs only a concise plan; multi-step work uses `todowrite`. The detailed references below expand this preflight only when their topic applies.

- Use tool schemas and skill descriptions already visible in the current context. Do not activate a group, list every role, or reload a catalog merely to rediscover an available capability.
- Read required skills and routed references completely once per agent/context generation. Reuse an unchanged document already read in that context; reread when its contents changed, are no longer available after compaction, or a higher-priority instruction requires it. Never reuse a parent's loaded-skill state for a child.
- Keep mandatory safety rules active. Reference documents are not optional when their task-specific trigger applies; batch only the references actually needed for the chosen route.
- Maintain compact continuation state in task notes and handoffs: current objective, decisions, unresolved failures, exact test selectors/results, active process IDs and evidence references. Resume from that state or the existing compaction checkpoint before searching bounded transcript passages. Never rebuild context by dumping an entire transcript.
- Keep raw logs and full tool outputs in their durable stores. Request targeted excerpts or cached-result pages; batching calls does not by itself reduce their combined payload.

## Core workflow

Apply this workflow to every task, reusing the preflight above instead of generating a second planning narrative.

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

## Memory protocol

Memory is a retrieval input, not a write-only log. Before planning any non-trivial task, issue both a scoped `memory` recall and a `semantic_memory` search using a concise task-specific query when those tools are available. Treat returned memories as hypotheses, verify them against current files or tests, and carry only the verified, relevant findings into the plan. When a memory materially informs a decision, preserve its source or name in the working notes. If a multi-word query returns no result, retry with the most distinctive one-to-three terms and an explicit type filter before concluding that no memory exists.

After an investigation, decision, or fix produces durable project knowledge, save one compact typed memory only after the recall step. Prefer `project`, `feedback`, or `reference` with a precise name and description; do not save a transcript dump. A task is not considered memory-aware merely because it wrote a memory: it must also show a read/use path.

## Tool routing

Use dedicated Kompile tools: `read`/`read_batch`, `glob`, `list`, `grep`/`grep_batch` for file I/O; the code index first for symbols and callers; `edit`/`write` for mutations; `process` for long commands and `bash` only for permitted system commands. Use the live schemas for arguments, not a copied catalog.

Use `memory`/`semantic_memory` for durable notes, `transcript_search` for prior conversation evidence, and `diff_index` for historical agent edits. The [memory and transcript reference](references/memory-and-transcript-search.md) covers retrieval; [tool-catalog.md](references/tool-catalog.md) covers other tool families. Git access always requires the user's permission and must respect project prohibitions.

## Hard rules

- Never substitute shell file I/O, search, or web commands for dedicated kompile tools.
- Never edit or overwrite an existing file before reading it.
- Never use `write` to modify an existing file when a focused edit or patch is sufficient.
- Never delegate through a raw subprocess.
- In multi-agent work, lock files before edits and release locks afterward.
- Do not assume the bundled catalogs are exhaustive; they are a dated baseline.

## References

- Read [default-planning.md](references/default-planning.md) when complex or high-risk work needs expanded dependency, acceptance or escalation planning beyond the compact preflight.
- Read [memory-and-transcript-search.md](references/memory-and-transcript-search.md) when prior decisions, preferences, investigations, or cross-session context may affect the workflow, and before persisting new memory.
- Read [skill-and-agent-routing.md](references/skill-and-agent-routing.md) when selecting skills or delegating any plan step.
- Read [tool-catalog.md](references/tool-catalog.md) when selecting among tool families or auditing the installed MCP surface.
- Read [skill-catalog.md](references/skill-catalog.md) when choosing, installing, or reconciling kompile and Codex skills.
- Read [mcp-role-dispatch.md](references/mcp-role-dispatch.md) before every agent dispatch; a role label in prompt text does not replace the MCP `role` argument.
- Read [cost-aware-delegation.md](references/cost-aware-delegation.md) before any cross-tier delegation or escalation, including a lower-thinking doer dispatching the Codex architect role.
- Read [post-subagent-audit.md](references/post-subagent-audit.md) after every subagent result and before integration.
