/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.app.tools;

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.core.rag.ConversationalRagService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatSessionToolTest {

    @Test
    void createSessionRehydratesPersistedTurnsThroughRagMemory() {
        ConversationalRagService ragService = mock(ConversationalRagService.class);
        ChatSessionTool tool = new ChatSessionTool(
                ragService,
                mock(AgentRegistryService.class),
                mock(BuiltInToolDiscoveryService.class),
                mock(ServerPortService.class));

        List<ChatSessionTool.ChatTurnInput> history = List.of(
                new ChatSessionTool.ChatTurnInput("user", "hello"),
                new ChatSessionTool.ChatTurnInput("assistant", "hi"));
        ChatSessionTool.CreateChatSessionInput input =
                new ChatSessionTool.CreateChatSessionInput(
                        "session-1", null, false, 5, 5, 0.5,
                        true, true, 50, "", history);

        Map<String, Object> result = tool.createChatSession(input);

        assertEquals("success", result.get("status"));
        assertEquals(2, result.get("restoredTurns"));
        @SuppressWarnings("unchecked")
        var messages = (List<Message>) captureHistory(ragService);
        assertEquals(2, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertEquals("hello", messages.get(0).getText());
        assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertEquals("hi", messages.get(1).getText());
    }

    private List<? extends Message> captureHistory(ConversationalRagService ragService) {
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(ragService).replaceConversationHistory(eq("session-1"), captor.capture());
        return captor.getValue();
    }
}
