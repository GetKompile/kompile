# Multi-Task / Task Subagent Runner: Refactor Handoff

**Date:** 2026-06-19  
**Status:** Code written, NOT yet built or tested. Build + test steps below.

---

## What Was Changed and Why

### Root causes addressed (from KOMPILE_MULTITASK_DIAGNOSIS.md)

| RC | Fix applied |
|----|-------------|
| RC-1 | Eliminated shared `claudeProcess` field; each subtask spawns its own `PersistentAgentProcess` |
| RC-1 | Raised `DEFAULT_TURN_TIMEOUT_SECONDS` from 120 s to 600 s |
| RC-2 | Added `TIMED_OUT` outcome — timed-out subtasks are never counted as successes |

RC-3 (qwen/gemini command-line flags) and RC-5 (dead-agent preflight) are out of scope per the original task instruction — they affect the non-claude subprocess path only and are separate concerns.

---

## Infrastructure Reused

The fix reuses `PersistentAgentProcess` (the exact same class the project already uses for claude subagent delegation) as a **per-subtask factory** rather than a shared singleton.

Key file:
`kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/main/chat/agent/PersistentAgentProcess.java`

This class manages the stream-json protocol (`-p --input-format stream-json --output-format stream-json`), the init handshake, the stdout reader loop, and the `CountDownLatch`-based turn-completion gate. It is the canonical persistent-agent infrastructure the project already uses. The only structural change to it is:

1. Raise `DEFAULT_TURN_TIMEOUT_SECONDS` from 120 to 600 (line 60)
2. Add `TimedOutException` inner class (lines 144-156)
3. Change `sendMessage()` to always throw `TimedOutException` on timeout — including when the buffer is non-empty — instead of silently returning partial content as success (lines 173-196)

The passthrough/interactive TUI (`EmulatedPassthroughCommand`, `SubprocessAgentRunner`) was deliberately NOT used for subtasks: it carries PTY wrappers, JLine terminal, ANSI rendering, and enforcer state that are inappropriate for headless parallel subtask execution. `PersistentAgentProcess` alone is the right level.

---

## Files Changed

### 1. `PersistentAgentProcess.java`
**Path:** `kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/main/chat/agent/PersistentAgentProcess.java`

**Changes:**
- Line ~60: `DEFAULT_TURN_TIMEOUT_SECONDS` raised from 120 → 600
- Lines ~144-156: New `TimedOutException extends IOException` inner class with `getPartialOutput()` accessor
- `sendMessage()`: now always throws `TimedOutException` on timeout regardless of buffer content; prior code silently returned partial content as if the turn completed

**Why:** The silent-partial-return was the mechanism by which two 120-s timeout fires at `PersistentAgentProcess.java:155-159` were reported as "2/2 subtasks completed successfully" by StdioMultiTaskTool. The new exception forces callers to distinguish a real completion from a timeout.

---

### 2. `DirectSubagentRunnerStdio.java`
**Path:** `kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/mcp/stdio/DirectSubagentRunnerStdio.java`

**Changes:**
- Removed the shared `private volatile PersistentAgentProcess claudeProcess` field (was line 83). Replaced with a comment explaining why it must not exist.
- Replaced `runClaudeStreaming()` and `ensureClaudeProcess()` with:
  - `runClaudeStreaming()` — now creates a fresh process via `spawnFreshClaudeProcess()`, calls `sendMessage()` once, disposes the process in a `finally` block. Re-throws `TimedOutException` so callers can record `TIMED_OUT`.
  - `spawnFreshClaudeProcess(AgentConfig)` — package-private factory that builds and starts a new `PersistentAgentProcess` from the agent config's system prompt. No reuse, no caching.

**Why:** The shared `claudeProcess` + `sendLock` was the mechanism that serialised concurrent subtasks to 240 s and contaminated their context windows. Each subtask now gets its own OS process, its own stdin/stdout, and its own empty conversation context. Two subtasks running concurrently will each start at ~0 s instead of the second starting at ~120 s.

