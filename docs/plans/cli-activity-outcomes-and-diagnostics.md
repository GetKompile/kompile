# CLI Activity: conversation outcomes and diagnostics

Status: proposed implementation plan. No runtime implementation is included in this document.

## 1. Product contract

Activity must answer five questions, without assigning a synthetic impact score:

1. What was the conversation trying to accomplish, and did it accomplish it?
2. How much elapsed time, agent work, waiting, and token usage did it consume?
3. What changed, who changed it, and which reviews, validations, and commits relate to it?
4. Where did coordination, execution, or the harness go wrong?
5. Did the agents and judge respond appropriately to observable problems?

Support current session, a selected historical session, current project, all locally known projects/conversations, and a loaded transcript. Use the same measurement definitions in all scopes. Preserve the existing live activity controls.

Non-goals: new server, external telemetry collector, automatic Git mutations, automatic process termination, automatic lock stealing, model-generated productivity scores, or a second always-running judge grading the first one.

## 2. Concrete UI

### Entry points (proposed)

- `/activity`: preserve the existing live-work entry point; add an Outcomes/History selection.
- `/activity session [id]`: summary of current or selected conversation.
- `/activity project`: current project's live and historical conversations.
- `/activity global`: all locally known conversations, with project filter.
- `/activity transcript <path>`: read-only inspection; does not resume the conversation.
- Existing `/activity agents`, inspection, close, and live process controls remain compatible.

Wire standard chat and emulated passthrough to a shared query/model layer rather than implementing two analytics paths. Imported external-agent history uses the same reader contracts with explicit availability differences.

### Conversation list

Columns: goal/title, outcome + evidence basis, elapsed time, observed blocking time, tokens, changed files / edit volume, issue count. Selectable columns on narrow terminals; session identity and outcome remain visible. Filter by project, time, agent/model, outcome, and issue type. Sort by elapsed time, waits, tokens, or latest activity. Start with 50 summary rows per page, not all transcripts loaded at once.

Enter opens the summary. Every number is drillable. Later, selecting two rows opens an aligned comparison using exactly these definitions, not another scoring model.

### Summary (illustrative data, not a measured session)

```
Goal: Fix duplicate process wake notifications
Outcome: PARTIAL | agent claimed done | user confirmation: not recorded
Criteria: 2 passed, 1 unverified  [open evidence]
Elapsed: 42m | observed blocking: 11m | unclassified: 4m
Tokens: 81k actual | parent 60k + children 18k + judge 3k
Changes: 4 files, 9 edits, +120/-45 edit volume
Attention: build budget exceeded; repeated lock conflict

Timeline | Changes | Agents & waits | Processes | Judge | Scope
```

The outcome/evidence header stays available from all six views:

| View | Row content | Enter/detail action |
|---|---|---|
| Timeline | time, actor, event, duration/state, evidence availability | source transcript/event, diff, log, message, or judgement |
| Changes | file, edit count, additions/deletions, actor, last edit | saved unified diff; next/previous edit or file; related reviews/commits |
| Agents & waits | task, parent, role/model, state, elapsed, dependency | child output plus message/lock/wait timeline |
| Processes | owner, command, state, elapsed, exit, last output, alerts | paged/searchable log, live follow when running |
| Judge | expected/invoked/skipped/error, decision, latency, effect | evaluated evidence, policy, response, correction and subsequent action |
| Scope | declared scope, observed reads/writes/modules, new areas | supporting tool calls and user scope changes |

Keyboard behavior: Up/Down select; Enter drill down; Esc return; PageUp/PageDown page; search and next/previous match. Detail views must not inject their contents or user navigation into the active agent prompt. Historical records cannot activate kill/send-message controls. Reuse existing TUI navigation bindings where these keys already have meaning.

## 3. Measurement definitions

### Goal and outcome

