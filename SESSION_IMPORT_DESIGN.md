# Session Import and Resume Design

## Overview

This document describes the architecture for importing and resuming chat sessions from external AI coding assistants (Claude Code, OpenCode, Codex, Qwen) into the kompile-cli chat system.

## Current State

### Existing Infrastructure

1. **ChatHistory.java**: Plain-text transcript storage at `~/.kompile/conversations/<session-id>.txt`
   - Human-readable format with headers, user messages (`> `), assistant responses
   - Supports resume via `readTurns()` and `readTranscript()`
   - Index file tracks metadata

2. **ConversationImportTool.java**: MCP tool that imports from:
   - **Claude Code**: `~/.claude/projects/` (JSONL format)
   - **OpenCode**: `~/.opencode/sessions/` (JSON format - incomplete)
   - **Codex**: `~/.codex/conversations/` or `~/.codex/history/` (JSON format)
   - **Missing**: OpenCode SQLite support, Qwen JSONL support

3. **ChatMemory.java**: Cross-session context via:
   - Persistent MEMORY.md files
   - Transcript search across conversations
   - RAG search

4. **ChatCommand.java**: Session resume via CLI flags:
   - `--resume <session-id>`
   - `--continue` (most recent)
   - `--list` (list conversations)

### External Assistant Formats

| Assistant | Location | Format |
|-----------|----------|--------|
| **Claude Code** | `~/.claude/projects/<project>/<uuid>.jsonl` | JSONL with `type: user/assistant`, `message.role`, `message.content` |
| **OpenCode** | `~/.local/share/opencode/opencode.db` | SQLite: `session`, `message`, `part` tables |
| **Codex** | `~/.codex/sessions/<year>/<month>/<uuid>/` | JSONL with `session_id`, `ts`, `text` |
| **Qwen** | `~/.qwen/projects/<project>/chats/<uuid>.jsonl` | JSONL with `type: user/assistant`, `sessionId`, `message.role`, `message.parts` |

## Architecture

### Design Goals

1. **Unified session format**: All external sessions converted to kompile's plain-text transcript format
2. **Bidirectional awareness**: Kompile chat can reference external sessions via `/import` command
3. **Memory integration**: Imported sessions searchable via ChatMemory
4. **Minimal disruption**: Build on existing ConversationImportTool and ChatHistory

### Components

