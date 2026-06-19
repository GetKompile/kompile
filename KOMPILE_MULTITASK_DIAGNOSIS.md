# Kompile MCP `multi_task` / `task` Reliability: Root-Cause Diagnosis

**Investigation date:** 2026-06-19  
**Source files examined:** All under `kompile-cli/kompile-cli-main/src/main/java/`  
**Runtime artifacts:** `.kompile/task-results/*.md`, `~/.kompile/logs/mcp-activity.log`

---

## Executive Summary

Four root causes are confirmed with file:line evidence. One hypothesis (E: worktree-isolated edits) is refuted for the June-19 runs; the observed worktree changes are from a prior agent session. One hypothesis (F: no preflight for dead agents) is confirmed.

---

## Confirmed Root Causes

### RC-1 (Hypothesis B): Shared `PersistentAgentProcess` serialises parallel claude subtasks → 2 × 120 s = 240 s timeout, partial output silently reported as "completed successfully"

**Files and lines:**

| File | Line | Issue |
|------|------|-------|
| `DirectSubagentRunnerStdio.java` | 83 | `private volatile PersistentAgentProcess claudeProcess;` — one instance, shared by all threads in the same MCP session |
| `DirectSubagentRunnerStdio.java` | 452–480 | `runClaudeStreaming()` calls `ensureClaudeProcess()` then `claudeProcess.sendMessage(prompt)` — all callers share the same object |
| `PersistentAgentProcess.java` | 69 | `private final ReentrantLock sendLock = new ReentrantLock();` |
| `PersistentAgentProcess.java` | 143–163 | `sendLock.lock()` at top of `sendMessage()` → only one thread can run at a time |
| `PersistentAgentProcess.java` | 55 | `DEFAULT_TURN_TIMEOUT_SECONDS = 120` |
| `PersistentAgentProcess.java` | 155–159 | `turnComplete.await(timeoutSeconds, ...)` → if `!completed && result.isEmpty()` → `IOException`. **If the buffer is non-empty, it returns silently.** |

**Mechanism:** `StdioMultiTaskTool.execute()` creates one `ExecutorService` with `totalInstances` threads (line 189) and submits all subtasks to it concurrently. Each subtask thread calls `runSubtask()` → `runClaudeStreaming()` → `claudeProcess.sendMessage()`. Because `sendMessage()` acquires `sendLock`, only one thread executes at a time. The two claude subtasks run sequentially, each blocked for up to 120 s. Both read partial output before `turnComplete.await(120)` fires; because the buffer is non-empty, no exception is raised and `SubtaskResult(true, partialContent)` is returned. The outer `StdioMultiTaskTool` increments `succeeded++` for each `result.success == true`. Final report: "2/2 subtasks completed successfully."

**Observed timing:** `mcp-activity.log` shows two runs at exactly 240 689 ms and 240 002 ms — within milliseconds of 2 × 120 000 ms.

**Observed content:** The results file contains only mid-task reasoning ("still reading files"), consistent with the 120-s cutoff mid-exploration.

**Fix:**

1. Give each subtask its **own** `PersistentAgentProcess` instance rather than sharing via the `claudeProcess` field. Refactor `runClaudeStreaming()` to accept an agent-scoped process, or construct one per `runSubtask()` call. The cost is one extra claude process per subtask; this is the correct design for parallel subtasks.
2. Increase `DEFAULT_TURN_TIMEOUT_SECONDS` from 120 s to at least 600 s (10 min), matching the outer `sf.future.get(10, TimeUnit.MINUTES)` timeout in `StdioMultiTaskTool`.
3. In `sendMessage()`, treat a timeout with non-empty buffer as a **partial result** and surface it as an error or a distinct `TIMED_OUT` status rather than silently returning it as success.

---

### RC-2 (Hypothesis A): "Success" is declared on any non-exception return — no task-completion gating

**Files and lines:**

| File | Line | Issue |
|------|------|-------|
| `StdioMultiTaskTool.java` | 354–369 | `runSubtask()`: calls `subagentRunner.runSubagent()`, if no exception → `return new SubtaskResult(true, result)` |
| `StdioMultiTaskTool.java` | 245–267 | `execute()`: `if (result.success) { succeeded++; }` — only failure modes are `TimeoutException`, `ExecutionException`, or explicit `SubtaskResult(false, ...)` |
| `StdioMultiTaskTool.java` | 272–273 | Final summary: `.append(succeeded).append("/").append(subtasks.size()).append(" subtasks completed successfully.")` — this count is derived purely from whether an exception was thrown, never from whether the agent actually performed the requested work |

**Mechanism:** `runSubagent()` in `DirectSubagentRunnerStdio` returns any non-null String to be treated as success. For change tasks, there is no post-hoc check: no `git diff`, no file modification timestamp comparison, no assertion that the prompt's target files changed. The string "still reading files" is as valid a "success" return as a well-formed edit summary.

