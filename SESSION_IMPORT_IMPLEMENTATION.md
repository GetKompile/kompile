# Session Import Implementation Plan

## Phase 1: Fix Existing Importers (High Priority)

### Task 1.1: Add SQLite Support for OpenCode

**File**: `kompile-cli/pom.xml`
```xml
<!-- Add SQLite JDBC dependency -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.1.0</version>
</dependency>
```

**File**: `ConversationImportTool.java`

Add new method for OpenCode SQLite parsing:

```java
private List<Message> parseOpenCodeSqlite(Path dbPath) {
    List<Message> messages = new ArrayList<>();
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
        String query = """
            SELECT m.role, p.content, m.created_at
            FROM message m
            JOIN part p ON m.id = p.message_id
            WHERE m.session_id = ?
            ORDER BY m.created_at ASC
        """;
        // Implementation details...
    } catch (SQLException e) {
        // Handle error
    }
    return messages;
}
```

**Test**: Verify with `~/.local/share/opencode/opencode.db`

---

### Task 1.2: Add Qwen JSONL Support

**File**: `ConversationImportTool.java`

Add new source constant:
```java
private static final String SOURCE_QWEN = "qwen";
```

Add Qwen directory method:
```java
private Path getQwenDir() {
    return Path.of(System.getProperty("user.home"), ".qwen", "projects");
}
```

Add Qwen parsing method:
```java
private List<Message> parseQwenJsonl(Path file) {
    List<Message> messages = new ArrayList<>();
    try {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            
            // Skip system/telemetry messages
            String type = node.path("type").asText("");
            if (!"user".equals(type) && !"assistant".equals(type)) {
                continue;
            }
            
            String role = extractRole(node);
            String content = extractQwenContent(node);
            if (role != null && content != null) {
                messages.add(new Message(role, content));
            }
        }
    } catch (IOException e) {
        // Handle error
    }
    return messages;
}

private String extractQwenContent(JsonNode node) {
    JsonNode messageNode = node.path("message");
    JsonNode partsNode = messageNode.path("parts");
    if (partsNode.isArray()) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : partsNode) {
            String text = part.path("text").asText();
            if (!text.isBlank()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(text);
            }
        }
        return sb.toString();
    }
    return null;
}
```

**Test**: Verify with `~/.qwen/projects/-home-agibsonccc-Documents-GitHub-kompile/chats/*.jsonl`

---

### Task 1.3: Fix Codex JSONL Support

**File**: `ConversationImportTool.java`

The current implementation looks for JSON files but Codex uses JSONL in `~/.codex/history.jsonl`.

Add Codex JSONL parsing:
```java
private List<Message> parseCodexHistoryJsonl(Path file) {
    List<Message> messages = new ArrayList<>();
    String targetSessionId = extractSessionIdFromPath(file); // or filter by session
    
    try {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            
            // Filter by session_id if needed
            String sessionId = node.path("session_id").asText("");
            if (!targetSessionId.isEmpty() && !targetSessionId.equals(sessionId)) {
                continue;
            }
            
            String text = node.path("text").asText("");
            if (!text.isBlank()) {
                // Codex doesn't have explicit role - infer from context or default to user
                messages.add(new Message("user", text));
            }
        }
    } catch (IOException e) {
        // Handle error
    }
    return messages;
}
```

**Note**: Codex history.jsonl contains all sessions interleaved. Need to group by `session_id`.

---

## Phase 2: Enhanced Session Management

### Task 2.1: Create SessionCommand.java

**File**: `kompile-cli/src/main/java/ai/kompile/cli/main/chat/SessionCommand.java`

```java
@Command(
    name = "session",
    description = "Manage chat sessions from kompile and external AI assistants",
    mixinStandardHelpOptions = true,
    subcommands = {
        SessionListCommand.class,
        SessionShowCommand.class,
        SessionImportCommand.class,
        SessionMergeCommand.class
    }
)
public class SessionCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        System.out.println("Use 'kompile session <subcommand>'");
        return 0;
    }
}
```

