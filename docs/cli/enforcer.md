# Policy Enforcer

The enforcer evaluates every agent response against rules and can interrupt mid-stream, auto-rollback file changes on violations, and retry with correction prompts.

## Usage

```bash
kompile enforcer --agent=claude-code \
  --rules="STOP_CMD: git push --force" \
  --rules="BAN_DIFF_REGEX: password\s*=\"[^\"]+\"" \
  --max-corrections=3
```

## Rule types

| Type | Description |
|------|------------|
| `STOP_CMD` | Block specific shell commands |
| `BAN_CMD` | Prevent command execution |
| `BAN_DIFF_REGEX` | Catch banned code patterns in file diffs |
| LLM judge | Use an LLM to evaluate responses against custom criteria |

## Configuration

```bash
kompile configure enforcer      # Interactive enforcer config wizard
```

Or configure via `~/.kompile/config/` JSON files. The enforcer supports per-project rules.

## Behavior

- Evaluates every agent response against all active rules
- Can interrupt mid-stream on violations
- Auto-rollback file changes when a violation is detected
- Retries with correction prompts up to `--max-corrections` times
- `--diff-patterns` catches banned code patterns in file diffs
