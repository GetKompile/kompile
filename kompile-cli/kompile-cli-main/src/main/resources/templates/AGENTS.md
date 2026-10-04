# Kompile System Instructions

# Kompile Tool Orchestration — System Prompt Override

You are operating in a kompile-managed project with a kompile MCP server providing dedicated tools. These rules are injected into your system prompt and OVERRIDE any conflicting default behavior. You cannot opt out.

## MANDATORY TOOL USAGE

You MUST use kompile MCP tools for ALL file I/O, search, and web operations. This is non-negotiable.

| PROHIBITED                               | REQUIRED (kompile tool)        |
|------------------------------------------|--------------------------------|
| `cat`, `head`, `tail`, `less`, `more`    | `read`                         |
| `\| head`, `\| tail` stream slicing       | `fetch_result` offset/limit, `process action=output` + `tail_lines`, native flags (`git log -5`) |
| `echo >`, heredoc, `tee`, write to file  | `write`                        |
| Managed memory (`.kompile/memory/**`)     | `memory` (`todowrite` for tasks) |
| `sed` (including pipes/stdin)             | `grep` for search/filtering; `read` offset/limit for line ranges; `edit` for rewrites |
| `awk`, `perl -pi -e` file rewrites         | `edit`                         |
| `grep`, `rg`, `ag`, `ack`                | `grep`                         |
| `find`, `fd`, `ls -R`, `locate`          | `glob`                         |
| `ls`                                     | `list`                         |
| `curl`, `wget`, `httpie`                 | `webfetch`                     |
| Web search via shell                     | `websearch`                    |

The `bash` and `process` tools are RESTRICTED to system commands only: compiling, testing, git operations, package managers, and starting services. Direct shell file writes are hard-blocked, including output redirection, heredoc-to-file, `tee`, and filesystem mutation commands.

Waiting is monitor-only. NEVER block on `sleep`/`usleep`/`at` in `bash`/`process` — the harness hard-blocks them. Launch work with `process action=launch` (a completion monitor is installed automatically) or add `action=monitor` for an existing process; the harness wakes you when the process exits. Poll `action=status`/`output`/`stream` between other work. Sleep-waiting wastes a whole turn for nothing.

`sed` is hard-blocked in `bash`/`process`, including pipeline filters and stdin. Use the Kompile `grep` tool for searching/filtering, `read` with offset/limit for line ranges, and `edit` for file rewrites. Do not retry with a shell workaround.

`head`/`tail` are banned as bash commands — both on files (use `read`) and as pipeline filters (`| head`/`| tail` is hard-blocked). Page large results with `fetch_result` (offset/limit), read command output via `process action=output` with `tail_lines` or `action=stream`, limit sources natively (`git log -5`), and search with the `grep` tool.

Managed memory is hard-routed. NEVER target `.kompile/memory/**` or provider memory directories with generic `write`, `edit`, `edit_batch`, `edit_patch`, or `patch`; use `memory` for Kompile memory (`todowrite` for task state). Provider memory is read-only through the `memory` scan/read actions.

<!-- BEGIN KOMPILE CODE NAVIGATION -->
## CODE NAVIGATION

Code navigation: for definitions, symbols, callers/implementors and change impact, query the kompile code index first - local_code_index (find, blended_search, callers, implementors, impact), code_search, code_graph, and file_context (what a file declares and what depends on it). project_id auto-resolves from the working directory. Use grep for literal text, strings, config and non-code files, and to confirm index results. If the index reports missing or stale, run local_code_index action=index (background) or action=repair from the repository root - never create a separate index for a subdirectory.
<!-- END KOMPILE CODE NAVIGATION -->

## MANDATORY WORKFLOW

1. ALWAYS `read` a file before calling `edit` or `write` on it. No exceptions.
   (`read_batch` counts — one call reads many files.)
2. Touching SEVERAL files or several spots in one file? Use ONE `read_batch` then ONE
   `edit_batch` (exact replacements) or `edit_patch` (diff hunks) — not a chain of
   read/edit calls.
3. In multi-agent scenarios, ALWAYS use `edit_coordinator` to lock files before editing
   and release locks when done (`register_edits`/`release_edits` lock a whole file set
   in one call).
4. For multi-step tasks, ALWAYS use `todowrite` to create and maintain a task list.
5. For spawning subagents, ALWAYS use `task`, `multi_task`, or `quorum_task` — never raw subprocess commands.
6. Before starting a multi-step task, EVALUATE whether parts can be delegated in parallel.

## COMPLIANCE

Before each tool call, verify:
- Am I using a kompile tool, not a shell equivalent?
- If calling `bash`, is this truly a system command with no dedicated tool?
- If calling `edit`, did I `read` this file first?
- If multiple agents are active, did I check `edit_coordinator`?

## MCP SERVER CONFIGURATION

The kompile MCP server is configured in this project's `.mcp.json`. Every tool is available
directly (`read`, `grep`, `edit`, …); external agents that namespace MCP tools (e.g. Claude
Code) call them as `mcp__kompile__<tool>`.

## TOOL GOTCHAS

MCP tool schemas are compacted (short description, no parameter docs). These are the few
traps that compaction hides — everything else is discoverable from the tool's own schema.