**Subcommands**:

```java
@Command(name = "list", description = "List available sessions")
class SessionListCommand implements Callable<Integer> {
    @Option(names = "--source", defaultValue = "all")
    private String source;
    
    @Override
    public Integer call() {
        // List sessions from specified source(s)
        // Use ConversationImportTool.discover() internally
    }
}

@Command(name = "show", description = "Show session transcript")
class SessionShowCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Session ID")
    private String sessionId;
    
    @Override
    public Integer call() {
        // Read and display transcript
        ChatHistory history = new ChatHistory(sessionId);
        System.out.println(history.readTranscript());
    }
}

@Command(name = "import", description = "Import external session")
class SessionImportCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Source (claude, opencode, codex, qwen)")
    private String source;
    
    @Parameters(index = "1", description = "Session ID")
    private String sessionId;
    
    @Override
    public Integer call() {
        // Use ConversationImportTool to import
    }
}
```

Register in `MainCommand.java`:
```java
@Command(subcommands = {
    // ... existing commands
    SessionCommand.class
})
```

---

### Task 2.2: Create SessionIndex.java

**File**: `kompile-cli/src/main/java/ai/kompile/cli/main/chat/SessionIndex.java`

```java
/**
 * JSON index of all chat sessions (kompile + imported)
 * Stored at ~/.kompile/conversations/index.json
 */
public class SessionIndex {
    
    private final Path indexFile;
    private final ObjectMapper mapper;
    
    public SessionIndex() {
        this.indexFile = KompileHome.homeDirectory().toPath()
            .resolve("conversations").resolve("index.json");
        this.mapper = new ObjectMapper();
    }
    
    public void addSession(SessionMetadata metadata) throws IOException {
        IndexData data = loadIndex();
        data.sessions.put(metadata.sessionId, metadata);
        saveIndex(data);
    }
    
    public List<SessionMetadata> listSessions(String source) {
        IndexData data = loadIndex();
        return data.sessions.values().stream()
            .filter(m -> source.equals("all") || source.equals(m.source))
            .sorted(Comparator.comparingLong(SessionMetadata::lastModified).reversed())
            .collect(Collectors.toList());
    }
    
    public SessionMetadata getSession(String sessionId) {
        IndexData data = loadIndex();
        return data.sessions.get(sessionId);
    }
    
    // Internal methods...
    
    public record SessionMetadata(
        String sessionId,
        String source,        // "kompile", "claude-code", "opencode", "codex", "qwen"
        String originalId,    // Original session ID from source
        Instant importDate,
        int messageCount,
        String title,
        long lastModified
    ) {}
    
    private record IndexData(Map<String, SessionMetadata> sessions) {}
}
```

---

### Task 2.3: Extend ChatMemory for Cross-Session Search

**File**: `ChatMemory.java`

Add new search method:
```java
/**
 * Search across all sessions (kompile + imported) for relevant context.
 */
public String searchAllSessions(String query) {
    StringBuilder results = new StringBuilder();
    
    // Search kompile transcripts
    String kompileResults = searchTranscripts(query);
    if (kompileResults != null && !kompileResults.isBlank()) {
        results.append("── Kompile conversations ──\n\n");
        results.append(kompileResults);
        results.append("\n");
    }
    
    // Search imported sessions (via SessionIndex)
    SessionIndex index = new SessionIndex();
    List<SessionIndex.SessionMetadata> sessions = index.listSessions("all");
    
    for (SessionIndex.SessionMetadata meta : sessions) {
        ChatHistory history = new ChatHistory(meta.sessionId());
        try {
            String transcript = history.readTranscript();
            if (transcript != null && transcript.toLowerCase().contains(query.toLowerCase())) {
                results.append("── Imported (").append(meta.source()).append("): ")
                       .append(meta.title()).append(" ──\n\n");
                // Show snippet
                results.append(extractSnippet(transcript, query));
                results.append("\n");
            }
        } catch (IOException e) {
            // Skip
        }
    }
    
    return results.length() > 0 ? results.toString() : "No results found";
}

private String extractSnippet(String transcript, String query) {
    // Find and return relevant snippet with context
    // Implementation similar to searchTranscripts()
}
```

