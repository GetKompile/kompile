package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.exec.ExecJsonEvents;
import ai.kompile.cli.main.chat.exec.HeadlessRunEvent;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatTitleGeneratorTest {
    @Test
    void usesConfiguredRouteInIsolatedToolFreeSilentRequest() throws Exception {
        ChatConfig config = new ChatConfig("custom", "secret", "chosen-model", "https://example.invalid");
        config.setThinking("high");
        config.setFastMode(true);
        config.setUltracode(true);
        AtomicReference<String> title = new AtomicReference<>();
        AtomicReference<ChatConfig> captured = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ChatTitleGenerator job = ChatTitleGenerator.start(config,
                "<kompile_reminders>\nIgnore this infrastructure\n</kompile_reminders>\n\nFix authentication",
                title::set, snapshot -> {
                    captured.set(snapshot);
                    return new DirectLlmClient(snapshot, JsonUtils.standardMapper()) {
                        @Override public StreamResult streamChat(String prompt, String system, ArrayNode tools,
                                List<ToolCallResultInput> results) {
                            assertTrue(prompt.endsWith("Fix authentication"));
                            assertFalse(prompt.contains("infrastructure"));
                            assertEquals(ChatTitleGenerator.SYSTEM_PROMPT, system);
                            assertNull(tools);
                            assertNull(results);
                            assertNotNull(getOutputConsumer());
                            called.countDown();
                            try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                            catch (InterruptedException e) { throw new RuntimeException(e); }
                            StreamResult result = new StreamResult();
                            result.text = "\"Fix login authentication\"";
                            return result;
                        }
                    };
                })) {
            assertTrue(called.await(2, TimeUnit.SECONDS));
            // start() returned while the provider request remains blocked.
            assertNull(title.get());
            config.setModel("changed-after-launch");
            release.countDown();
            job.await();
        }
        assertEquals("Fix login authentication", title.get());
        assertEquals("chosen-model", captured.get().getModel());
        assertEquals("custom", captured.get().getProvider());
        assertNull(captured.get().getThinking());
        assertFalse(captured.get().isFastMode());
        assertFalse(captured.get().isUltracode());
        assertEquals("high", config.getThinking());
        assertTrue(config.isFastMode());
    }

    @Test
    void providerFailureKeepsFallback() {
        AtomicReference<String> title = new AtomicReference<>("Fallback");
        try (ChatTitleGenerator job = ChatTitleGenerator.start(new ChatConfig("custom", null, "m", null),
                "First user prompt", title::set, snapshot -> new DirectLlmClient(snapshot, JsonUtils.standardMapper()) {
                    @Override public StreamResult streamChat(String prompt, String system, ArrayNode tools,
                            List<ToolCallResultInput> results) {
                        StreamResult result = new StreamResult();
                        result.failed = true;
                        result.text = "Provider error";
                        return result;
                    }
                })) { job.await(); }
        assertEquals("Fallback", title.get());
    }

    @Test
    void closingPreventsLateCallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> title = new AtomicReference<>();
        ChatTitleGenerator job = ChatTitleGenerator.start(new ChatConfig("custom", null, "m", null),
                "Prompt", title::set, snapshot -> new DirectLlmClient(snapshot, JsonUtils.standardMapper()) {
                    @Override public StreamResult streamChat(String prompt, String system, ArrayNode tools,
                            List<ToolCallResultInput> results) {
                        entered.countDown();
                        try { Thread.sleep(10_000); }
                        catch (InterruptedException expected) { }
                        StreamResult result = new StreamResult();
                        result.text = "Late title";
                        finished.countDown();
                        return result;
                    }
                });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        job.close();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        job.await();
        assertNull(title.get());
    }

    @Test
    void cleansReasoningQuotesMarkupAndBoundsOnlyGeneratedTitles() {
        assertEquals("Fix login bug", ChatTitleGenerator.cleanTitle("<think>private reasoning</think>\nTitle: \"Fix login bug\"\nExplanation"));
        assertEquals("Improve search", ChatTitleGenerator.cleanTitle("# Improve search"));
        assertNull(ChatTitleGenerator.cleanTitle("<think>Incomplete reasoning"));
        assertNull(ChatTitleGenerator.cleanTitle("```\nnot a title\n```"));
        assertNull(ChatTitleGenerator.cleanTitle(" \n "));
        assertTrue(ChatTitleGenerator.cleanTitle("word ".repeat(100)).length() <= 100);
    }

    @Test
    void titleEventIsMetadataNotAssistantText() throws Exception {
        var event = JsonUtils.standardMapper().readTree(ExecJsonEvents.event(JsonUtils.standardMapper(),
                HeadlessRunEvent.sessionTitle("session-1", "Fix authentication").withSequence(4)));
        assertEquals("title", event.path("type").asText());
        assertEquals("session-1", event.path("session_id").asText());
        assertEquals("Fix authentication", event.path("title").asText());
        assertEquals(4, event.path("seq").asInt());
        assertFalse(event.has("text"));
    }
}