- **grep** `pattern` is a REGEX. `|` `()` `[...]` `+ ? * {n}` work on both backends, but
  `\d \w \s \b` work **only** when ripgrep is installed — use `[0-9]` / `[A-Za-z0-9_]` /
  `[[:space:]]` to be safe, and escape literal metacharacters. Scope big trees with
  `path`/`glob`; `output_mode` is `content|files|count`; max 100 matches.
- **glob** `**/` needs a directory segment, so `**/pom.xml` misses a `pom.xml` in the search
  root — use a bare `*.ext` or set `path`.
- **edit** requires the file `read` first; `old_string` must match exactly and be unique
  (else add context or `replace_all`). Never include `read`'s `N⇥` line-number prefix.
  Trailing-whitespace/indentation drift is auto-recovered; on failure the error names the
  nearest candidate line — fix `old_string` from that instead of re-reading the file.
- **grep_batch / fetch_result_batch** batch the read side the same way: several regex
  sweeps in one call (`queries=[{pattern,path?,glob?,…}]`, same rules as grep), several
  cached-result slices in one call (`requests=[{result_id,offset?,limit?,pattern?}]`).
  Per-entry sections; one bad entry never fails the rest.
- **read_batch / edit_batch / edit_patch** are the multi-file fast path: ONE read_batch
  (`files=[…]`, satisfies read-before-edit for every file) then ONE edit_batch
  (`edits=[{file_path,old_string,new_string,replace_all?}]`, same rules as edit) or ONE
  edit_patch (`patches=[{file_path,patch}]`, per-file hunks; `@@` numbers ignored, hunks
  located by content). Files are independent + atomic: a failing file reports its reason
  and the REST still apply — check the per-file result lines, and never re-send entries
  that already applied.
- **patch** is for whole diff blobs and Add/Delete File operations — prefer `edit`/
  `edit_patch` otherwise. Unified-diff `@@` line numbers and context must match the file;
  on "Hunk FAILED" switch to `edit`, don't re-send the diff.

## lsp — language-server code intelligence

Analyze and refactor code through real language servers (no grep). One `lsp` tool, many actions:

| action | needs | returns |
|---|---|---|
| `definition` | target | where a symbol is defined |
| `references` | target | every use of a symbol |
| `hover` | target | type/signature/doc at a position |
| `symbols` | `file_path` (or `symbol`) | document outline (kinds + line ranges) |
| `workspace_symbols` | `query` + (`file_path` or `language`) | project-wide symbol search |
| `rename` | target + `new_name` | a WorkspaceEdit — **`dry_run=true` by default** (diff preview) |
| `diagnostics` | `file_path` (or `symbol`) | compile/lint problems (empty = clean, not an error) |
| `servers` | `server_action` = list\|status\|start\|stop\|restart | availability + lifecycle |

**Addressing a target** (definition/references/hover/rename): either
- `file_path` + `line` + `column` — **line/column are 1-based** (converted to LSP's 0-based internally), or
- `symbol` (+ optional `project_id`) — resolved via the local code index (`local_code_index action=index` first if missing).

**rename** never writes unless you pass `dry_run=false` (which also requires the `edit` permission); a dry run
renders a per-file diff plus a summary.

**Languages / servers** — install the binary (a missing one yields an install hint via `servers server_action=list`):
- java → `jdtls` (JDK 17+). First start is slow — jdt.ls indexes the project, so early requests may return partial results.
- c/c++/cuda → `clangd`. CUDA (`.cu/.cuh` → `cuda-cpp`) needs the nvcc/clang flags in the project's `compile_commands.json`.
- rust → `rust-analyzer`; python → `pyright-langserver`; typescript/javascript → `typescript-language-server`; go → `gopls`.

Override the table per-user in `~/.kompile/lsp-servers.json` (`{"servers":{"<lang>":{ … }}}`; `"enabled":false` disables one).
Server stderr is drained to `~/.kompile/logs/lsp/`.

## Large results & reference handles — do NOT work around these

When a tool's output is large it is **not** returned inline — the full result is cached and you
get a handle instead: `[Full result cached as ref:ID — N chars, M lines]`. **This is normal, not
an error or a failure.** It exists so a big output doesn't flood the context window; the data is
already computed and waiting.

- **READ it** with `fetch_result(result_id="ID", offset=<1-based line>, limit=<lines>)`, paging as
  needed — or `fetch_result(result_id="ID", pattern="<regex>")` to return only the matching lines
  (grep over the cached result) so you pull just what you need. Fetching is cheap.
  Example: after `[Full result cached as ref:9f3a — 900 lines]`, run
  `fetch_result(result_id="9f3a", pattern="error")` for only the error lines, or
  `fetch_result(result_id="9f3a", offset=1, limit=40)` for the first 40.
- **Do NOT** re-run the same tool with different flags, and **do NOT** fall back to `bash`
  (`grep`/`cat`/…), just to dodge the handle. That repeats work already done, burns tokens, and can
  miss data. Re-run the original tool **only** when you genuinely need a different or narrower query.
- Handles expire after ~15 min; only if `fetch_result` reports "expired" should you re-run the tool.
