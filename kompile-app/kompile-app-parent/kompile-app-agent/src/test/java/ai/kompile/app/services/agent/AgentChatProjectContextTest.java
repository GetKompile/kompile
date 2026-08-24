package ai.kompile.app.services.agent;

import ai.kompile.app.web.dto.AgentChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentChatProjectContextTest {

    @Test
    void requestContractAcceptsCliSystemPromptOverride() throws Exception {
        AgentChatRequest request = new ObjectMapper().readValue("""
                {
                  "message": "hello",
                  "agentName": "codex",
                  "systemPromptOverride": "PROJECT_CONTEXT_MARKER"
                }
                """, AgentChatRequest.class);

        assertEquals("PROJECT_CONTEXT_MARKER", request.getSystemPromptOverride());
    }
}
