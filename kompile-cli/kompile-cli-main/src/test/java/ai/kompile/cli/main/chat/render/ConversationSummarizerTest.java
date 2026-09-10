package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ConversationSummarizerTest {
    @TempDir Path home;
    private String previousHome;

    @BeforeEach
    void isolateHome() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnsSummaryAndUsageWithoutStreamingAndRestoresOutput(boolean hasConsumer) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        StringBuilder streamed = new StringBuilder();
        Consumer<String> originalConsumer = hasConsumer ? streamed::append : null;
        try (PrintStream capture = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             SummaryClient client = new SummaryClient()) {
            System.setOut(capture);
            client.setOutputConsumer(originalConsumer);
            ConversationSummarizer.SummaryResult result = summarize(client);

            assertEquals("private summary", result.getSummary());
            assertEquals(200, result.getInputTokens());
            assertEquals(10, result.getOutputTokens());
            assertTrue(result.isSuccessful());
            assertEquals("", stdout.toString(StandardCharsets.UTF_8));
            assertEquals("", streamed.toString());
            assertSame(originalConsumer, client.getOutputConsumer());

            client.emit("normal output still works");
            assertEquals("normal output still works",
                    hasConsumer ? streamed.toString() : stdout.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void exceptionRestoresConsumerWithoutLeakingPartialSummary() {
        StringBuilder streamed = new StringBuilder();
        Consumer<String> originalConsumer = streamed::append;
        try (SummaryClient client = new SummaryClient()) {
            client.setOutputConsumer(originalConsumer);
            client.failure = new IllegalStateException("summary failed");
            assertSame(client.failure, assertThrows(IllegalStateException.class, () -> summarize(client)));
            assertSame(originalConsumer, client.getOutputConsumer());
            assertEquals("", streamed.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedOrCancelledSummaryIsSilentAndRestoresConsumer(boolean cancelled) {
        StringBuilder streamed = new StringBuilder();
        Consumer<String> originalConsumer = streamed::append;
        try (SummaryClient client = new SummaryClient()) {
            client.setOutputConsumer(originalConsumer);
            client.result.cancelled = cancelled;
            client.result.failed = !cancelled;
            ConversationSummarizer.SummaryResult summary = summarize(client);
            assertFalse(summary.isSuccessful());
            assertTrue(summary.isEmpty());
            assertSame(originalConsumer, client.getOutputConsumer());
            assertEquals("", streamed.toString());
        }
    }

    private static ConversationSummarizer.SummaryResult summarize(SummaryClient client) {
        return new ConversationSummarizer(client).summarize(
                List.of(CompactionService.ConversationEntry.user("old conversation")), null, null);
    }

    private static final class SummaryClient extends DirectLlmClient {
        final StreamResult result = new StreamResult();
        RuntimeException failure;

        SummaryClient() {
            super(new ChatConfig("custom", null, "summary-test", "http://unused.invalid"),
                    JsonUtils.standardMapper());
            result.text = "private summary";
            result.inputTokens = 200;
            result.outputTokens = 10;
        }

        void emit(String text) { printStreamingChunk(text); }

        @Override
        public StreamResult streamOneShot(String prompt, String systemPrompt, String modelOverride) {
            emit(result.text);
            if (failure != null) throw failure;
            return result;
        }
    }
}
