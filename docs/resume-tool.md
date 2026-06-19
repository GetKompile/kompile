# Resume Tool - Conversation Migration and Resume System

## Overview

The Resume Tool is an interactive multi-tab TUI (Text User Interface) for browsing, searching, migrating, and resuming conversations across different AI agents. It enables seamless transition between conversations from various agents (Claude Code, Codex, Qwen, OpenCode) and allows you to resume any conversation with a different agent while preserving context.

## Key Features

### 1. **Multi-Tab Browser**
- Tabbed interface grouped by agent (claude, codex, qwen, opencode, etc.)
- Browse conversations from all agents in a unified view
- Pagination support for large conversation lists

### 2. **Search & Filter**
- Search conversations by keyword
- Filter by agent name
- Filter by source (kompile, claude-code, opencode, codex, qwen)
- Clear filters with a single command

### 3. **Conversation Migration**
- Migrate conversations between formats:
  - `kompile` - Plain-text transcript format
  - `openai` - JSON array for OpenAI API
  - `anthropic` - JSON array for Anthropic Messages API
  - `markdown` - Human-readable markdown format
  - `jsonl` - JSON Lines format

### 4. **Resume with Context**
- Load any conversation into a different agent
- Automatically builds context file with conversation history
- Injects context into agent session at startup
- Supports all passthrough agents (claude, codex, qwen, opencode, gemini)

## Usage

### Launch Interactive TUI

```bash
# Launch the full interactive resume tool
kompile resume
```

**Interactive Commands:**
- `tab <n>` - Switch to agent tab (0-based index)
- `search <query>` - Search conversations by keyword
- `filter agent=<name>` - Filter by agent name
- `filter source=<name>` - Filter by source
- `view <session-id>` - View conversation transcript
- `migrate <session-id>` - Migrate conversation to different format
- `resume <session-id>` - Resume conversation with designated agent
- `next` / `prev` - Navigate pages
- `clear` - Clear all filters
- `q` - Quit

### Command-Line Options

```bash
# Search conversations
kompile resume --search "database migration"

# Filter by agent
kompile resume --search "API" --filter-agent claude

# Filter by source
kompile resume --search "bug fix" --filter-source kompile

# View a specific conversation
kompile resume --view --session-id cli-abc123

# Migrate conversation to different format
kompile resume --migrate markdown --session-id cli-abc123

# Resume conversation with specific agent
kompile resume --session-id cli-abc123 --agent claude

# Resume with qwen agent
kompile resume -s cli-abc123 -a qwen

# List all conversations (interactive)
kompile resume --list
```

### Resume Workflow

#### Step 1: Browse/Search Conversations

Launch the interactive TUI:
```bash
kompile resume
```

You'll see a tabbed interface:
```
╔══════════════════════════════════════════════════════════╗
║           Kompile Conversation Resume Tool               ║
╚══════════════════════════════════════════════════════════╝

  [ claude ]  codex  kompile  opencode  qwen  

Filters:
  Search: database

  #  ID                                    Agent           Source         Last Modified
  ──────────────────────────────────────────────────────────────────────────────────────
   1 cli-abc123                           claude          kompile        2025-04-08 14:30
   2 passthrough-xyz789                   codex           kompile        2025-04-08 13:15

  Page 1 of 1 (2 conversations)

Commands: tab <n> | search <query> | filter agent=<name> | view <id> | migrate <id> | resume <id> | next | prev | clear | q
```

#### Step 2: View Conversation (Optional)

```
Command: view cli-abc123
```

This displays the conversation transcript for review.

#### Step 3: Migrate Conversation (Optional)

```
Command: migrate cli-abc123
Target format (kompile/openai/anthropic/markdown/jsonl) [kompile]: markdown
```

The conversation is converted to the target format and saved to:
```
~/.kompile/conversations/cli-abc123-migrated.markdown
```

#### Step 4: Resume with Agent

```
Command: resume cli-abc123
Target agent (claude/codex/qwen/opencode) [claude]: codex
```

The system:
1. Loads the conversation turns
2. Builds a markdown context file with the full history
3. Launches the target agent with context injection
4. Shows the context to the agent at session start

### Direct Resume (Non-Interactive)

Skip the TUI and resume directly:

```bash
# Resume with claude
kompile resume --session-id cli-abc123 --agent claude

# Resume with codex
kompile resume -s cli-abc123 -a codex

# Resume with qwen
kompile resume -s cli-abc123 -a qwen
```

## How It Works

The Resume Tool uses **native agent session injection** for true conversation continuation:

### Architecture

Instead of displaying a generic context file, the Resume Tool:

