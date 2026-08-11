package ai.kompile.chat.local.sdx;

import ai.kompile.chat.local.ChatRequest;
import ai.kompile.chat.local.ChatResponse;
import ai.kompile.chat.local.ChatToolCall;
import ai.kompile.chat.local.Message;
import ai.kompile.graph.reasoning.unified.MiniJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the adapter cannot override model-import protocol ownership. */
class ChatTemplateTest {

    @Test
    void everyFilenameUsesModelOwnedTemplate() {
        assertEquals(SdxChatModel.ChatTemplate.MODEL_OWNED,
                SdxChatModel.ChatTemplate.sniff("Qwen3.5-0.8B-Q4_K_M.gguf"));
        assertEquals(SdxChatModel.ChatTemplate.MODEL_OWNED,
                SdxChatModel.ChatTemplate.sniff("llama-base.gguf"));
        assertEquals(SdxChatModel.ChatTemplate.MODEL_OWNED,
                SdxChatModel.ChatTemplate.sniff(null));
    }

    @Test
    void legacyTemplateArgumentCannotOverrideImportedModel() {
        SdxChatModel model = new SdxChatModel(
                "/does/not/load.gguf", null,
                SdxChatModel.ChatTemplate.CHATML_IM_NOTHINK);
        assertEquals(SdxChatModel.ChatTemplate.MODEL_OWNED, model.chatTemplate());
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestCarriesStructuredAssistantCallsAndToolResults() {
        ChatResponse call = ChatResponse.toolCalls("raw-model-output", List.of(
                new ChatToolCall("call-7", "graph_search", Map.of("query", "Alice"))));
        ChatRequest request = ChatRequest.of(List.of(
                Message.user("Find Alice"),
                Message.assistant(call),
                Message.toolResult("call-7", "graph_search", "{\"entities\":[]}")),
                "[{\"name\":\"graph_search\",\"description\":\"Search\","
                        + "\"parameters\":{\"type\":\"object\"}}]");

        Map<String, Object> root = MiniJson.parseObject(request.toJson());
        List<Object> messages = (List<Object>) root.get("messages");
        Map<String, Object> assistant = (Map<String, Object>) messages.get(1);
        Map<String, Object> toolResult = (Map<String, Object>) messages.get(2);

        assertTrue(assistant.containsKey("tool_calls"));
        assertEquals("tool", toolResult.get("role"));
        assertEquals("call-7", toolResult.get("tool_call_id"));
        assertFalse(String.valueOf(toolResult.get("content")).contains("TOOL_RESULT"));
    }
}