---

### 3. `StdioMultiTaskTool.java`
**Path:** `kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/mcp/stdio/StdioMultiTaskTool.java`

**Changes:**
- Added import for `PersistentAgentProcess`
- Added `SubtaskOutcome` enum (`COMPLETED`, `TIMED_OUT`, `FAILED`) before `SubtaskResult`
- Rewrote `SubtaskResult` to carry `SubtaskOutcome` instead of `boolean success`; added `isSuccess()`, `isTimedOut()`, `completed()`, `timedOut()`, `failed()` factory methods
- `runSubtask()`: added `catch (PersistentAgentProcess.TimedOutException)` → returns `SubtaskResult.timedOut(partial)` instead of `completed`
- `execute()` result loop: replaced `if (result.success)` with a switch on `result.outcome`; added `timedOut` counter; `TIMED_OUT` goes to `timedOut++` not `succeeded++`; summary/report lines distinguish TIMED_OUT from FAILED
- `ToolResult` metadata map now includes `"timedOut"` key alongside `"succeeded"` and `"failed"`
- Succeeded names list now uses `isSuccess()` instead of `result.success`

**Why:** Fixes RC-2 (false "completed successfully" on timed-out subtasks).

---

### 4. `StdioTaskTool.java`
**Path:** `kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/mcp/stdio/StdioTaskTool.java`

**Changes:**
- Added import for `PersistentAgentProcess`
- Added `catch (PersistentAgentProcess.TimedOutException)` before the existing `catch (RateLimitException)` — returns `ToolResult.error(...)` with an explicit "NOT completed — partial output" message

**Why:** The single-task `task` tool suffered the same silent-partial-success problem. Now a timed-out `task` returns an error response rather than returning partial content as success.

---

## Before / After Architecture

### Before

```
StdioMultiTaskTool.execute()
  ↓ submits N subtasks to ExecutorService
  ↓ each subtask thread calls DirectSubagentRunnerStdio.runSubagent()
      ↓ runClaudeStreaming()
          ↓ ensureClaudeProcess()  ← checks/creates ONE shared claudeProcess
          ↓ claudeProcess.sendMessage(prompt)
                          └── sendLock.lock()  ← SERIALISES all threads
                              120-second timeout fires with partial buffer
                              returns partial content silently
  ↓ runSubtask() wraps result in SubtaskResult(true, partial)
  ↓ execute() counts succeeded++ for each result.success==true
  ↓ prints "2/2 subtasks completed successfully" (FALSE)
```

### After

```
StdioMultiTaskTool.execute()
  ↓ submits N subtasks to ExecutorService (N concurrent threads)
  ↓ each subtask thread calls DirectSubagentRunnerStdio.runSubagent()
      ↓ runClaudeStreaming()
          ↓ spawnFreshClaudeProcess()  ← creates ONE NEW isolated process PER CALL
          ↓ proc.sendMessage(prompt)   ← each proc has its own sendLock — no contention
                600-second timeout
                if timeout fires → throws TimedOutException(partialContent)
                if result event fires → returns content string
          ↓ proc.close()  ← always, in finally
  ↓ runSubtask():
      normal return → SubtaskResult.completed(result)
      TimedOutException → SubtaskResult.timedOut(partialContent)
      other exception → SubtaskResult.failed(message)
  ↓ execute() switch on result.outcome:
      COMPLETED → succeeded++  (true success)
      TIMED_OUT → timedOut++   (NOT counted as success)
      FAILED → failed++
  ↓ prints "2/2 completed" only when truly complete
    OR "1/2 completed; 1 timed out (INCOMPLETE)"
```

---

## Risks and Loose Ends

### Risks

1. **One OS process per subtask.** A 5-subtask multi_task now spawns 5 claude processes simultaneously. Each claude process holds an Anthropic API connection and uses ~50-200 MB of memory. This is the correct design, but the operator should be aware of the increased resource usage on high-parallelism runs.