1. **Loads** kompile conversation turns from `~/.kompile/conversations/`
2. **Converts** them to the target agent's native JSONL format:
   - **Claude Code**: `~/.claude/projects/<project-dir>/<session-uuid>.jsonl`
   - **Codex**: `~/.codex/sessions/YYYY/MM/DD/rollout-<timestamp>-<uuid>.jsonl`
   - **Qwen**: `~/.qwen/projects/<project-dir>/chats/<session-uuid>.jsonl`
   - **OpenCode**: `~/.local/share/opencode/storage/{session,message,part}/`
3. **Writes** the session directly into the agent's storage
4. **Launches** the agent with its native resume flag:
   - Claude: `claude --resume <session-id>`
   - Codex: `codex resume <session-id>`
   - Qwen: `qwen --resume <session-id>`
   - OpenCode: `opencode -s <session-id>`

### Agent-Specific Format Conversion

The `ConversationExporter` handles format differences:

| Agent | User Role | Assistant Role | Content Format | Storage |
|-------|-----------|----------------|----------------|---------|
| **Claude** | `user` | `assistant` | `message.content[]` (array of blocks) | Single `.jsonl` file |
| **Codex** | _(all entries)_ | _(none)_ | `text` field (flat) | Date-based dirs + `history.jsonl` |
| **Qwen** | `user` | `model` (not assistant!) | `message.parts[]` | `.jsonl` in `chats/` |
| **OpenCode** | `user` | `assistant` | Separate JSON files for session/message/part | Hierarchical JSON tree |

### Session Index Updates

For agents that use session indexes (Claude, Qwen), the exporter automatically:
- Creates or updates `sessions-index.json`
- Adds metadata: session ID, summary, message count, timestamps
- Ensures the session appears in the agent's resume picker UI

### Data Flow

```
┌─────────────────────┐
│  Load Kompile       │
│  Conversation       │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Conversation       │
│  Exporter           │
│  (format convert)   │
└──────────┬──────────┘
           │
     ┌─────┴──────┬────────────┬──────────┐
     ▼            ▼            ▼          ▼
┌────────┐  ┌──────────┐  ┌──────┐  ┌─────────┐
│Claude  │  │  Codex   │  │ Qwen │  │OpenCode │
│ JSONL  │  │  JSONL   │  │JSONL │  │ JSON fs │
└───┬────┘  └────┬─────┘  └──┬───┘  └────┬────┘
    │            │           │           │
    ▼            ▼           ▼           ▼
┌────────┐  ┌──────────┐  ┌──────┐  ┌─────────┐
│~/.claude│ │~/.codex/ │ │~/.qwen│ │~/.local/│
│/projects│ │sessions/ │ /projects│ │share/ope│
└────┬───┘  └────┬─────┘  └──┬───┘  │ncode/   │
     │           │            │      └────┬────┘
     ▼           ▼            ▼           ▼
┌─────────────────────────────────────────────────┐
│  Agent Native Resume Flag                       │
│  claude --resume <id>                           │
│  codex resume <id>                              │
│  qwen --resume <id>                             │
│  opencode -s <id>                               │
└─────────────────────────────────────────────────┘

### Components

#### 1. ResumeTool (`ResumeTool.java`)
- **Location**: `kompile-cli/src/main/java/ai/kompile/cli/main/chat/tools/ResumeTool.java`
- **Purpose**: Core TUI implementation with multi-tab browser
- **Features**:
  - JLine-based terminal interaction
  - Tab management by agent
  - Search and filtering
  - Conversation viewing
  - Migration between formats
  - Resume with context building

#### 2. ResumeCommand (`ResumeCommand.java`)
- **Location**: `kompile-cli/src/main/java/ai/kompile/cli/main/chat/ResumeCommand.java`
- **Purpose**: Picocli command entry point
- **Features**:
  - Command-line argument parsing
  - Dispatch to appropriate action (search, view, migrate, resume)
  - Falls back to interactive TUI if no specific action

#### 3. PassthroughCommand Updates
- **Location**: `kompile-cli/src/main/java/ai/kompile/cli/main/chat/PassthroughCommand.java`
- **New Option**: `--resume-context <path>`
- **Features**:
  - Accepts path to context file
  - Displays context before starting agent
  - Integrates with existing passthrough workflow

### Context File Format

When resuming a conversation, a markdown context file is generated:

```markdown
# Conversation Context

**Original Session ID:** cli-abc123
**Message Count:** 15
**Resumed At:** 2025-04-08 14:30:00

---

## Conversation History

### User

Help me debug this database connection issue...

### Assistant

I'll help you debug the database connection issue. Let me check...

---

### User

The error message is "Connection timeout after 30s"

...

## Instructions

This is a resumed conversation from a previous session. Please review the context above and continue helping the user from where you left off.
```

### Data Flow

```
┌─────────────────────┐
│  Load Conversations │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Apply Filters      │
│  (search, agent,    │
│   source)           │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Display in TUI     │
│  (multi-tab,        │
│   paginated)        │
└──────────┬──────────┘
           │
     User selects
     conversation
           │
           ▼