Represent the goal as the user's request plus versioned scope changes. Suggested acceptance criteria are visibly provisional until confirmed. Each criterion has a status (passed/failed/unverified), evidence references, observation time, and evaluated artifact/revision identity when relevant.

Display outcome separately from execution state:

- Outcome: achieved, partial, blocked, abandoned, or unverified.
- Execution: running, waiting, cleanly ended, interrupted, or unknown.
- Evidence basis: agent claim, judge opinion, linked validation, user confirmation.

A completed tool, clean session exit, successful build, commit, or judge approval alone does not establish that the whole goal was achieved. Explicit user confirmation can establish acceptance; otherwise an automatic achieved status requires all confirmed criteria to have relevant supporting evidence. Show later contradictory evidence without erasing earlier claims. User annotations append corrections rather than rewriting history.

### Time

Record start/end for instrumented operations and explicit waits. Distinguish model/tool execution, peer/lock/resource waits, and awaiting user input. An unexplained transcript gap is unclassified, not automatically idle or blocked.

Session elapsed time is wall time. Concurrent child durations must not be summed into session elapsed or blocking time. Show agent-work duration separately if useful. Blocking time requires evidence that the owner could not proceed; a child waiting while its parent works is not whole-conversation blockage. For any exclusive wall-time breakdown, use interval unions with a documented overlap policy; show overlapping actor lanes separately. Do not claim an exact critical path until dependency coverage supports it. Resumed runs have separate run intervals; report the span and observed run time rather than counting days between sessions as active work.

### Tokens, edits, and children

- Use actual provider usage when recorded; label estimates; missing stays unavailable.
- Preserve cache/input conventions per source; cached tokens must not be counted twice.
- Separate parent, child, judge/auxiliary, and unattributed usage. Include descendants once; do not add a child already included in a parent aggregate.
- Keep agent launch count separate from follow-up turns and retries on an existing child.
- Additions/deletions are recorded edit volume, not net contribution or code quality.
- Count successful observed reads separately from read attempts where results establish success. Broad search hits are not proof that every matched file was read.

## 4. Existing ingredients and precise gaps

Paths below are relative to `kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/` unless stated otherwise. Findings are source inspections, not runtime guarantees.

| Existing component | Reuse | Gap to address |
|---|---|---|
| `main/chat/StandardChatActivityPanel` | selectable work, tool details, subagent output, process output | currently live/in-memory; no historical impact model |
| `main/chat/activity/AgentActivitySnapshotService` and `ProjectActivityController` | bounded background refresh, project coordination view | active agents and five-minute tool window are not historical totals |
| `main/chat/ChatSessionMetrics`, `ChatStatsCommand` | token/turn/tool/compaction counters and provider usage readers | presence-aware normalization, durable updates, resume continuity |
| `main/chat/harness/ModelPerformanceRecord`, `ModelPerformanceStore` | task prompt/outcome/reason, assertions, per-session judge records | inspect producer coverage; do not assume fields are populated on every route |
| `main/chat/context/ConversationLedger` | stable event sequence, latest compaction checkpoint | checkpoint replacement loses prior compaction markers; retain all occurrences |
| `main/chat/tools/DiffIndexTool` / existing `DiffIndexService` | session/file history, old/new/unified diff and edit volume | query without refresh on redraw; verify native Kompile edit ingestion and success semantics |
| `main/chat/ToolCallRecord` | source/session/project/tool IDs, inputs, timestamps, errors, duration | correlate operation start/end, actor and source evidence where absent |
| `mcp/stdio/TaskRecord`, `TaskRegistry` | external task hierarchy and persisted results | prunable records and non-universal direct-chat coverage |
| `main/chat/agent/DirectSubagentRunner`, `ChatRepl` lifecycle listeners | actual child lifecycle and stream responses | persistent unique child identity, usage and output association; repeated same-role children cannot share identity |
| `main/coordination/CoordinationStateManager`, `CoordinationMessage`, `EditLockEntry`, `ActivityWaitRegistry` | messages, reply IDs, lock ownership, waits | acknowledgements delete mailbox files; historical lifecycle and wake observations need retention |
| `main/chat/tools/BackgroundProcessManager` | launch/state/log reading | process map is in memory; cleanup deletes logs; preserve historical metadata |
| `main/chat/SessionRegistry`, `SessionLifecycleManager` | clean exit and dead-PID recovery | disappearance is interruption evidence, not a crash-cause diagnosis |
| `main/chat/enforcer/JudgementRecord`, `JudgementLog` | persisted decisions, corrections, latency, raw response excerpts | evaluated identity/policy and expected invocation links; truncation and silent logging failure must be visible |
| `main/chat/tools/ConversationImportTool`, `ChatHistory` | transcript import and browsing | retain source identity/availability; text-only import cannot recreate missing telemetry |
| `kompile-cli-common/.../logs/AgentLogReader`, subprocess metadata | existing persisted log readers | verify conversation joins; numeric server session IDs are not transcript UUIDs |

