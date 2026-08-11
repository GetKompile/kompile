# Cost-aware agent delegation

Use a strong orchestrator for planning, risk control, review, and synthesis. Use cheaper workers for bounded tasks whose outputs can be checked cheaply and objectively.

## Responsibility split

| Strong orchestrator retains | Cheaper workers may handle |
|---|---|
| Requirement interpretation and scope | File and symbol discovery |
| Architecture and dependency decisions | Targeted research and evidence collection |
| Risk classification and authorization | Mechanical or localized implementation |
| Task decomposition and acceptance criteria | Focused tests, lint, and diagnostics |
| Conflicting-result resolution | Documentation drafts and inventories |
| Final review, integration, and user response | Independent checks against explicit criteria |

Do not delegate tasks whose failure could silently corrupt data, change production state, expose secrets, or lock in a consequential architecture without strong-agent review.

## Workflow

1. Discover available agents, roles, model selectors, and current performance data. Inspect the live schemas for `task`, `multi_task`, `quorum_task`, `role_manager`, and `performance_harness`; do not assume model names or prices.
2. Decompose the request into work packets. Make each packet independently useful, bounded to named files or questions, and free of hidden dependencies.
3. Classify each packet:
   - **Routine:** deterministic search, inventory, formatting, localized edits, or prescribed tests. Assign to the cheapest capable worker.
   - **Judgment-heavy:** ambiguous diagnosis, cross-module design, security, migrations, or broad refactors. Keep with the orchestrator or use a stronger worker.
   - **High-risk:** destructive or externally visible operations. Keep authorization and execution control with the orchestrator.
4. Select and inspect a live role for each packet with `role_manager`, record the rationale, and require the actual dispatch payload to set that role as described in [mcp-role-dispatch.md](mcp-role-dispatch.md). Do not treat a prompt label as role injection.
5. Define the contract before dispatch:
   - objective and explicit non-goals
   - permitted files, tools, and mutations
   - required evidence, commands, or citations
   - acceptance criteria and output format
   - stop conditions and escalation triggers
6. Dispatch:
   - Use `task` for one independent packet and set its top-level `role` field.
   - Use `multi_task` for genuinely independent, same-role packets and set its top-level `role` field. Split packets into separate calls when roles differ unless the live schema explicitly supports per-subtask roles.
   - Use `quorum_task` sparingly for consequential uncertainty and set its top-level `role` field for all voters.
   - Select a cheaper model only when the live schema/config supports it.
   - Use `edit_coordinator` before any concurrent file edits.
7. Audit every result centrally using [post-subagent-audit.md](post-subagent-audit.md). Reconstruct the worker's scope from the original contract, verify claims against source artifacts, inspect all changed files and diffs, and rerun decisive checks. Treat worker conclusions as untrusted proposals until the audit completes.
8. Assign an explicit audit disposition:
   - **Accept:** evidence satisfies every criterion and no unauthorized change remains.
   - **Accept with orchestrator fixes:** defects are bounded, corrected centrally, and revalidated.
   - **Retry:** the packet remains valid but execution or evidence was inadequate.
   - **Escalate:** uncertainty, risk, conflict, or repeated failure requires a stronger agent.
   - **Reject:** the result is unusable, out of scope, unsafe, or unsupported.
9. Integrate only accepted or corrected work, run end-to-end validation, record useful model/tool outcomes with `performance_harness` when appropriate, and synthesize the final response with the audit disposition.

## Worker prompt contract

Use a prompt shaped like:

```text
Objective: <one bounded outcome>
Scope: <specific files, modules, or question>
Non-goals: <what must not change>
Role: <verified live role; also set this value in the MCP dispatch `role` field>
Required skills: <skill names + exact SKILL.md locations>
Skill requirement: read each named SKILL.md completely before task actions
Injected constraints: <essential rules that must survive missing skill access>
Allowed actions: <read-only or exact mutation authority>
File ownership: <exclusive paths, shared read-only paths, lock requirements>
Required evidence: <paths, line references, diffs, tests, citations>
Acceptance criteria:
- <objective check 1>
- <objective check 2>
Output:
- findings or changes
- evidence
- validation performed
- uncertainties and escalation recommendation
Stop and report if: <risk, ambiguity, missing dependency, or scope expansion>
```

Do not give workers the intended conclusion. Give them raw artifacts and criteria so their work remains an independent signal.

## Lower-thinking doer to architect

A doer running on a lower-thinking profile (for example, Luna at `xhigh` or `max`) may delegate one bounded architecture question to Codex by selecting the built-in `architect` role:

```text
task({
  description: "Design cache invalidation",
  agent: "codex",
  role: "architect",
  prompt: "Objective: design cache invalidation for the request cache.
Scope: the cache module and its callers.
Non-goals: no code edits, migrations, or adjacent cleanup.
Return one decision-complete plan with files, tests, risks, and assumptions."
})
```

When `model` and `thinking` are omitted, Codex architect dispatches use `gpt-5.6-sol` with `xhigh` thinking. The architect is a read-only, single-task planner: it cannot mutate files, delegate again, or retain a session. The Luna doer must audit the complete result, verify the cited files and acceptance criteria, and decide whether to implement it; the plan is never auto-executed by the dispatch layer.

## Model-selection policy

Choose the cheapest agent with demonstrated ability to satisfy the packet's contract. Prefer historical evidence from `performance_harness` over reputation or model size alone.

Escalate one tier when any of these apply:

- the packet requires multi-file causal reasoning or unfamiliar architecture
- tests are nondeterministic or evidence is incomplete
- two workers disagree on a material fact
- a worker proposes a wider change than authorized
- security, privacy, data loss, compatibility, or production behavior is involved

Do not spend quorum budget when a deterministic tool, test, or stronger single reviewer can resolve the question more cheaply.

## Cost and quality controls

Track, at minimum:

- number of worker calls and retries
- model/role selected for each packet
- wall time and tool errors
- acceptance or escalation outcome
- validation coverage
- defects found during orchestrator review

Optimize total verified outcome cost, not the cheapest individual call. Repeated weak-agent retries are more expensive than one correctly escalated review.