┌─────────────────────┐
│  Load Turns from    │
│  ChatHistory        │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Build Context File │
│  (markdown format)  │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Launch Passthrough │
│  Agent with Context │
│  (--resume-context) │
└─────────────────────┘
```

## Integration Points

### ConversationImportTool
The ResumeTool leverages the existing `ConversationImportTool` to discover and list external agent conversations from:
- Claude Code: `~/.claude/projects/*.jsonl`
- OpenCode: `~/.local/share/opencode/opencode.db` (SQLite)
- Codex: `~/.codex/history.jsonl`
- Qwen: `~/.qwen/projects/*.jsonl`

### ConversationFormatter
Uses `ConversationFormatter` to convert conversation turns to different formats:
- Kompile plain-text
- OpenAI JSON
- Anthropic JSON
- Markdown
- JSONL

### ConversationReader
Uses `ConversationReader` to load conversation turns from:
- Kompile sessions (`~/.kompile/conversations/*.txt`)
- External agent storage (native formats)

### ChatHistory
Relies on `ChatHistory` for:
- Listing kompile conversations
- Reading transcript files
- Parsing conversation turns

## Examples

### Example 1: Search and Resume

```bash
# Search for conversations about "database"
kompile resume --search "database"

# In the TUI, you see:
#   1 cli-db123    claude    kompile    2025-04-08 14:30
#   2 cli-db456    codex     kompile    2025-04-08 13:15

# Resume the first conversation with codex
Command: resume cli-db123
Target agent (claude/codex/qwen/opencode) [claude]: codex

# Context is built and codex launches with the conversation history
```

### Example 2: Migrate and Archive

```bash
# Migrate a conversation to markdown for documentation
kompile resume --migrate markdown --session-id cli-abc123

# Output:
# ✓ Conversation migrated to markdown format
#   Saved to: ~/.kompile/conversations/cli-abc123-migrated.markdown
```

### Example 3: Cross-Agent Resume

```bash
# Started a complex debugging session with Claude
# Now want to continue with Codex for a different perspective

kompile resume --session-id cli-debug123 --agent codex

# Codex sees the full conversation context and continues
# from where Claude left off
```

### Example 4: Filter by Source

```bash
# Only show conversations from Claude Code
kompile resume --filter-agent claude --filter-source kompile

# Shows only kompile sessions where the agent was claude
```

## Advanced Usage

### Programmatic Access

The ResumeTool can be used as a CliTool within the agentic chat loop:

```java
ResumeTool tool = new ResumeTool();
ObjectNode params = om.createObjectNode();
params.put("action", "search");
params.put("query", "database");
params.put("agent", "claude");

ToolResult result = tool.execute(params, context);
```

### Supported Actions

| Action | Description | Required Params | Optional Params |
|--------|-------------|-----------------|-----------------|
| `browse` | Launch interactive TUI | - | - |
| `search` | Search conversations | - | `query`, `agent`, `source` |
| `migrate` | Migrate conversation | `session_id` | `output_format` |
| `resume` | Resume with agent | `session_id` | `target_agent` |
| `view` | View transcript | `session_id` | - |

## File Locations

- **Transcripts**: `~/.kompile/conversations/<session-id>.txt`
- **Migrated Files**: `~/.kompile/conversations/<session-id>-migrated.<format>`
- **Context Files**: `~/.kompile/temp/<session-id>-context.md`
- **Index**: `~/.kompile/conversations/index.json`

## Troubleshooting

### Issue: "Conversation not found"
**Solution**: Ensure the session ID is correct. List conversations with `kompile resume --list`

### Issue: "Agent not found on PATH"
**Solution**: Install the target agent (claude, codex, qwen, opencode) and ensure it's in your PATH

### Issue: Context file not injected
**Solution**: The context file is displayed to the agent at startup. The agent should see it in the terminal output. Some agents may not automatically acknowledge the context - you may need to manually prompt them to review it.

### Issue: Migration fails with format error
**Solution**: Ensure the format is one of: `kompile`, `openai`, `anthropic`, `markdown`, `jsonl`

## Future Enhancements

- [ ] Support for resuming with server-mode (kompile-app) agents
- [ ] Merge multiple conversations before resuming
- [ ] Smart context truncation for very long conversations
- [ ] Export conversations to external formats (PDF, HTML)
- [ ] Semantic search across conversations using embeddings
- [ ] Conversation tagging and categorization
- [ ] Batch operations (migrate all, resume multiple)

## See Also

- `kompile chat --help` - Chat command help
- `kompile passthrough --help` - Passthrough command help
- `kompile session --help` - Session management help
- [AGENTS.md](../../AGENTS.md) - Main project documentation
