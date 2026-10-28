package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ToolCallIndex;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ToolCallCatalogToolTest {
    @TempDir Path workDir;
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final ToolCallCatalogTool tool = new ToolCallCatalogTool();
    private String session;
    private ToolContext context;

    @BeforeEach
    void seed() {
        session = "catalog-" + UUID.randomUUID();
        context = new ToolContext(session, AgentConfig.builder("tester").enabledTools(Set.of("*")).build(),
                new PermissionService(), workDir, new ToolRegistry(mapper));
        for (int i = 0; i < 107; i++) {
            ToolCallIndex.getInstance().record(session, i % 2 == 0 ? "Read" : "Write",
                    "{\"file_path\":\"needle-" + i + ".java\"}",
                    i % 2 == 0 ? "worker-A" : "worker-B", "test", false, 0, workDir.toString());
        }
    }

    private ObjectNode params(String action) {
        return mapper.createObjectNode().put("action", action).put("session", session).put("query", "needle-");
    }

    @Test
    void listAndSearchPageWithHonestContinuationAndStableTies() throws Exception {
        for (String action : Set.of("list", "search")) {
            ObjectNode params = params(action).put("limit", 50).put("sort_by", "tool");
            Set<String> ids = new HashSet<>();
            for (int offset = 0; offset < 107; offset += 50) {
                ToolResult result = tool.execute(params.put("offset", offset), context);
                assertFalse(result.isError(), result.getOutput());
                assertEquals(offset == 100 ? 7 : 50, result.getMetadata().get("count"));
                assertEquals(offset < 100, result.getMetadata().get("has_more"));
                if (offset < 100) assertEquals(offset + 50, result.getMetadata().get("next_offset"));
                for (String line : result.getOutput().split("\n")) {
                    if (line.contains(" id=")) {
                        String id = line.substring(line.indexOf(" id=") + 4, line.indexOf(" session="));
                        assertTrue(ids.add(id), "Repeated row across pages: " + id);
                    }
                }
            }
            assertEquals(107, ids.size());
        }
    }

    @Test
    void groupsByActualAgentOrToolAndHonorsAllFilters() throws Exception {
        ToolResult agents = tool.execute(params("list").put("group_by", "agent").put("limit", 1), context);
        assertFalse(agents.isError(), agents.getOutput());
        assertTrue(agents.getOutput().contains("## worker-A (1)"));
        assertTrue(agents.getOutput().contains("## worker-B (1)"));
        assertFalse(agents.getOutput().contains("## filesystem"));
        ToolResult tools = tool.execute(params("list").put("group_by", "tool").put("limit", 1), context);
        assertTrue(tools.getOutput().contains("## Read (1)"));
        assertTrue(tools.getOutput().contains("## Write (1)"));
        ToolResult filtered = tool.execute(params("list").put("group_by", "category")
                .put("category", "no-such-category"), context);
        assertFalse(filtered.isError(), filtered.getOutput());
        assertFalse(filtered.getOutput().contains("## filesystem"));
    }

    @Test
    void rejectsInvalidPagingAndUnsupportedGroupsClearly() throws Exception {
        assertTrue(tool.execute(params("search").put("offset", -1), context).isError());
        assertTrue(tool.execute(params("list").put("limit", 501), context).isError());
        assertTrue(tool.execute(params("list").put("group_by", "unknown"), context).isError());
        assertTrue(tool.execute(params("list").put("group_by", "tool").put("offset", 1), context).isError());
    }
}