Do not replace these with a second diff database or a new coordination service.

## 5. Minimal storage and correlation design

Add a small per-conversation activity sidecar under the existing conversations session directory. Proposed logical artifacts:

- `activity-summary.json`: versioned, atomically replaced, cheap list-view summary, identity aliases, goal/outcome annotations and coverage.
- `activity-events.jsonl`: append-only lifecycle evidence not already reliably retained elsewhere.

These are new artifacts, not replacements for metrics, context ledgers, judgements, task registries or payload logs. Use shared path resolution rather than hardcoding a second home directory. One bounded reader/service in the existing activity package composes them with current stores.

Event envelope: schema version, event ID, conversation key, run ID, actor ID, parent actor ID, writer sequence, timestamp, event type, operation ID, related IDs, source reference, small typed attributes. Use source namespace plus original session ID for imported identities; keep transcript, coordination, task and provider IDs as explicit aliases, not interchangeable strings.

Same-operation start/end pairs share an operation ID. Messages share message/reply IDs; locks share lock IDs; processes share run identity, not PID alone. Use monotonic duration within a process, wall time for display, and causal links across processes. Concurrent timestamps do not establish causality.

Essential additional events:

- session/run start and clean end; operation start/end with result reference;
- child launch/end and usage reference; process launch/end/output-location;
- wait start/end/reason/owner; lock conflict/acquire/release;
- message sent/available/acknowledged/replied; wake requested/delivered/failed;
- compaction committed: occurrence sequence/turn, coverage boundary, before/after context tokens, reason;
- judge eligibility/invocation/skip/error/decision/correction dispatch and observed application;
- goal/scope/outcome annotations and evidence links.

Store references to full payloads, not duplicate raw logs or all streaming tokens. Persist at lifecycle boundaries and bounded periodic summary updates, not just session shutdown. Use an existing serialized append mechanism if available; otherwise use a short cross-process append lock, never the coordinator's long-held work lock. A partial final JSONL record must not hide earlier valid records. Recording failure must raise a visible diagnostics-health warning without breaking the task or silently reporting complete coverage.

Read precedence: use linked primary events for detail and compatible producer summaries for totals; do not sum multiple representations of the same activity. Live state overlays persisted records by identity. Coverage per section is complete/partial/unavailable/error, with earliest retained time and truncation/expiry markers. Inconsistent totals are surfaced, not silently reconciled into invented values.

Diff index readiness is explicit: read-only queries for browsing; a requested/background incremental refresh when needed, never per redraw. Historical payloads load on demand. Reuse global conversation inventory and known project registrations; do not recursively crawl arbitrary directories to populate Global.

## 6. Coordination diagnostics

For each dependency show owner, waiter, resource/file, wait reason, start/end, related messages and observed progress. Separate legitimate serialized resource admission from peer/lock waits and notification delivery failures.

