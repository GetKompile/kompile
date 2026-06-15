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
kompile chat --resume --session-id=<id>

# Browse and pick a session to resume
kompile resume

# Resume all tracked sessions in new terminal windows
kompile resume-all --agent=claude-code
kompile resume-all --dry-run                      # Preview what would resume
kompile resume-all --list                         # List resumable sessions
kompile resume-all --prune                        # Remove stale sessions
kompile resume-all --status                       # Show session status
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
