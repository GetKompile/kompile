package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TemporaryUserHome
class PassthroughCommandTokenUsageTest {
    @TempDir Path directory;

    @Test
    void qwenNativeUsageCountsCachedPromptOnceAndIncludesThinking() throws Exception {
        Path transcript = directory.resolve("qwen.jsonl");
        Files.writeString(transcript, """
                {"type":"assistant","usageMetadata":{"promptTokenCount":1000,"candidatesTokenCount":20,"thoughtsTokenCount":80,"cachedContentTokenCount":900}}
                {"type":"system","subtype":"ui_telemetry","systemPayload":{"uiEvent":{"event.name":"api_response","input_token_count":1000,"output_token_count":100,"cached_content_token_count":900}}}
                """);
        var command = new PassthroughCommand();
        var metrics = new ChatSessionMetrics("qwen-usage");
        var parse = PassthroughCommand.class.getDeclaredMethod("parseQwenJsonl",
                File.class, ChatHistory.class, ChatSessionMetrics.class);
        parse.setAccessible(true);
        parse.invoke(command, transcript.toFile(), new ChatHistory("qwen-usage"), metrics);
        assertEquals(100, metrics.getInputTokens());
        assertEquals(900, metrics.getCacheReadTokens());
        assertEquals(100, metrics.getOutputTokens());
        assertEquals(1100, metrics.getTotalTokens());
    }
}