Retain acknowledgement history independently from pending mailbox state. Delivered-to-host, made-available-to-agent, acknowledged and replied are distinct observations. External agent turns may not be wakeable; report that capability rather than calling an undelivered wake a deadlock.

Detect possible cycles only from explicit active wait edges. For example A waits for B's lock while B waits for A's task. Label as a possible cycle; show the edges. Repeated failed lock requests alone establish conflicts, not continuous whole-task blocking. Show other work performed during a wait. This instrumentation must not change scheduling, steal locks, or automatically stop peers.

## 7. Unhealthy-condition responsiveness

Initial implementation is deterministic and configurable. Every finding has a rule ID, trigger values, first observed time, evidence references, severity, coverage and confirmed/dismissed/unresolved annotation.

A condition and an agent's response are separate records:

1. condition became observable;
2. warning was made available to the owner;
3. owner had an opportunity to act;
4. owner investigated, justified continuation, fixed, stopped, or did not respond in the observed interval.

Do not label an agent negligent merely because a process ran slowly while it was blocked inside a tool. Show notification failure or unavailable observation instead.

| Rule | Trigger | Required guard/detail |
|---|---|---|
| `duration-budget-exceeded` | operation exceeds an explicit user/project budget | distinguish execution from resource-admission time; duration alone is not failure |
| `no-new-output` | configured silence interval exceeded | call it no new output, not proof of no progress; silence may be normal |
| `repeat-polling` | configurable count of polls after warning without related diagnostic evidence | identify this process/wait; unrelated tool calls do not qualify as investigation |
| `repeat-failure` | same observed failure repeats without relevant intervening change/diagnosis | retries can be justified; store reason; signature matching is heuristic |
| `unaddressed-condition` | warning available plus configured response grace and observed opportunity, without response | incomplete actor trace means unknown response, not no response |
| `wait-cycle` | explicit active dependency cycle | possible cycle, evidence links, no automatic intervention |

Start with explicit budgets, not one universal build timeout. Later, offer a historical baseline only with at least five comparable successful runs: same project, command/target, relevant flags, build kind, concurrency and environment fingerprint. Separate cold/full builds from incremental builds. A proposed advisory default is above twice the median AND at least two minutes above it; expose and version the setting. Show sample count and range. Insufficient comparability means no baseline. A dynamic baseline must never override an explicit budget or silently normalize a new regression.

Investigation evidence can include relevant log inspection, process/thread dump, resource check, targeted diagnosis or an explicit continuation rationale. Each is an observed action, not proof of a correct diagnosis. Distinguish merely informing the user from investigating. No automatic kill action.

## 8. Reviewer, scope and judge analysis

### Reviewer evidence

Attach reviews to the assignment, acceptance criteria and exact file revisions/diff IDs reviewed. Show requested versus inspected files, time/tokens, findings with evidence, and subsequent disposition. Preserve review comments when later edits make them stale.

First-release flags: finding lacks a usable source reference; reviewed revision became stale; claimed validation has no linked result; required review target has no observed inspection. Phrase these as evidence gaps, not proof of an incompetent reviewer. "No bugs found" is not a failure. Later test failure contradicts a review only when linked to the reviewed criterion/revision; otherwise it is a possible relationship for inspection. User marks useful/incorrect/unresolved findings with reasons. Do not equate missing read calls with lack of knowledge when the reviewed diff was supplied in the prompt.

### Scope and tangents

Show declared files/modules and observed read/write sets separately. Extra reads are exploration, not inherently drift. Flag writes outside confirmed scope; show scope expansions with user approval. Track repeated reads and newly visited areas as descriptive counts. Semantic tangent detection is deferred or explicit on-demand analysis, labelled as an inference with evidence and user dismissal. No continuous LLM scorer.

### Judge observability and debugging (not a trusted evaluator)

