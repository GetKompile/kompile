package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AuxiliaryChatReplTest {

    @Test
    void streamsIndependentTurnIntoLiveTranscript() throws Exception {
        AuxiliaryChatRepl repl = AuxiliaryChatRepl.withRunner(
                AuxiliaryChatRepl.Kind.JUDGE,
                "test/model",
                (user, system, stream) -> {
                    stream.accept("{\"action\":");
                    stream.accept("\"ALLOW\"}");
                    return "{\"action\":\"ALLOW\"}";
                });
        AtomicInteger changes = new AtomicInteger();
        repl.addChangeListener(changes::incrementAndGet);

        String response = repl.generate("Review tool read", "Return JSON");

        assertEquals("{\"action\":\"ALLOW\"}", response);
        assertEquals("ready", repl.status());
        assertTrue(repl.transcript().contains("Review tool read"));
        assertTrue(repl.transcript().contains("{\"action\":\"ALLOW\"}"));
        assertTrue(changes.get() >= 3, "request, streamed chunks, and completion should repaint");
        repl.close();
    }

    @Test
    void sendsFeedbackDirectlyThroughMainChatControl() {
        AuxiliaryChatRepl repl = AuxiliaryChatRepl.observer(
                AuxiliaryChatRepl.Kind.ENFORCER, "ready · keyword");
        AtomicReference<String> source = new AtomicReference<>();
        AtomicReference<String> feedback = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        repl.setMainChatControl((actualSource, actualFeedback, interrupt) -> {
            source.set(actualSource);
            feedback.set(actualFeedback);
            interrupted.set(interrupt);
            return true;
        });

        assertTrue(repl.sendFeedback("Use a compliant alternative", true));
        assertEquals("enforcer", source.get());
        assertEquals("Use a compliant alternative", feedback.get());
        assertTrue(interrupted.get());
        assertTrue(repl.transcript().contains("feedback -> main · interrupt"));

        repl.close();
        assertEquals("closed", repl.status());
        assertFalse(repl.isAvailable());
    }

    @Test
    void modelBackedLabelsIncludeJudgeEffortForEveryAuxiliaryKind() {
        for (AuxiliaryChatRepl.Kind kind : AuxiliaryChatRepl.Kind.values()) {
            RecordingClient client = new RecordingClient();
            client.getChatConfig().setThinking("xhigh");
            AuxiliaryChatRepl repl = AuxiliaryChatRepl.modelBacked(kind, client, "override-model");
            try {
                String label = "custom/override-model · thinking/effort: xhigh";
                assertTrue(repl.describe().contains(label));
                assertTrue(repl.verdictBackend().describe().contains(label));
                assertTrue(repl.transcript().contains(label));
            } finally {
                repl.close();
            }
        }
    }

    @Test
    void unsetEffortIsLabelledProviderDefaultNotOff() {
        for (String thinking : new String[] {null, "", "  "}) {
            RecordingClient client = new RecordingClient();
            client.getChatConfig().setThinking(thinking);
            AuxiliaryChatRepl repl = AuxiliaryChatRepl.modelBacked(
                    AuxiliaryChatRepl.Kind.JUDGE, client, null);
            try {
                assertTrue(repl.describe().contains("judge-test · thinking/effort: provider default"));
            } finally {
                repl.close();
            }
        }
    }

    @Test
    void modelBackedReplUsesChatHistoryAndResetsItAcrossProviderRouteChanges()
            throws Exception {
        RecordingClient client = new RecordingClient();
        AuxiliaryChatRepl repl = AuxiliaryChatRepl.modelBacked(
                AuxiliaryChatRepl.Kind.JUDGE, client, null);

        assertEquals("first", repl.generate("review one", "judge system"));
        assertEquals(0, client.clearCalls.get(), "display metadata must not change the route identity");
        client.getChatConfig().setModel("judge-test-2");
        assertEquals("second", repl.generate("review two", "judge system"));

        assertEquals(2, client.chatCalls.get());
        assertEquals(0, client.oneShotCalls.get());
        assertEquals(1, client.clearCalls.get());
        assertTrue(repl.transcript().contains("first"));
        assertTrue(repl.transcript().contains("second"));
        repl.close();
    }

    @Test
    void machineVerdictsUseOneShotWhileJudgeChatRemainsConversational() throws Exception {
        RecordingClient client = new RecordingClient();
        AuxiliaryChatRepl repl = AuxiliaryChatRepl.modelBacked(
                AuxiliaryChatRepl.Kind.JUDGE, client, null);
        JudgeBackend verdicts = repl.verdictBackend();
        JsonNode schema = JsonUtils.standardMapper().createObjectNode()
                .put("type", "object");

        assertEquals("verdict", verdicts.generateJson(
                "review tool", "strict JSON",
                new JudgeBackend.JsonSchema("test_verdict", schema, true)));
        assertEquals(1, client.oneShotCalls.get());
        assertEquals(1, client.structuredCalls.get());
        assertEquals(0, client.chatCalls.get());

        assertEquals("first", repl.generate("why did you block it?", "judge chat"));
        assertEquals(1, client.chatCalls.get());
        assertTrue(repl.transcript().contains("verdict"));
        assertTrue(repl.transcript().contains("first"));
        repl.close();
    }

    @Test
    void providerFailureMarksAuxiliaryReplFailedInsteadOfReady() {
        DirectLlmClient client = new DirectLlmClient(
                new ChatConfig("custom", null, "judge-test", "http://unused.invalid"),
                JsonUtils.standardMapper()) {
            @Override
            public StreamResult streamOneShotJson(
                    String prompt, String systemPrompt, String modelOverride,
                    String schemaName, JsonNode schema, boolean strict) {
                StreamResult result = new StreamResult();
                result.failed = true;
                result.failureMessage = "[OpenAI Codex API error 429: usage limit reached]";
                result.text = result.failureMessage;
                return result;
            }
        };
        AuxiliaryChatRepl repl = AuxiliaryChatRepl.modelBacked(
                AuxiliaryChatRepl.Kind.JUDGE, client, null);
        JudgeBackend.JsonSchema schema = new JudgeBackend.JsonSchema(
                "test", JsonUtils.standardMapper().createObjectNode().put("type", "object"), true);

        Exception failure = assertThrows(Exception.class,
                () -> repl.verdictBackend().generateJson("review", "system", schema));

        assertTrue(failure.getMessage().contains("429"));
        assertEquals("failed", repl.status());
        assertTrue(repl.failureReason().contains("429"));
        repl.close();
    }

    private static final class RecordingClient extends DirectLlmClient {
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final AtomicInteger oneShotCalls = new AtomicInteger();
        private final AtomicInteger structuredCalls = new AtomicInteger();
        private final AtomicInteger clearCalls = new AtomicInteger();

        private RecordingClient() {
            super(new ChatConfig("custom", null, "judge-test", "http://unused.invalid"),
                    JsonUtils.standardMapper());
        }

        @Override
        public StreamResult streamChat(
                String userMessage, String systemPrompt, ArrayNode toolDefs,
                List<ToolCallResultInput> toolResults, String modelOverride) {
            int call = chatCalls.incrementAndGet();
            String text = call == 1 ? "first" : "second";
            if (getOutputConsumer() != null) getOutputConsumer().accept(text);
            StreamResult result = new StreamResult();
            result.text = text;
            return result;
        }

        @Override
        public StreamResult streamOneShot(
                String prompt, String systemPrompt, String modelOverride) {
            oneShotCalls.incrementAndGet();
            if (getOutputConsumer() != null) getOutputConsumer().accept("verdict");
            StreamResult result = new StreamResult();
            result.text = "verdict";
            return result;
        }

        @Override
        public StreamResult streamOneShotJson(
                String prompt, String systemPrompt, String modelOverride,
                String schemaName, JsonNode schema, boolean strict) {
            structuredCalls.incrementAndGet();
            assertEquals("test_verdict", schemaName);
            assertTrue(schema.isObject());
            assertTrue(strict);
            return streamOneShot(prompt, systemPrompt, modelOverride);
        }

        @Override
        public void clearHistory() {
            clearCalls.incrementAndGet();
            super.clearHistory();
        }
    }
}
