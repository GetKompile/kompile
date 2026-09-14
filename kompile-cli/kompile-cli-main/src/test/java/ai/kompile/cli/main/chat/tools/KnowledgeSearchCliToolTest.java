package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectCrawlBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeSearchCliToolTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolContext context() {
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("knowledge_search", PermissionService.PermissionLevel.ALLOW);
        return new ToolContext("knowledge-search-test", null, permissions, root, null);
    }

    @Test void localSearchForwardsTopicAndCorpus() throws Exception {
        LocalProjectCrawlBackend local = mock(LocalProjectCrawlBackend.class);
        HttpClient http = mock(HttpClient.class);
        ToolContext context = context();
        ToolResult expected = ToolResult.success("fixture", "matched");
        when(local.search("question", "security", "fixture", 3, context)).thenReturn(expected);
        ToolResult result = new KnowledgeSearchCliTool(null, mapper, local, http).execute(
                mapper.createObjectNode().put("query", "question").put("topic", "security")
                        .put("knowledgeBase", "fixture").put("limit", 3), context);
        assertSame(expected, result);
        verify(local).search("question", "security", "fixture", 3, context);
        verifyNoInteractions(http);
    }

    @Test void remoteConnectionFailureNeverSearchesOrBootstrapsLocalCorpus() throws Exception {
        LocalProjectCrawlBackend local = mock(LocalProjectCrawlBackend.class);
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new ConnectException("fixture refusal"));
        ToolResult result = new KnowledgeSearchCliTool("http://localhost:9999", mapper, local, http)
                .execute(mapper.createObjectNode().put("query", "question"), context());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("no local fallback"));
        verifyNoInteractions(local);
        assertFalse(Files.exists(root.resolve("data")));
    }
}