Working assumption from user reports: the current judge is buggy. It often vaguely accepts or times out, and can issue overly conservative or nonsensical blocks depending on task wording, including blocking the first research action. Debugging the judge itself is in scope. These reports define reproduction targets, not a diagnosed root cause. Neither existing decisions nor configured policy establish correct behavior.

Primary measurements are observable events:

- Toggles: enabled/disabled/paused/resumed, timestamp, initiating actor, source control, requested scope (global/session/tool), previous and effective state, policy/config version, and reason only if supplied. Record initial effective state separately from transitions. Show off intervals and the nearby blocks/timeouts; proximity alone does not prove why the user turned it off.
- Actual blocks: exact attempted action/tool and arguments or safe retained reference, task wording and stage (including initial research), judge response/reason, enforcing component, final enforcement action, time lost to checks/retries, and subsequent retry/rewording/override/abandonment. Distinguish judge recommendation from an action actually prevented. Permission denials from other components must not count as judge blocks.
- Operational results: invoked, returned allow, returned block, timeout, parse/transport error, skipped and unavailable. Show what the harness actually did after failure (allowed, blocked, retried, or unknown). A timeout followed by fail-open is not a judge approval. An allow decision does not prove useful evaluation.

Proposed first summary: effective judge state; off-toggle count and observed off duration; prevented-action count; timeout/error count; observed judge latency. Counts carry coverage labels. No effectiveness score, missed-block rate, or automatic claim about what should have been blocked.

Link layered JUDGE_* and RESULT records with one judgement/invocation ID so one check is not counted several times. Display unavailable evidence and truncated response/input explicitly. Policy/input hashes alone are insufficient for reproduction; retain a safe source reference or mark reproduction unavailable. If logs only report ACCEPTED without the underlying path, show the enforcement outcome with unknown decision provenance.

Mechanical coverage can compare recorded invocation triggers with the effective implementation/configuration, but is labelled invocation coverage, not semantic correctness or safety coverage. Debug inspection must check whether those triggers and configuration are themselves wrong.

Debugging sequence:

1. Capture representative block, toggle and timeout incidents and their exact available inputs, task stage, model, effective policy and enforcement result.
2. Trace prompt construction, context selection, judge routing/tool policy, decision parsing, deadlines/retries, fallback handling and toggle propagation. Do not assume task wording is the only cause or fix by blindly relaxing all rules.
3. Add regression cases for each reproduced failure. Test first read-only research before implementation, semantically equivalent task rewordings, vague responses, malformed responses, timeout fallback and global/session off-toggle behavior. A rejected research action is only labelled incorrect when its actual permissions and independently reviewed expected behavior support that conclusion.
4. Validate control flow with fake-backend tests; evaluate model judgment on a small explicitly user-reviewed case set. Real-model reproductions are opt-in, keep original evidence, report variability and never become automatic ground truth.

False-positive/incorrect-block annotations require user review or independent agreed evidence. What the judge should have blocked is deferred to individually labelled cases, not inferred from general conversation history. Judge fixes have their own verified regression gates; the Activity browser must remain useful while the judge is broken or disabled.

## 9. Commit correlation

Many-to-many links: conversation <-> recorded edit <-> commit. Link fields: repository identity, commit hash, diff IDs, conversation keys, basis, evidence and optional user confirmation.

Basis labels:

- explicit: recorded association or user-provided commit ID;
- patch match: supported overlap/matching hunks, not a claim of sole authorship;
- candidate: file/time overlap only, requiring confirmation.

Never assign an entire mixed-contribution commit to one conversation merely because it happened during that session. Preserve unmatched edits. Squash/rebase/amend can change hashes; mark stale links and offer reconciliation, never silently reassign them. Cherry-picks can yield multiple commit associations for the same change.

The user's no-Git rule remains in force. Implement explicit metadata associations without Git commands first. Automatic read-only local history/patch inspection requires explicit authorization; no commit/reset/checkout/staging/push operations belong in this feature. Do not gate the other milestones on this clarification.

