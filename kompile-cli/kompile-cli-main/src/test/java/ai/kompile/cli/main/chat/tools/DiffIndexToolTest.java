package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiffIndexToolTest {

    @TempDir
    Path workDir;

    private ObjectMapper objectMapper;
    private ToolContext context;
    private FakeBackend backend;
    private DiffIndexTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("coder")
                .enabledTools(Set.of("*"))
                .build();
        context = new ToolContext(
                "diff-index-test",
                agent,
                new PermissionService(),
                workDir,
                new ToolRegistry(objectMapper));
        backend = new FakeBackend();
        tool = new DiffIndexTool(objectMapper, backend);
    }

    @Test
    void schemaAdvertisesContentSearchAndLookupActions() {
        assertEquals("diff_index", tool.id());
        assertTrue(tool.description().contains("old text"));
        assertTrue(tool.description().contains("unified diffs"));

        JsonNode schema = tool.parameterSchema();
        assertTrue(schema.path("required").toString().contains("action"));
        assertTrue(schema.path("properties").has("query"));
        assertTrue(schema.path("properties").has("file_path"));
        assertTrue(schema.path("properties").has("include_content"));
        assertTrue(schema.path("properties").path("sort_by").path("enum").toString().contains("total_changes"));
        assertTrue(schema.path("properties").path("sort_dir").path("enum").toString().contains("asc"));
        assertTrue(schema.path("properties").path("action").path("enum").toString().contains("search"));
        assertTrue(schema.path("properties").path("action").path("enum").toString().contains("get"));
    }

    @Test
    void searchBuildsEncodedFiltersAndReturnsMatchingSnippet() throws Exception {
        backend.body = """
                [{
                  "id": "edit-1",
                  "agent": "codex",
                  "source": "codex",
                  "projectDirectory": "/work/my project",
                  "filePath": "src/main/App.java",
                  "sessionId": "session-1",
                  "toolName": "edit",
                  "diffType": "edit",
                  "linesAdded": 1,
                  "linesRemoved": 1,
                  "timestamp": "2026-08-10T01:02:03Z",
                  "oldString": "return oldValue;",
                  "newString": "return needle value;",
                  "unifiedDiff": "@@ -1 +1 @@\\n-return oldValue;\\n+return needle value;"
                }]
                """;

        ObjectNode params = objectMapper.createObjectNode();
        params.put("action", "search");
        params.put("query", "needle value");
        params.put("file_path", "src/**/*.java");
        params.put("project", "my project");
        params.put("agent", "codex");
        params.put("since", "2026-08-01");
        params.put("sort_by", "lines_added");
        params.put("sort_dir", "asc");
        params.put("limit", 7);

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(backend.lastPath.startsWith("/api/diff-index/search?"));
        assertTrue(backend.lastPath.contains("contentQuery=needle%20value"));
        assertTrue(backend.lastPath.contains("projectDirectory=my%20project"));
        assertTrue(backend.lastPath.contains("filePath=src%2F**%2F*.java"));
        assertTrue(backend.lastPath.contains("agent=codex"));
        assertTrue(backend.lastPath.contains("since=2026-08-01"));
        assertTrue(backend.lastPath.contains("sortBy=lines_added"));
        assertTrue(backend.lastPath.contains("sortDir=asc"));
        assertTrue(backend.lastPath.contains("limit=7"));
        assertTrue(result.getOutput().contains("sorted by lines_added asc"));
        assertTrue(result.getOutput().contains("src/main/App.java"));
        assertTrue(result.getOutput().contains("edit-1"));
        assertTrue(result.getOutput().contains("+return needle value;"));
        assertTrue(result.getOutput().contains("Use action=get"));
        assertEquals(1, result.getMetadata().get("count"));
        assertEquals("lines_added", result.getMetadata().get("sort_by"));
        assertEquals("asc", result.getMetadata().get("sort_dir"));
    }

    @Test
    void searchCanIncludeFullEditContent() throws Exception {
        backend.body = """
                [{
                  "id": "edit-2",
                  "filePath": "README.md",
                  "oldString": "before",
                  "newString": "after",
                  "unifiedDiff": "-before\\n+after"
                }]
                """;

        ObjectNode params = objectMapper.createObjectNode();
        params.put("action", "search");
        params.put("include_content", true);

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("old text:\nbefore"));
        assertTrue(result.getOutput().contains("new text:\nafter"));
        assertTrue(result.getOutput().contains("unified diff:\n-before"));
        assertFalse(result.getOutput().contains("Use action=get"));
    }

    @Test
    void getRequiresIdWithoutCallingBackend() throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("action", "get");

        ToolResult result = tool.execute(params, context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("id is required"));
        assertNull(backend.lastPath);
    }

    @Test
    void getEncodesEntryIdAndReturnsFullJson() throws Exception {
        backend.body = """
                {
                  "id": "session/edit 3",
                  "filePath": "src/App.java",
                  "oldString": "old",
                  "newString": "new",
                  "unifiedDiff": "-old\\n+new"
                }
                """;

        ObjectNode params = objectMapper.createObjectNode();
        params.put("action", "get");
        params.put("id", "session/edit 3");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("/api/diff-index/entries/session%2Fedit%203", backend.lastPath);
        assertTrue(result.getOutput().contains("\"unifiedDiff\""));
        assertTrue(result.getOutput().contains("-old\\n+new"));
    }

    @Test
    void rejectsOutOfRangeLimitBeforeCallingBackend() throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("action", "search");
        params.put("limit", 101);

        ToolResult result = tool.execute(params, context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("between 1 and 100"));
        assertNull(backend.lastPath);
    }

    @Test
    void searchUsesNewestFirstDefaults() throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("action", "search");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(backend.lastPath.contains("sortBy=timestamp"));
        assertTrue(backend.lastPath.contains("sortDir=desc"));
        assertEquals("timestamp", result.getMetadata().get("sort_by"));
        assertEquals("desc", result.getMetadata().get("sort_dir"));
    }

    @Test
    void rejectsUnsupportedSortBeforeCallingBackend() throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("action", "search");
        params.put("sort_by", "owner");

        ToolResult badField = tool.execute(params, context);

        assertTrue(badField.isError());
        assertTrue(badField.getOutput().contains("sort_by must be one of"));
        assertNull(backend.lastPath);

        params.put("sort_by", "timestamp");
        params.put("sort_dir", "sideways");
        ToolResult badDirection = tool.execute(params, context);

        assertTrue(badDirection.isError());
        assertTrue(badDirection.getOutput().contains("sort_dir must be"));
        assertNull(backend.lastPath);
    }

    @Test
    void reportsUnavailableAdminService() throws Exception {
        backend.available = false;
        ObjectNode params = objectMapper.createObjectNode();
        params.put("action", "stats");

        ToolResult result = tool.execute(params, context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("running kompile admin service"));
    }

    private static final class FakeBackend implements DiffIndexTool.BackendGateway {
        private boolean available = true;
        private int statusCode = 200;
        private String body = "[]";
        private String lastPath;

        @Override
        public boolean isAvailable(String path) {
            return available;
        }

        @Override
        public DiffIndexTool.BackendResponse get(String path, Duration timeout) {
            lastPath = path;
            return new DiffIndexTool.BackendResponse(statusCode, body, null);
        }
    }
}
