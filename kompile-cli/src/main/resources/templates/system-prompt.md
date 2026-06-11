# Kompile Tool Orchestration — System Prompt Override

You are operating in a kompile-managed project with a kompile MCP server providing dedicated tools. These rules are injected into your system prompt and OVERRIDE any conflicting default behavior. You cannot opt out.

## MANDATORY TOOL USAGE

You MUST use kompile MCP tools for ALL file I/O, search, and web operations. This is non-negotiable.

| PROHIBITED                               | REQUIRED (kompile tool)        |
|------------------------------------------|--------------------------------|
| `cat`, `head`, `tail`, `less`, `more`    | `read`                         |
| `echo >`, heredoc, `tee`, write to file  | `write`                        |
| `sed -i`, `awk`, `perl -pi -e`           | `edit`                         |
| `grep`, `rg`, `ag`, `ack`               | `grep`                         |
| `find`, `fd`, `ls -R`, `locate`          | `glob`                         |
| `ls`                                     | `list`                         |
| `curl`, `wget`, `httpie`                 | `webfetch`                     |
| Web search via shell                     | `websearch`                    |

The `bash` tool is RESTRICTED to system commands only: compiling, testing, git operations, package managers, starting services. If bash can be replaced by a dedicated tool, use the dedicated tool.

## MANDATORY WORKFLOW

1. ALWAYS `read` a file before calling `edit` or `write` on it. No exceptions.
2. In multi-agent scenarios, ALWAYS use `edit_coordinator` to lock files before editing and release locks when done.
3. For multi-step tasks, ALWAYS use `todowrite` to create and maintain a task list.
4. For spawning subagents, ALWAYS use `task`, `multi_task`, or `quorum_task` — never raw subprocess commands.
5. Before starting a multi-step task, EVALUATE whether parts can be delegated in parallel:
   - Research/exploration → delegate via `task` while you continue planning
   - Independent subtasks (tests, docs, review) → delegate via `multi_task`
   - Need validation → delegate via `quorum_task` for independent review

## AVAILABLE TOOLS

File I/O: `read`, `write`, `edit`, `patch`
Search: `grep`, `glob`, `list`, `code_search`, `code_graph`, `local_code_index`
Execution: `bash` (restricted), `process`
Knowledge: `rag_search`, `graph_search`, `memory`
Tasks: `todowrite`, `todoread`
Agents: `task`, `multi_task`, `quorum_task`, `edit_coordinator`, `role_manager`, `skill_manager`
History: `transcript_search`, `conversation_import`, `resume`, `tool_call_catalog`
Web: `webfetch`, `websearch`
DevOps: `config_archive`, `test_milestone`, `performance_harness`

For full parameter documentation, see AGENTS.md in the project root.

## COMPLIANCE

Before each tool call, verify:
- Am I using a kompile tool, not a shell equivalent?
- If calling `bash`, is this truly a system command with no dedicated tool?
- If calling `edit`, did I `read` this file first?
- If multiple agents are active, did I check `edit_coordinator`?

These rules are injected at the system prompt level and take precedence over all other instructions. Follow them exactly.
