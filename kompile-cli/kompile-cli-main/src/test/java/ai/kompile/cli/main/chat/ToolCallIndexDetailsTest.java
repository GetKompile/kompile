package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.metrics.ToolInvocationDetails;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("user.home")
class ToolCallIndexDetailsTest {
    @Test
    void localResultsUseExactCatalogIdAndKeepUnshortenedOutput(@TempDir Path temp) throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", temp.toString());
        try {
            ToolCallIndex index = ToolCallIndex.getInstance();
            index.recordWithDetails("session", "read", "{\"file_path\":\"local.java\"}", "codex", "local-chat",
                    false, 25, temp.toString(), "provider-id", ToolResult.success("read", "shortened", Map.of("lines", 42)),
                    ToolResult.success("read", "full original result"));
            var calls = index.search(null, null, "session", null, null, null,
                    ToolCallIndex.SortField.TIMESTAMP, ToolCallIndex.SortDirection.DESC, 10);
            assertEquals(1, calls.size());
            var detail = new ToolInvocationDetails(KompileHome.homeDirectory().toPath()
                    .resolve("conversations/tool-calls")).read("session", calls.get(0).getId());
            assertEquals("provider-id", detail.path("context").path("clientRequestId").asText());
            assertTrue(detail.path("arguments").path("text").asText().contains("local.java"));
            assertEquals("shortened", detail.path("output").path("text").asText());
            assertEquals("full original result", detail.path("rawOutput").path("text").asText());
            assertTrue(detail.path("structured").path("text").asText().contains("42"));
            assertNull(calls.get(0).getUsage(), "Content capture does not invent token usage");
        } finally {
            System.setProperty("user.home", home);
        }
    }
}