2. **`sendLock` still exists on each `PersistentAgentProcess`.** This lock serialises turns on a SINGLE process instance, which is correct (single-process, multi-turn). Since each subtask now owns its own instance and calls `sendMessage` exactly once, the lock is irrelevant for parallelism. It remains as a safety guard for any future multi-turn use of a single process.

3. **`TimedOutException` extends `IOException`.** This means it can be caught by a bare `catch (IOException e)` block if callers are not careful. All callers in the MCP path (`runClaudeStreaming`, `runSubtask`, `execute`) explicitly catch it before the `IOException` / `Exception` catch. Any new caller must be written with the same care.

4. **Process startup latency.** Starting a fresh claude process takes ~2-5 s (stream-json init handshake). For subtasks that run in parallel this startup time is paid in parallel, so total wall time is `max(startup_i + work_i)` not `sum(startup_i + work_i)`. This is acceptable.

5. **RC-3 (qwen/gemini flags) not fixed.** The qwen/gemini non-claude subprocess path (`executeSubagentProcess`) is unchanged. Those agents will still exit with code 1 in non-interactive mode. This is a separate fix; see RC-3 in KOMPILE_MULTITASK_DIAGNOSIS.md.

### Loose Ends

- The `incomplete` variable declared in `execute()` (`int incomplete = timedOut + failed;`) is computed but not yet used in the output. It is there for future use (e.g., an `incomplete` metadata key). It does not affect behaviour.
- The `SubtaskResult.isTimedOut()` accessor is declared but not called anywhere yet. It is there for any future caller that needs to distinguish TIMED_OUT from FAILED programmatically.

---

## Build and Test Instructions

### Modules to rebuild

Only one module needs rebuilding — the CLI main module that contains all four changed files:

```bash
cd /home/agibsonccc/Documents/GitHub/kompile
/home/agibsonccc/dev-apps/mvn/bin/mvn install -DskipTests \
  -pl kompile-cli/kompile-cli-main
```

If the build is run from a clean state, you may also need the parent:

```bash
/home/agibsonccc/dev-apps/mvn/bin/mvn install -DskipTests \
  -pl kompile-cli/kompile-cli-main,kompile-cli/kompile-cli-common
```

### Confirming true parallelism

Launch the MCP stdio server (as done when running kompile as an MCP server), then invoke the `multi_task` tool with two subtasks that each take ~30 seconds of real work:

```json
{
  "tool": "multi_task",
  "arguments": {
    "description": "parallel isolation test",
    "subtasks": [
      {
        "name": "subtask-a",
        "agent": "claude",
        "prompt": "Count to 100 slowly (wait 100ms between each number), then write the result to /tmp/subtask-a.txt. When done, print DONE-A."
      },
      {
        "name": "subtask-b",
        "agent": "claude",
        "prompt": "Count to 100 slowly (wait 100ms between each number), then write the result to /tmp/subtask-b.txt. When done, print DONE-B."
      }
    ]
  }
}
```

**Parallelism check:** Monitor `mcp-activity.log`. Both subtasks should START within 5 s of each other (parallel process startup). Total wall time should be ~30 s, NOT ~60 s (which would indicate serialisation).

**No contamination check:** The result file sections for subtask-a and subtask-b should contain independent text. Subtask-b's output must not mention anything from subtask-a's prompt.

**Correct success/timeout reporting check:**
- Run a subtask with a very short timeout (test by temporarily lowering `DEFAULT_TURN_TIMEOUT_SECONDS` to 5 in PersistentAgentProcess.java, or by writing a long-running prompt). Confirm the output says `0/2 subtasks completed successfully; 1 timed out (INCOMPLETE)` rather than `2/2 subtasks completed successfully`.
- After verifying, restore the timeout to 600 s.

### Log locations

- MCP activity log: `~/.kompile/logs/mcp-activity.log`
- Task result files: `.kompile/task-results/multi-*.md` in the working directory

---

*End of handoff document.*
