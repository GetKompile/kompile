# Post-subagent audit

Audit every subagent result before accepting its claims, integrating its changes, or presenting it as fact. The orchestrator owns this audit and final acceptance.

## Audit inputs

Collect:

- the original worker contract, acceptance criteria, and dispatch payload including the actual `role` field
- the worker's response, evidence, uncertainties, and claimed validation
- all files or external state the worker could have changed
- relevant diffs, logs, test results, citations, and tool traces
- the current plan and results from other workers

Do not audit only the worker's summary. Reconstruct the result from primary artifacts.

## Audit procedure

1. **Identity, skills, and scope**
   - Confirm the result belongs to the assigned packet.
   - Confirm the dispatch payload used the planned, live-verified role in the actual MCP `role` field; a role mentioned only in prompt text fails this check.
   - Confirm the worker read and followed every required skill, role constraint, and injected rule.
   - Compare touched files, systems, and decisions with allowed scope and file ownership.
   - Flag hidden dependencies, unrequested refactors, generated artifacts, or external side effects.
2. **Claim verification**
   - Check each factual claim against source files, live state, or authoritative evidence.
   - Verify cited paths, symbols, line references, commands, and URLs.
   - Distinguish observation from inference and unsupported assertion.
3. **Change review**
   - Inspect the complete diff and every changed file.
   - Check correctness, edge cases, compatibility, security, privacy, data loss, and maintainability.
   - Confirm unrelated user changes were preserved.
4. **Validation replay**
   - Rerun the decisive checks independently where practical.
   - Confirm tests exercise the changed behavior and did not merely pass accidentally.
   - Inspect failures, skips, warnings, truncation, stale caches, and environment assumptions.
5. **Cross-result consistency**
   - Compare parallel-worker findings and shared assumptions.
   - Resolve contradictions from primary evidence; do not choose by majority alone.
   - Check that independently valid outputs integrate without semantic or file conflicts.
6. **Acceptance mapping**
   - Map evidence to every acceptance criterion.
   - Record unmet criteria, residual risks, and follow-up work.
   - Assign one explicit disposition.

## Dispositions

- **Accept:** all criteria are supported; changes are authorized, correct, and independently validated.
- **Accept with orchestrator fixes:** bounded defects were corrected by the orchestrator and the corrected result was revalidated.
- **Retry:** the contract remains appropriate, but execution or evidence was incomplete; issue a narrower corrective packet.
- **Escalate:** ambiguity, risk, architectural judgment, conflicting evidence, or repeated failure needs a stronger agent or quorum.
- **Reject:** the result is unsafe, out of scope, substantively wrong, or cannot be supported.

Never silently repair and mark the original worker result as accepted. Record that the orchestrator corrected it.

## Audit record

Capture a concise record:

```text
Worker packet:
Planned role / dispatched MCP role: match or mismatch + evidence
Scope compliance: pass/fail + evidence
Claims verified: pass/fail + evidence
Changes reviewed: paths or state inspected
Validation replayed: commands/checks + results
Conflicts: none or resolution
Acceptance criteria: satisfied/unmet
Residual risks:
Disposition: accept | accept-with-fixes | retry | escalate | reject
Follow-up:
```

For persisted multi-step work, keep the audit as its own plan step or completion gate. Do not mark the delegated implementation complete until its audit disposition is recorded.

## Escalation triggers

Escalate instead of repeatedly repairing when:

- the worker exceeded authority or caused external side effects
- security, privacy, destructive behavior, or data integrity is uncertain
- primary evidence contradicts the worker's central conclusion
- multiple workers disagree on a material architectural or factual point
- validation cannot be reproduced
- correction expands beyond the original packet
- the same worker or model fails the packet twice

## Final integration gate

Before integration, confirm:

- accepted artifacts are exactly those audited
- concurrent edit locks are released and no newer changes invalidate the review
- integrated tests cover cross-packet behavior
- the task plan reflects audit outcomes and remaining work
- the final response distinguishes worker claims, orchestrator verification, and residual uncertainty
