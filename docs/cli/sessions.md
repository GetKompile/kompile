# Session Management

Kompile tracks all chat conversations as sessions. Sessions persist across restarts and can be resumed, searched, imported, and managed.

## List and browse sessions

```bash
kompile session list                              # List all sessions
kompile session show --id=<id>                    # Show session details
kompile session search --query="authentication"   # Search across sessions
```

## Resume sessions

```bash
# Resume a specific session
kompile chat --resume <id>

# Browse and pick a session to resume
kompile resume

# Restore recently active exited or crash-detected chats in new terminal windows
kompile resume-all                                # Sessions active in the last 30 minutes (default)
kompile resume-all --list                         # Exact sessions the batch would launch
kompile resume-all --dry-run                      # Preview commands without launching
kompile resume-all --active-within 60             # All sessions active in the last 60 minutes
kompile resume-all --recent 5                     # Only the 5 most recently active
kompile resume-all --all                          # Every resumable tracked chat
kompile resume-all --set-recent 20                # Persist a new default limit
kompile resume-all --prune 30                     # Remove entries older than 30 days
kompile resume-all --status                       # Show registry and terminal status

# The same batch action is visible inside standard chat
/resume-all --dry-run
/resume-all --active-within 60

# Or open /resume and enter `resume-all --dry-run` in the resume browser.
# Running chats are excluded; dead-PID entries become resumable automatically.

# Repair stuck locks after a bad/corrupt shutdown
kompile resume-all --unlock <session-id>           # Force one stuck session back to resumable
kompile resume-all --unlock-all                    # Repair abandoned resume claims + dead-PID rows
```

## Import from external providers

Import chat history from Claude, ChatGPT, Gemini, and other AI providers:

```bash
kompile session import --source=claude
kompile session import-all                        # Import from all sources

# Agent CLI import with more options
kompile agent chat-import sources                 # List available sources
kompile agent chat-import list --source=claude    # List importable sessions
kompile agent chat-import fetch --source=claude --id=<id>
kompile agent chat-import sync --source=claude    # Sync all new sessions
kompile agent chat-import search --query="topic"
```

## Translate and merge

```bash
kompile session translate --id=<id> --format=markdown
kompile session merge --ids=<id1>,<id2>
```

## Analytics

```bash
# Index tool calls and token usage from provider transcripts
kompile index --source=claude --reindex

# View usage statistics
kompile stats --project=my-project --last=7d
kompile stats --session=<id> --json
```