**Fix:** For change-type tasks, add an optional post-run verification step:
- If the tool invocation included a `verify_files` array (list of files expected to be modified), check `Files.getLastModifiedTime()` of each after the subtask and flag `SubtaskResult(false, ...)` if none changed.
- At minimum, add a distinct `INCOMPLETE` field to `SubtaskResult` (separate from `success`) that is set whenever `sendMessage()` returned due to a timeout rather than a `result` event.

---

### RC-3 (Hypothesis C): Non-claude subagents (`qwen`, `gemini`, `opencode`) are launched without the one-shot prompt flag

**Files and lines:**

| File | Line | Issue |
|------|------|-------|
| `DirectSubagentRunnerStdio.java` | 444–446 | `buildAgentCommand()` returns `new ArrayList<>(List.of(binary))` — no flags, no prompt argument |
| `DirectSubagentRunnerStdio.java` | 299–309 | The prompt is piped via `stdinWriter` to the subprocess stdin |

**What each agent actually needs:**

| Agent | Correct one-shot invocation | What is currently launched |
|-------|----------------------------|---------------------------|
| qwen  | `qwen "<prompt>"` or `qwen -p "<prompt>"` | `qwen` (interactive mode) |
| gemini | `gemini -p "<prompt>"` | `gemini` (interactive mode) |
| opencode | stdin-piped approach may work, but unconfirmed | `opencode` |

**Observed evidence:** `mcp-activity.log` line dated 2026-06-19 06:29:37: `task:qwen — Subagent 'qwen' exited with code 1 after 7.6s`. Exit code 1 after 7.6 s means qwen launched in interactive mode, found no TTY, and aborted. The `qwen --help` output confirms: "Positional prompt. Defaults to one-shot; use -i/--prompt-interactive for interactive." — sending text via stdin alone does not trigger one-shot mode.

**MCP tool injection for qwen:** `McpToolInjection.injectForQwen()` correctly writes `.qwen/settings.json`. However this is irrelevant when qwen exits immediately (code 1) before reading any config.

