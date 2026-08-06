# Default planning and orchestration

Plan before executing every task. Make planning proportional to complexity, but never omit the preflight decision.

## Contents

- Mandatory preflight
- Plan depth
- Orchestration decision
- Plan maintenance
- Completion gate

## Mandatory preflight

Answer these questions before the first task action:

1. What exact outcome must be delivered?
2. What is in scope and explicitly out of scope?
3. Which instructions, permissions, and safety constraints apply?
4. What evidence or state must be inspected before acting?
5. Could persistent memory or prior transcripts contain relevant decisions, preferences, failed approaches, or unresolved work? If so, retrieve them and identify how current evidence will verify them.
6. What dependencies, uncertainty, or failure modes could change the approach?
7. What objective acceptance criteria define completion?
8. What validation will prove each criterion?
9. Which execution shape produces the best verified outcome?

Do not confuse tool discovery or exploratory execution with a plan. Exploration must have a question, a bounded scope, and a decision it will inform.

## Plan depth

### Atomic task

Use a concise internal or user-visible one-step plan when the task has one obvious action, no meaningful branching, and one direct validation check.

Record:

- intended outcome
- chosen action
- validation check

Do not create busywork or delegate an atomic task merely to satisfy the planning rule.

### Multi-step task

Persist the plan with `todowrite` before implementation. Define steps as observable outcomes rather than vague activities. Keep one step in progress, complete steps promptly, and add discovered work instead of hiding it.

Each step should identify:

- owner: orchestrator, tool, or agent
- required skills and why each applies
- exact skill locations or injected instructions for delegated work
- verified MCP role and selection rationale for every agent-owned step
- inputs and allowed scope
- output artifact or decision
- acceptance criteria
- validation method
- dependencies and escalation condition

### Complex or high-risk task

Add explicit phases:

1. discovery and evidence collection
2. design or decision
3. implementation
4. independent review where warranted
5. integration and end-to-end validation
6. final synthesis and residual-risk report

Keep authorization, destructive actions, external side effects, and final acceptance with the orchestrator.

## Orchestration decision

Choose one shape deliberately:

| Shape | Use when |
|---|---|
| Direct | One agent can finish faster than delegation overhead |
| Sequential | Later work depends on earlier findings or artifacts |
| Parallel | Work packets are independent and do not contend for files or state |
| Delegated | A bounded packet can be completed and verified by a cheaper capable worker |
| Quorum | A consequential, uncertain judgment benefits from independent opinions |
| Hybrid | Discovery, implementation, and review need different shapes |

Before parallel or delegated work, verify independence, define contracts, and coordinate file ownership. Read [mcp-role-dispatch.md](mcp-role-dispatch.md) to bind each packet to a live role and partition mixed-role work, then read [cost-aware-delegation.md](cost-aware-delegation.md) for worker selection and escalation.

## Plan maintenance

Treat the plan as live state.

- Update it when evidence invalidates an assumption.
- Re-plan before expanding scope or authority.
- Mark blocked work with the exact missing dependency.
- Cancel obsolete steps rather than silently skipping them.
- Preserve completed evidence when restructuring.
- Do not declare completion while acceptance criteria remain unverified.

Pause and reassess when:

- a worker reports ambiguity or wider-than-authorized changes
- two evidence sources materially disagree
- validation fails repeatedly
- a new destructive or externally visible action becomes necessary
- concurrent work begins touching shared files or state
- actual cost exceeds the expected value of continued retries

## Completion gate

Finish only when:

- every required plan step is completed or explicitly resolved
- every subagent output has a recorded audit disposition
- only accepted or orchestrator-corrected agent outputs have been integrated
- every acceptance criterion has evidence
- validation covers integrated behavior, not only isolated worker checks
- changed artifacts and remaining risks are reported
- the persisted task list reflects the true final state
