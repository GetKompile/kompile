package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DirectLlmClientNativeLimitTest {
    @TempDir Path home;

    @Test void weeklyQuotaBeforeOutputIsTypedReplaySafeAndKeepsNativeSession() throws Exception {
        check(false);
    }

    @Test void weeklyQuotaAfterNativeToolActivityIsNeverReplaySafe() throws Exception {
        check(true);
    }

    private void check(boolean toolActivity) throws Exception {
        String original = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            FakeClaudeCode fake = new FakeClaudeCode(home.resolve("claude"), """
                    turn() {
                      say_init
                    """ + (toolActivity ? """
                      emit '{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"Bash","input":{}}}}'
                    """ : "") + """
                      emit '{"type":"assistant","message":{"model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"Weekly usage limit reached; resets_in_seconds=604800"}]},"parent_tool_use_id":null,"error":"rate_limit","is_api_error_message":true}'
                      emit '{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":1,"errors":["Request failed"]}'
                    }
                    """);
            ChatConfig config = new ChatConfig("anthropic", null, "claude-sonnet-4-6", null);
            config.setAuthenticationMethod("oauth");
            try (DirectLlmClient client = new DirectLlmClient(config, JsonUtils.standardMapper(), home)) {
                ClaudeCliClient nativeClient = new ClaudeCliClient(home, null, fake.binary());
                var field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
                field.setAccessible(true);
                field.set(client, nativeClient);
                var result = client.streamChat("continue the task", "system rules", null, null);
                assertTrue(result.failed);
                assertEquals(DirectLlmClient.FailureKind.QUOTA_EXHAUSTED, result.failureKind);
                assertEquals(!toolActivity, result.isReplaySafe());
                assertTrue(result.failureMessage.contains("resets_in_seconds=604800"), result.failureMessage);
                assertTrue(nativeClient.processAlive());
                assertNotNull(nativeClient.nativeSession());
                assertEquals(1, fake.messages().size(), "quota is terminal, not a connectivity retry");
            }
        } finally { System.setProperty("user.home", original); }
    }
}
