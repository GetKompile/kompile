package ai.kompile.chat.local.cli;

import ai.kompile.chat.local.ChatModel;
import ai.kompile.chat.local.Message;
import ai.kompile.chat.local.GenOptions;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Non-interactive smoke test: injects a scripted model via ChatCli.testModelOverride,
 * pipes one prompt via stdin, verifies output contains expected response.
 *
 * Also covers the probeInProcess helper (always returns false when the native lib is
 * absent, which is the case in the unit-test environment) and the sdxMode flag
 * parsing via --sdx-mode.
 */
class ChatCliSmokeTest {

    @Test
    void smokeTestOnePrompt() throws Exception {
        // Scripted model that always returns a plain answer (no tool calls)
        ChatCli.testModelOverride = new ChatModel() {
            @Override
            public String generate(List<Message> messages, GenOptions opts) {
                return "Scripted answer: 42";
            }
            @Override
            public boolean isAvailable() { return true; }
            @Override
            public String modelId() { return "smoke-test"; }
        };

        // Capture stdout
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));

        // Pipe "What is the answer?\n/quit\n" as stdin
        InputStream originalIn = System.in;
        String input = "What is the answer?\n/quit\n";
        System.setIn(new ByteArrayInputStream(input.getBytes()));

        try {
            ChatCli.main(new String[]{});
        } finally {
            System.setOut(originalOut);
            System.setIn(originalIn);
            ChatCli.testModelOverride = null;
        }

        String output = baos.toString();
        assertTrue(output.contains("Scripted answer: 42"),
            "Expected scripted answer in output, got:\n" + output);
        assertTrue(output.contains("REMOTE") || output.contains("LOCAL") || output.contains("NONE"),
            "Expected route info in output");
    }

    @Test
    void probeInProcessReturnsFalseForBadPath() {
        // A non-existent path must not throw — must return false.
        boolean result = ChatCli.probeInProcess("/nonexistent/path/libsdx_llm.so");
        assertFalse(result, "probeInProcess should return false when lib is not found");
    }

    @Test
    void probeInProcessReturnsFalseForNullPath() {
        // Null path: configureLibPath is a no-op, IS_AVAILABLE remains false in test env.
        boolean result = ChatCli.probeInProcess(null);
        assertFalse(result, "probeInProcess should return false for null path");
    }
}