**Note on claude subagents:** For `claude`, `PersistentAgentProcess.buildCommand()` correctly adds `-p --input-format stream-json --output-format stream-json`, so the protocol works. The subagent's log message "kompile MCP tools aren't available (ToolSearch can't find them)" does NOT mean the MCP server is unreachable — it means the `ToolSearch` deferred-tool mechanism of the parent SDK is not available in the subagent's context. The subagent's built-in `Read/Edit/Bash` tools are available and functional; the agent correctly fell back to them.

**Fix:** Add agent-specific command-line flags to `buildAgentCommand()`:
```java
List<String> buildAgentCommand(String binary, String agentName) {
    List<String> cmd = new ArrayList<>();
    cmd.add(binary);
    if (agentName.contains("qwen")) {
        cmd.add("--dangerously-skip-permissions");
        // prompt will be the first positional arg — add it at call site, or use -p
    } else if (agentName.contains("gemini")) {
        cmd.add("-p");
        // Then append the prompt via args, not stdin
    }
    return cmd;
}
```
For agents that accept positional args or `-p`, pass the prompt as a command-line argument rather than (or in addition to) stdin.

---

### RC-4 (Hypothesis D): Result file stores only the head of each subagent's transcript; subtask content may be cross-contaminated via the shared persistent process

**Files and lines:**

| File | Line | Issue |
|------|------|-------|
| `DirectSubagentRunnerStdio.java` | 260 | `headBuffer` captures first `MAX_MEMORY_CHARS = 128_000` chars |
| `StdioMultiTaskTool.java` | 247–248 | `fullOutput.append(result.output)` — for claude, `result.output` is the `currentTurnOutput` buffer from `PersistentAgentProcess.sendMessage()`, which is what accumulated in the 120-s window |
| `PersistentAgentProcess.java` | 149–150 | `currentTurnOutput` is reset for each `sendMessage()` call; BUT since both subtasks share the same process, the **conversation context** (prior messages and responses) accumulates across subtasks |

**Observed evidence:** The results file section labelled `transcript-attribution` contains text about `FactSheetGraphServiceImpl` ("belongs to the OTHER subtask") because the two subtasks share a persistent process. Subtask 2 ran with subtask 1's entire conversation already in the model's context window, causing its response to bleed into subtask-1 territory.

**Fix:** The correct fix is RC-1 (one process per subtask). Additionally, the result file sections are labelled by `sf.name` (correctly ordered), so the labelling logic itself is sound; the content cross-contamination is the artifact of the shared process.

---

### RC-5 (Hypothesis F): Dead/unauthorized agent (qwen) has no preflight health check

**Files and lines:**

| File | Line | Issue |
|------|------|-------|
| `StdioTaskTool.java` | 119 | Only check: `if (result.contains("not found in PATH")) { continue; }` — falls through to next agent |
| `DirectSubagentRunnerStdio.java` | 356–358 | Same `not found in PATH` check in `StdioMultiTaskTool.runSubtask()` |
| `DirectSubagentRunnerStdio.java` | 346–369 | Exit code 1 is NOT treated as a special case; `executeSubagentProcess()` does not throw for non-zero exit code (lines 346–390): returns `SubtaskResult(true, output)` unless the output triggers `isRateLimited()` |

**Mechanism:** When `qwen` exits with code 1, the subprocess output (if non-empty) is returned as `SubtaskResult(true, ...)`. If the output is empty, the method returns a fallback string: `"Subagent 'qwen' exited with code 1 after 7.6s (no output captured)."` This string does NOT contain "not found in PATH", so the check at line 119 does not trigger a fallback. The task is declared failed (via the empty-output path returning a description string checked by caller), but no fallback agent is tried for exit-code-1 scenarios.

**Fix:**
1. Add a preflight `resolveAgentBinary()` check before submitting any subtask to the pool (not just when PATH lookup fails).
2. Treat non-zero exit codes as soft failures and trigger the role-based fallback chain (currently only triggered for `RateLimitException`).
3. For qwen specifically, once the command-line is fixed (RC-3), exit-code-1 failures will resolve.

---

## Unconfirmed / Refuted Hypotheses

### Hypothesis E: Worktree isolation — edits written to worktree, never merged to main tree

**Status: REFUTED for the June-19 multi_task runs; true for prior session.**

The `.claude/worktrees/agent-a52e5b737aa05f388/` worktree has 15 modified Java files (BatchRetryPolicy.java, DynamicBatchSizer.java, etc.) with timestamps from **Jun 18 20:08–21:37** — more than 10 hours before the multi_task runs on Jun 19 07:58 and 08:13. These are uncommitted changes from a prior Claude Code agent session using `isolation: "worktree"`, unrelated to the multi_task failures under investigation.

**Evidence against E for June-19 runs:**
- The task-results files (`multi-Fix_the_three_crawl_persistence_visibili-20260619-080223.md`, `multi-Re-dispatch_the_three_crawl_fixes___tigh-20260619-081748.md`) are in `/home/agibsonccc/Documents/GitHub/kompile/.kompile/task-results/` (main repo), not in any worktree. `McpStdioCommand` passes `wd` (the MCP server's working directory) to `DirectSubagentRunnerStdio`, and the MCP server is launched by claude via `.mcp.json` with `--work-dir` set to the project root, not the worktree.
- `find kompile-app -name '*.java' -newermt '2026-06-19 07:55'` found zero files in the main tree — confirmed that subagents simply did not complete their edits, not that edits went to a different location.

**Ongoing risk (not for these runs):** When the **parent** claude agent runs with `isolation: "worktree"` and uses the `Agent` tool (not the kompile `multi_task` MCP tool), its own edits land in the worktree and are not merged unless the SDK handles it. The 15 stranded worktree files represent exactly this scenario from Jun 18. Those edits need manual merge or the worktree needs to be committed and merged.

---

## Fix Priority Ranking

| Priority | Fix | Impact |
|----------|-----|--------|
| P0 | **RC-1**: Give each claude subtask its own `PersistentAgentProcess` | Eliminates 240-s timeout, enables true parallelism |
| P0 | **RC-1**: Raise `DEFAULT_TURN_TIMEOUT_SECONDS` from 120 s to 600 s | Subtasks that do actual work need more time |
| P1 | **RC-3**: Pass prompt as positional/`-p` arg to qwen/gemini; add `--dangerously-skip-permissions` for non-interactive use | qwen exits immediately without this fix |
| P2 | **RC-2**: Add `TIMED_OUT` / `INCOMPLETE` status field to `SubtaskResult`; surface it in summary | Prevents false "completed successfully" signals |
| P3 | **RC-5**: Treat non-zero exit codes as a fallback trigger (alongside `RateLimitException`) | Enables automatic retry/fallback on dead agents |

---

## Appendix: Key Source Locations

```
kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/mcp/stdio/
  StdioMultiTaskTool.java     — multi_task tool: subtask dispatch, result collection, status reporting
  StdioTaskTool.java          — task tool: single subtask dispatch
  DirectSubagentRunnerStdio.java — subprocess spawning, MCP injection, PersistentAgentProcess management
  AsyncToolExecutor.java      — background task execution wrapper

kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/main/chat/agent/
  PersistentAgentProcess.java — claude stream-json protocol, sendLock, 120s turn timeout

kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/main/chat/mcp/
  McpToolInjection.java       — writes .qwen/settings.json, .mcp.json, etc. for subagent MCP access
  McpToolInjectionSupport.java — resolves kompile CLI launcher for stdio MCP injection

kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/main/mcp/
  McpStdioCommand.java        — MCP server entry point; forces delegation tools to run async
```

---

*Report generated by code analysis. All file:line citations are from the current HEAD of the `main` branch.*