## 10. Implementation slices and ownership

One integration owner maintains identity/data contracts. Parallelize only independent packets after those contracts are stable; coordinate file locks. All new component names below are proposed, not existing APIs.

### A. First usable vertical slice: outcome plus historical evidence browser

- Add a presence-aware `ConversationActivityService` and immutable summary/detail models in `main/chat/activity`.
- Read existing metrics, outcome records, transcripts and indexed diffs; expose retained process/judge evidence with partial-coverage labels. Prioritize judge-off transitions and actual blocked actions where retained; do not infer absent toggles from a lack of judge calls.
- Add session/project/global/transcript selection and shared detail navigation. Do not infer anomaly verdicts yet.
- Add user-confirmed goal/criteria/outcome annotations and explicit commit metadata links.
- UI integration: `ChatRepl`, `ChatCommandRouter`, `ChatCompleter`, `StandardChatActivityPanel`, `EmulatedPassthroughCommand`.
- Done when a current session and historical transcript can show the same known summary evidence and open an indexed diff, without reading every payload or resuming work. Missing historical data stays unavailable.

### B. Complete future-session recording and crash recovery

- Implement lifecycle sidecar writer/reader and incremental summaries before expanding analytics.
- Wire ledger compaction commit, direct/external child lifecycle and usage, process launch/end, and run start/end. Verify actual source producer paths rather than assuming TaskRegistry is universal.
- Persist message/lock/wait/wake lifecycle without altering coordinator scheduling. Record judge initial effective state and toggle transitions, including scope and origin; retain actual enforcement actions separately from judge responses and fallback outcomes.
- Related boundaries: `ConversationLedger`, `ChatSessionMetrics`, `DirectSubagentRunner`, `ChatRepl`, `McpSessionTracker`/task execution, `BackgroundProcessManager`, `CoordinationStateManager`, `ActivityWaitRegistry`, session lifecycle and import paths.
- Done when restart/resume, multiple same-role children, multiple compactions, completed processes and acknowledged messages remain accurately browseable. Unexpected termination must leave useful last-known state with no fabricated cause.

### C. Judge debugging and evidence-backed diagnostics

- Expose toggle/off-interval, actual block and timeout/error timelines first; correlate effective policy/input/result/enforcement links in existing judge/harness producers.
- Investigate representative user-reported judge failures using the sequence in section 8. Fix reproduced prompt/context/routing/parsing/timeout/toggle defects in bounded changes with independent regression tests. This is actual judge debugging, not just a metrics UI.
- Add wait graphs, configurable budgets, response timelines, reviewer evidence gaps and scope-write checks.
- Pure diagnostic rule evaluation over persisted snapshots; no background model calls or automatic missed-block judgments.
- Done when toggle scopes and actual blocks can be traced, timeout fallback is never mislabeled judge approval, and reproduced initial-research/wording-sensitive failures have reviewed regression evidence. Unreproduced reports remain explicitly unresolved. Mechanical invocation coverage must not be presented as judge correctness.

### D. Comparison and optional Git-assisted matching

- Two-conversation summary comparison, same definitions and coverage labels.
- Historical duration baselines only after comparable-run data exists.
- If authorized, add read-only commit patch matching and labelled candidates; otherwise retain explicit links.
- Done when child/import deduplication holds globally and mixed-contribution commits retain multiple associations.

Do not estimate calendar delivery until A's reader joins and UI integration are tested. Keep each slice independently useful and reviewable. Recording changes can begin alongside A after the identity contract is fixed, but must not block A on every later heuristic.

## 11. Acceptance test plan

Use existing activity renderer/controller/panel tests, `ChatSessionMetricsTest`, `ConversationLedgerTest`, task registry tests, and judge tests as extension points. Add isolated fixture-backed tests for the new reader, recorder, rules and navigation. No real build slowdown, model call or deadlock is required to test the UI/rules.

