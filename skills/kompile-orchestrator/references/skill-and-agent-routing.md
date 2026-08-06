# Skill and agent routing

Select skills before tools or agents. Skills define the procedure; roles define broad behavior; agents execute bounded contracts; tools perform operations.

## Contents

- Skill selection for every workflow
- Role versus skill
- Delegation decision
- Required worker contract
- Context packaging
- File coordination
- Worker output
- Orchestrator follow-through

## Skill selection for every workflow

1. Inspect the live installed-skill catalog and provider skill registries.
2. Match each plan step to skills whose frontmatter explicitly covers the task.
3. Choose the smallest set that fully covers the step. Avoid overlapping skills unless each contributes a distinct required procedure.
4. Read every selected `SKILL.md` completely before task actions. Follow direct references required for the step.
5. Add selected skills to the plan step with a short reason.
6. If no skill applies, proceed with project instructions and dedicated tools; do not invent a skill.
7. When skills conflict, follow higher-priority project/user instructions and record the resolution.

A workflow may use different skills per phase. For example, investigation, implementation, testing, review, and deployment should each receive the skill appropriate to that phase.

## Role versus skill

- **Role:** broad system behavior, permissions, domain posture, and autonomy.
- **Skill:** task-specific workflow, tools, references, and validation procedure.
- **Agent/model:** execution capacity and cost/quality profile.

Specify both role and skills when both are available. A role never substitutes for reading a required skill. Verify the role with `role_manager` and pass it through the dispatch tool's actual `role` field; repeating a role name in prompt text is useful for traceability but does not inject the role.

## Delegation decision

Delegate only when a packet is concrete, bounded, independently useful, and auditable. Keep work local when decomposition, dispatch, and audit cost exceed execution cost.

Use:

- `task` for one bounded packet
- `multi_task` for independent packets that can run concurrently
- `quorum_task` for consequential uncertainty requiring independent judgments

Inspect live schemas before dispatch. Agent, role, and model fields differ across tool versions. Follow [mcp-role-dispatch.md](mcp-role-dispatch.md): bind every packet to a verified role and split mixed-role work when only a top-level `role` field is available.

## Required worker contract

Every delegated prompt must contain:

- objective and measurable acceptance criteria
- explicit scope and non-goals
- verified role name, confirmation that it is set in the actual MCP `role` field, and agent/model selection rationale
- required skill names and exact `SKILL.md` locations
- instruction to read each named skill completely before acting
- essential project and safety rules injected directly into the prompt
- allowed tools, mutations, and external side effects
- file ownership, shared read-only files, and lock requirements
- source artifacts and minimum necessary context
- required evidence and validation commands
- output format, uncertainties, stop conditions, and escalation triggers

Do not assume workers inherit the parent conversation, loaded skills, conclusions, permissions, or unstated intent.

## Context packaging

Pass raw artifacts and task-local facts. Include exact paths, symbols, failing tests, logs, or URLs. Do not leak the orchestrator's preferred conclusion when independent judgment matters.

If the worker cannot access a named skill path, require it to stop and report the missing dependency. For remote/provider agents, inject the skill's essential procedural constraints while still naming the source skill.

## File coordination

Before concurrent edits:

1. Partition files so workers have exclusive ownership.
2. Register locks with `edit_coordinator`.
3. Mark shared files read-only unless one owner is designated.
4. Require workers to report every touched file.
5. Release locks only after results are captured.
6. Audit against the latest file state before integration.

Never send two editing workers into the same file without an explicit serialization plan.

## Worker output

Require:

```text
Outcome:
Skills used and paths read:
Files/state inspected:
Files/state changed:
Evidence:
Validation and exact results:
Acceptance criteria mapping:
Uncertainties or residual risks:
Scope deviations:
Recommended audit disposition:
```

The recommendation is advisory. The orchestrator assigns the actual disposition after [post-subagent-audit.md](post-subagent-audit.md).

## Orchestrator follow-through

Track each dispatch as plan work plus a separate audit gate. After return:

1. retrieve the full result, not only the summary
2. verify the planned role matches the actual dispatch `role` field, then verify skill compliance and scope
3. perform the post-subagent audit
4. accept, correct, retry, escalate, or reject
5. integrate only accepted work
6. update the plan, locks, performance history, and final report
