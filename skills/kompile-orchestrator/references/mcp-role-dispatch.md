# MCP role dispatch

Use this procedure for every kompile agent dispatch. Roles are runtime MCP configuration, while skills are task procedures. Both may be required.

## Authoritative rule

A role is assigned only when the dispatch call sets the live tool's actual `role` argument. Mentioning a role in prompt text is useful for traceability but is not role injection.

The live MCP schema and role registry are authoritative. Do not copy agent names, role names, model selectors, or per-subtask fields from an older example without verifying them.

## Pre-dispatch procedure

1. Inspect the live schemas for `task`, `multi_task`, or `quorum_task`.
2. Call `role_manager` with `list_roles`.
3. Inspect the selected role with `get_role` when available, especially custom roles. Confirm its system behavior, permissions, domain, and autonomy fit the packet.
4. Record the selected role and rationale in the plan and worker contract.
5. Put that exact verified role name in the dispatch call's `role` field.
6. Repeat the role in the worker prompt only to make the contract auditable.
7. If the role is missing or rejected, stop and re-plan. Never silently omit the field or fall back to an unverified generic role.

Built-in roles commonly include `architect`, `coder`, `reviewer`, `researcher`, `devops`, and `data-scientist`, but this list is not a contract. Rediscover it at dispatch time.

## Tool binding

- `task`: set the top-level `role` for the single packet.
- `multi_task`: set the top-level `role` for the dispatch group. Treat all subtasks as sharing it unless the current live schema explicitly documents per-subtask roles.
- `quorum_task`: set the top-level `role` for all voters. Use a role suited to independent judgment, commonly a verified reviewer or researcher role.

Examples matching a schema with top-level role fields:

```text
task({
  description: "Investigate parser behavior",
  role: "researcher",
  prompt: "Role: researcher\nRequired skills: ..."
})

multi_task({
  description: "Implement independent fixes",
  role: "coder",
  subtasks: [...]
})

quorum_task({
  description: "Audit compatibility risk",
  role: "reviewer",
  agents: ["codex"],
  prompt: "Review independently against the stated criteria."
})
```

The examples illustrate field placement only. Agent lists and other parameters must come from the live schema.

## One bounded architect dispatch

A lower-thinking doer can delegate a single architecture question with the actual role field:

```text
task({
  description: "Design cache invalidation",
  agent: "codex",
  role: "architect",
  prompt: "Objective: design cache invalidation.
Scope: cache module and direct callers.
Non-goals: implementation, edits, delegation, and unrelated cleanup.
Output: one decision-complete plan with exact files, tests, risks, and assumptions."
})
```

The built-in Codex architect resolves omitted model/thinking to `gpt-5.6-sol`/`xhigh`. Its runtime policy is read-only and single-task, so it cannot use mutation tools, shell, todo, or another dispatch. The calling doer remains responsible for auditing the full result and explicitly deciding whether to implement it. The task tool never auto-executes an architect plan.

## Mixed-role work

When packets require different roles:

1. Partition packets by verified role.
2. Combine only independent, same-role packets in one `multi_task`.
3. Use separate `task` or `multi_task` calls for each role group.
4. Preserve dependencies: research before implementation, implementation before review, and review before final integration when later packets depend on earlier artifacts.
5. Audit each returned packet separately before using it as input to a later phase.

Do not place `role` inside a subtask object unless the live `multi_task` schema explicitly supports it. A top-level role cannot express a mixed-role batch.

## Role selection guidance

- `architect`: decomposition, interfaces, architecture, and design constraints.
- `researcher`: evidence collection, investigation, documentation, and read-heavy discovery.
- `coder`: bounded implementation and localized fixes.
- `reviewer`: independent review, risk analysis, and post-implementation validation.
- `devops`: build, release, infrastructure, and operational workflows.
- `data-scientist`: data analysis and machine-learning workflows.
- custom role: use only after inspecting its current definition.

Choose by packet responsibilities, not by the orchestrator's title. A strong orchestrator may dispatch different phases under different roles.

## Audit requirements

The post-subagent audit must compare:

- planned role
- role found in the live registry
- actual dispatch payload's `role` value
- worker behavior and permissions against the selected role
- required skills actually read and followed

A mismatch, omitted role, unknown role, or prompt-only role assignment fails identity and scope audit. Retry with a valid dispatch or reject the result; do not integrate it silently.