End-to-end synthetic fixture with a fake clock:

1. User confirms three criteria and two allowed modules; parent launches two children with the same role.
2. One child holds a file lock. Another conflicts, sends a message, does other work, and later receives a reply. A separate pair has explicit circular wait edges.
3. A build exceeds its configured budget. The owner sees a warning and repeatedly polls. Compare with a second run that inspects the relevant log and records a justified continuation.
4. Two compactions occur at different turns with different summarized coverage boundaries.
5. Several successful edits and one failed edit are recorded. A review references an earlier file revision and a claimed test without evidence.
6. Judge fixtures cover initial effective state, global/session off/on toggles, a first-research block, reworded attempts, explicit allow, vague/malformed responses, timeout with fail-open and fail-closed outcomes, and an unrelated permission denial. Independently labelled cases define expected behavior; judge output does not.
7. Simulate abrupt session termination with an in-flight operation and a partial final event line. Reload through a fresh reader.
8. Attach a commit ID explicitly to edits from two conversations; import a duplicate source transcript; inspect session, project and global views.

Required assertions:

- Outcome remains partial/unverified until criteria evidence or user confirmation supports achievement.
- Counts and usage agree before/after reload; cache, children, resumes and imported copies are not double-counted.
- Both compaction occurrence positions survive; jumping opens the right source event. Imported timestamps invented during legacy conversion are not presented as original occurrence times.
- Wait durations distinguish other productive work from owner blockage. Acknowledged messages remain in history, not in the pending inbox.
- Unhealthy-condition flags identify thresholds, warning visibility and observed response; investigated/justified continuation is not labelled ignored.
- Crash view shows the last valid event and incomplete operation; it does not claim OOM without evidence.
- Process rows survive cleanup; expired/missing logs show a reason, not a blank successful result.
- Diff navigation uses recorded content, excludes failed/dry-run changes from successful totals, and preserves provider/source uncertainty.
- Review and judge details retain evaluated revision/policy, layered checks are deduplicated, and missing logs raise coverage warnings.
- Judge initial off state is not counted as an off toggle; effective-state intervals and scope changes survive restart. Historical missing state remains unknown.
- Actual prevented actions are distinct from block recommendations and unrelated permission denials. Timeout fallback allows are not approvals. Nearby toggles/blocks are correlations, not inferred user motives.
- First-research and wording-variant regressions use independently agreed expectations; general missed-block rates and judge effectiveness claims are not generated.
- No automatic Git operation, lock theft, process kill, or resumed chat occurs while browsing.
- Terminal control characters are sanitized, summaries redact sensitive arguments, payload references are validated against permitted roots, and no imported path is executed.
- Paging performs bounded metadata/detail reads. A 10,000-conversation fixture never loads all transcript/log/diff payloads to display 50 rows; unchanged UI refreshes do not re-index history.

Future implementation validation command (repo root; not executed for this plan): use `/home/agibsonccc/dev-apps/mvn/bin/mvn`, preferably an offline reactor `-pl :kompile-cli-main -am` focused test run with `-Dkompile.backend=cpu` and `-Dsurefire.failIfNoSpecifiedTests=false`. Select exact new/existing tests once their boundaries are finalized. Do not use clean. Scope-only tests require freshly installed changed dependencies; do not misdiagnose stale installed JARs as a source regression.

## 12. Decisions and remaining clarification

Settled defaults: no impact score, facts before heuristics, investigation before termination, explicit unknowns, existing stores reused, no continuous extra judge, and no Git commands under current authorization.

Open authorization only: may commit correlation perform read-only Git history/patch inspection? This does not block explicit associations or milestones A-C.

Historical limit: events never captured, deleted logs, missing child usage and prior overwritten compaction checkpoints cannot be reconstructed reliably. The browser must be useful on existing data while clearly distinguishing it from fully instrumented future sessions.