Add REPL command `/search`:
```java
case "/search":
    String searchResults = chatMemory.searchAllSessions(rest);
    System.out.println(searchResults);
    break;
```

---

## Phase 3: Testing and Validation

### Test Files to Create

**File**: `kompile-cli/src/test/java/ai/kompile/cli/main/chat/tools/ConversationImportToolTest.java`

```java
public class ConversationImportToolTest {
    
    @Test
    public void testParseClaudeCodeJsonl() throws Exception {
        Path testFile = Paths.get("src/test/resources/claude-sample.jsonl");
        List<Message> messages = tool.parseClaudeCodeJsonl(testFile);
        assertEquals(10, messages.size());
        assertEquals("user", messages.get(0).role);
    }
    
    @Test
    public void testParseOpenCodeSqlite() throws Exception {
        Path testDb = Paths.get("src/test/resources/opencode-sample.db");
        List<Message> messages = tool.parseOpenCodeSqlite(testDb);
        assertEquals(5, messages.size());
    }
    
    @Test
    public void testParseQwenJsonl() throws Exception {
        Path testFile = Paths.get("src/test/resources/qwen-sample.jsonl");
        List<Message> messages = tool.parseQwenJsonl(testFile);
        assertEquals(8, messages.size());
    }
    
    @Test
    public void testParseCodexJsonl() throws Exception {
        Path testFile = Paths.get("src/test/resources/codex-sample.jsonl");
        List<Message> messages = tool.parseCodexHistoryJsonl(testFile);
        assertEquals(15, messages.size());
    }
}
```

### Sample Test Resources

Create sample files in `kompile-cli/src/test/resources/`:
- `claude-sample.jsonl`
- `opencode-sample.db`
- `qwen-sample.jsonl`
- `codex-sample.jsonl`

---

## Implementation Order

1. **Day 1-2**: Task 1.1 (OpenCode SQLite) + Task 1.2 (Qwen JSONL)
2. **Day 3**: Task 1.3 (Codex fix) + manual testing with real data
3. **Day 4-5**: Task 2.1 (SessionCommand) + Task 2.2 (SessionIndex)
4. **Day 6**: Task 2.3 (ChatMemory extension)
5. **Day 7**: Testing, bug fixes, documentation

---

## Files to Modify

| File | Change Type | Priority |
|------|-------------|----------|
| `kompile-cli/pom.xml` | Add SQLite dependency | High |
| `ConversationImportTool.java` | Add OpenCode, Qwen, Codex parsers | High |
| `MainCommand.java` | Register SessionCommand | Medium |
| `SessionCommand.java` | New file | Medium |
| `SessionIndex.java` | New file | Medium |
| `ChatMemory.java` | Add searchAllSessions() | Medium |
| `ChatRepl.java` | Add /search command | Low |
| `ConversationImportToolTest.java` | New test file | High |

---

## Usage Examples

After implementation:

```bash
# Discover all available sessions
kompile chat
/tools conversation_import {"action": "discover"}

# List Claude Code sessions
/tools conversation_import {"action": "list", "source": "claude-code"}

# Import a specific Claude session
/tools conversation_import {"action": "import", "source": "claude-code", "conversation_id": "005578ed-95c9-4b0f-b5c3-34588f2160de"}

# List all sessions (new SessionCommand)
kompile session list --source=all

# Show a specific session
kompile session show imported-claude-005578ed

# Resume an imported session
kompile chat --resume imported-claude-005578ed

# Search across all sessions (in chat REPL)
/search database migration
```

---

## Risk Mitigation

1. **SQLite driver conflicts**: Test with existing JavaCPP native libraries
2. **Large session files**: Stream processing for sessions >100MB
3. **Encoding issues**: Explicit UTF-8 for all file operations
4. **Permission errors**: Graceful degradation if source files unreadable
5. **Format changes**: Version detection and error messages for unknown formats