```
┌─────────────────────────────────────────────────────────────────┐
│                    Session Import System                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │ Claude Code  │  │   OpenCode   │  │    Codex     │          │
│  │  JSONL       │  │   SQLite     │  │   JSONL      │          │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘          │
│         │                 │                 │                   │
│         ▼                 ▼                 ▼                   │
│  ┌──────────────────────────────────────────────────┐          │
│  │        ConversationImportTool (MCP Tool)         │          │
│  │  - discover: find all external sessions          │          │
│  │  - list: show sessions from a source             │          │
│  │  - import: convert single session                │          │
│  │  - import-all: batch import                      │          │
│  └──────────────────────────────────────────────────┘          │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────────────────────────────────────────┐          │
│  │     SessionImportService (Java Service)          │          │
│  │  - Unified parser interface                      │          │
│  │  - Format detection                              │          │
│  │  - Transcript generation                         │          │
│  └──────────────────────────────────────────────────┘          │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────────────────────────────────────────┐          │
│  │        ChatHistory (Plain-text format)           │          │
│  │  ~/.kompile/conversations/imported-<source>-<id>.txt       │
│  └──────────────────────────────────────────────────┘          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Session Import Flow

1. **Discovery**: `ConversationImportTool.discover()` scans all known directories
2. **Listing**: User lists available sessions from a source
3. **Import**: Selected session parsed and converted to plain-text format
4. **Resume**: Imported session accessible via `kompile chat --resume imported-<source>-<id>`

## Implementation Plan

### Phase 1: Fix Existing Importers (Priority: High)

**1.1 OpenCode SQLite Support** (`ConversationImportTool.java`)
- Add SQLite JDBC dependency
- Query `session` and `message` tables
- Join with `part` table for full message content
- Convert to kompile transcript format

**1.2 Qwen JSONL Support** (`ConversationImportTool.java`)
- Add `SOURCE_QWEN = "qwen"` constant
- Location: `~/.qwen/projects/<project>/chats/<uuid>.jsonl`
- Parse format: `{ type, sessionId, message: { role, parts: [{ text }] } }`

**1.3 Codex JSONL Support** (fix existing)
- Current implementation looks for JSON, but actual format is JSONL
- Location: `~/.codex/history.jsonl` (single file with all sessions)
- Filter by `session_id` field

### Phase 2: Enhanced Session Management (Priority: Medium)

**2.1 Session Browser Command** (new `SessionCommand.java`)
```bash
kompile session list [--source=all|claude|opencode|codex|qwen|kompile]
kompile session show <session-id>
kompile session import <source> <session-id>
kompile session merge <session-id> <target-session-id>
```

**2.2 Session Metadata Index** (`SessionIndex.java`)
- JSON index at `~/.kompile/conversations/index.json`
- Track: source, original_id, import_date, message_count, participants
- Enable fast search/filtering

**2.3 Cross-Session Search** (extend `ChatMemory.java`)
- Search imported sessions by keyword
- Return snippets with source attribution
- Command: `/search <query>` in chat REPL

### Phase 3: Advanced Features (Priority: Low)

**3.1 Session Merging**
- Combine multiple sessions into unified conversation
- Preserve source attribution
- Handle overlapping timestamps

**3.2 Export Functionality**
- Export kompile sessions to external formats
- Round-trip compatibility where possible

**3.3 Auto-Import on Startup**
- Detect new external sessions
- Prompt user to import
- Configurable auto-import rules

## File Format Specifications

### Claude Code JSONL
```json
{"type":"user","message":{"role":"user","content":"Hello"},"uuid":"...","timestamp":"2026-03-29T10:15:10.626Z"}
{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Hi there!"}]},"uuid":"...","timestamp":"..."}
```

### OpenCode SQLite
```sql
-- session table
id TEXT PRIMARY KEY, title TEXT, project_root TEXT, created_at INTEGER, updated_at INTEGER

-- message table
id TEXT PRIMARY KEY, session_id TEXT, role TEXT, created_at INTEGER, metadata JSON

-- part table
id TEXT PRIMARY KEY, message_id TEXT, type TEXT, content TEXT, metadata JSON
```

### Codex JSONL
```json
{"session_id":"019a8b2e-f2e3-7183-89f3-338bedab594f","ts":1763285479,"text":"yes"}
{"session_id":"019a8b2e-f2e3-7183-89f3-338bedab594f","ts":1763286918,"text":"Yes take care of this"}
```

### Qwen JSONL
```json
{"uuid":"...","sessionId":"378d4e42-f944-4ada-8edf-c579bad88236","type":"user","message":{"role":"user","parts":[{"text":"Investigate..."}]}}
{"uuid":"...","sessionId":"...","type":"assistant","message":{"role":"model","parts":[{"text":"I'll investigate..."}]}}
```

### Kompile Transcript (Target Format)
```
──── Conversation: imported-claude-005578ed-95c9-4b0f-b5c3-34588f2160de ────
Started: 2026-03-31 16:00:00
Server:  (imported from claude-code)
Agent:   claude
RAG:     disabled

──────────────────────────────────

> Add subagents and other tools to the cli chat.

Let me start by understanding the current CLI chat implementation...

> Dissect opencode and add a comparable implementation.

I'll research OpenCode's tool architecture...
```

## Dependencies

### Required
- SQLite JDBC driver (for OpenCode support)
  ```xml
  <dependency>
      <groupId>org.xerial</groupId>
      <artifactId>sqlite-jdbc</artifactId>
      <version>3.45.1.0</version>
  </dependency>
  ```

### Optional
- None (all other parsing uses standard Java libraries)

## Testing Strategy

1. **Unit Tests**: Parser tests for each format with sample files
2. **Integration Tests**: End-to-end import → resume → chat flow
3. **Regression Tests**: Ensure existing ConversationImportTool functionality preserved

## Security Considerations

1. **File permissions**: Respect source file permissions when reading
2. **Path traversal**: Validate session IDs to prevent directory traversal
3. **Content sanitization**: Strip potentially sensitive metadata during import
4. **User confirmation**: Require explicit confirmation before importing

## Migration Path

For users with existing imported sessions:
1. No breaking changes to existing import format
2. New index file created alongside existing transcripts
3. Backward compatible with existing `--resume` functionality

## Success Metrics

1. **Coverage**: Support for 4 major AI assistants (Claude, OpenCode, Codex, Qwen)
2. **Performance**: Import 1000-message session in <5 seconds
3. **Fidelity**: Preserve >95% of message content (excluding tool calls, metadata)
4. **Usability**: Single-command import and resume workflow
