package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatToolStatsRoutingTest {
    @Test
    void bareStatsKeepsTheLiveSummaryWithoutAModelTurn() {
        SessionLifecycleManager lifecycle = mock(SessionLifecycleManager.class);
        ChatCommandRouter router = new ChatCommandRouter(null, null, null, lifecycle, null,
                JsonUtils.standardMapper(), "chat-1", true, null, null, null, null, null,
                null, null, null, null, null, null, null, null, List.of(), null);
        assertTrue(router.handleSlashCommand("/stats"));
        verify(lifecycle).printSessionSummary();
        verifyNoMoreInteractions(lifecycle);
    }

    @Test
    void toolsDefaultToTheCurrentSession() {
        var args = ChatCommandRouter.toolStatsArguments(JsonUtils.standardMapper(), "");
        assertEquals("tools", args.path("topic").asText());
        assertEquals("tokens this session", args.path("question").asText());
    }

    @Test
    void explicitSessionAndPaginationArePreservedAndJsonEscaped() throws Exception {
        var mapper = JsonUtils.standardMapper();
        String filters = "session:another tool:read call:call-1 offset:20 last 7 days \"quoted\"";
        var args = ChatCommandRouter.toolStatsArguments(mapper, filters);
        assertEquals("tokens " + filters, mapper.readTree(args.toString()).path("question").asText());
        assertFalse(args.path("question").asText().contains("this session"));
    }

    @Test
    void allSessionsCanBeRequestedExplicitly() {
        var args = ChatCommandRouter.toolStatsArguments(JsonUtils.standardMapper(), "all sessions last 7 days");
        assertEquals("tokens all sessions last 7 days", args.path("question").asText());
    }
}
