# Claude Code transcripts in Kompile Chat

Kompile's Claude subscription route runs a persistent `claude -p` process with
stream-json input/output. It already renders Claude's text, thinking, tool activity
and notices in the harness, saves the readable Kompile transcript, and checkpoints
the native session ID in the conversation ledger for restart/resume. The readable
assistant turn is saved at turn completion, not at every text delta.

Claude's native JSONL is separate: it contains structured provider history needed
for native resume. Kompile now exposes that file, without copying or polling, at:

```
~/.kompile/conversations/<kompile-session>/claude-code/<native-session>.jsonl
```

This is a symbolic link, not another transcript writer. Updates to the native file
are immediately readable through it **as Claude flushes them**. Linking is attempted
on the native initialization event and retried at turn completion if the file was
not ready. The accompanying `<native-session>.path` contains the original absolute
path and works on systems that deny symlink creation (a warning is printed). Native
session replacements retain their own links. Kompile never replaces existing files.

The native ID is also recorded as `[harvested:<id>]` in an existing readable Kompile
transcript, enabling existing native-ID resolution and imported-session deduplication.
Claude passthrough harvests use the same link helper. This does not turn raw native
passthrough's exit-time harvest into a new live file watcher.

## Storage controls and costs

- `CLAUDE_CONFIG_DIR` is supported by Claude, and Kompile's Claude transcript adapter
  now honors the inherited override instead of always reading `~/.claude/projects`.
  It relocates **settings, history and plugins**, not just conversations. Kompile
  does not set it automatically or copy account credentials/settings.
- There is no transcript-only directory override in the inspected official CLI and
  environment reference. Newer Claude versions provide `CLAUDE_CODE_PROJECT_DIR_NAME`
  alongside `CLAUDE_CONFIG_DIR`, but that also changes auto-memory placement.
- `--no-session-persistence` prevents native resume. Kompile already uses it for
  one-shot utility requests, not persistent chats. Isolated utility calls are not
  attached to a parent chat's transcript directory.
- The link and tiny path file add no duplicate JSONL bytes, stream-delta logs, polling
  threads, hooks, or per-tool processes. The readable Kompile transcript and Claude's
  resumable JSONL still consume space independently.
- Links are **not backups**. Claude's cleanup, compaction-time local GC, manual purge,
  or moving a config directory can remove/change the target. Kompile's own readable
  transcript remains independent. Subagent JSONL files are not mirrored by this change.

## Official references checked

- [Sessions: continuous saving, transcript paths, project directory naming](https://code.claude.com/docs/en/sessions#where-transcripts-are-stored)
- [Environment variables: CLAUDE_CONFIG_DIR and transcript GC](https://code.claude.com/docs/en/env-vars)
- [CLI: stream-json, session-id, resume, no-session-persistence](https://code.claude.com/docs/en/cli-reference)
- [Hooks: transcript_path](https://code.claude.com/docs/en/hooks#common-input-fields)

These references describe current releases; no new version-specific Claude flag is
required by the integration.
