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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.testing;

import ai.kompile.cli.main.chat.config.AgentSubprocessClient;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test harness for subprocess-based agent interactions.
 * Wraps a {@link TestableSubprocessClient} with stdout capture and assertion helpers.
 */
public class SubprocessTestHarness implements AutoCloseable {

    private final TestableSubprocessClient client;
    private final ByteArrayOutputStream capturedStdout = new ByteArrayOutputStream();
    private DirectLlmClient.StreamResult lastResult;

    private SubprocessTestHarness(TestableSubprocessClient client) {
        this.client = client;
    }

    // ========================================================================
    // Builder
    // ========================================================================

    public static Builder forAgent(String agentName) {
        return new Builder(agentName);
    }

    public static class Builder {
        private final String agentName;
        private String workingDirectory = System.getProperty("user.dir");
        private ObjectMapper objectMapper = new ObjectMapper();
        private String[] command;
        private AtomicBoolean cancelSignal;

        Builder(String agentName) {
            this.agentName = agentName;
        }

        public Builder script(MockAgentScript script) {
            this.command = script.toCommand();
            return this;
        }

        public Builder command(String... command) {
            this.command = command;
            return this;
        }

        public Builder workingDirectory(String dir) {
            this.workingDirectory = dir;
            return this;
        }

        public Builder objectMapper(ObjectMapper om) {
            this.objectMapper = om;
            return this;
        }

        public Builder cancelSignal(AtomicBoolean signal) {
            this.cancelSignal = signal;
            return this;
        }

        public SubprocessTestHarness build() {
            if (command == null) {
                throw new IllegalStateException("No script or command provided");
            }
            TestableSubprocessClient client = new TestableSubprocessClient(
                    agentName, workingDirectory, objectMapper, command);
            if (cancelSignal != null) {
                client.setCancelSignal(cancelSignal);
            }
            return new SubprocessTestHarness(client);
        }
    }

    // ========================================================================
    // Execution
    // ========================================================================

    /**
     * Run a single chat message through the subprocess agent.
     * Captures stdout during execution.
     */
    public DirectLlmClient.StreamResult chat(String message) {
        PrintStream originalOut = System.out;
        capturedStdout.reset();
        try {
            System.setOut(new PrintStream(capturedStdout, true));
            lastResult = client.streamChat(message, null, null, null, null);
            return lastResult;
        } finally {
            System.setOut(originalOut);
        }
    }

    /**
     * Get the raw stdout captured during the last {@link #chat(String)} call.
     */
    public String getCapturedStdout() {
        return capturedStdout.toString();
    }

    /**
     * Get the {@link DirectLlmClient.StreamResult} from the last call.
     */
    public DirectLlmClient.StreamResult getLastResult() {
        return lastResult;
    }

    // ========================================================================
    // Assertions
    // ========================================================================

    /** Assert that captured stdout contains the given text. */
    public SubprocessTestHarness assertOutputContains(String text) {
        String stdout = getCapturedStdout();
        if (!stdout.contains(text)) {
            throw new AssertionError("Expected stdout to contain '" + text
                    + "' but got:\n" + truncate(stdout, 1000));
        }
        return this;
    }

    /** Assert that captured stdout does NOT contain the given text. */
    public SubprocessTestHarness assertOutputNotContains(String text) {
        String stdout = getCapturedStdout();
        if (stdout.contains(text)) {
            throw new AssertionError("Expected stdout NOT to contain '" + text
                    + "' but it was found in:\n" + truncate(stdout, 1000));
        }
        return this;
    }

    /** Assert that captured stdout matches the given regex. */
    public SubprocessTestHarness assertOutputMatchesRegex(String regex) {
        String stdout = getCapturedStdout();
        if (!stdout.matches("(?s)" + regex)) {
            throw new AssertionError("Expected stdout to match regex '" + regex
                    + "' but got:\n" + truncate(stdout, 1000));
        }
        return this;
    }

    /** Assert the result text contains the given substring. */
    public SubprocessTestHarness assertResultContains(String text) {
        requireResult();
        if (!lastResult.text.contains(text)) {
            throw new AssertionError("Expected result text to contain '" + text
                    + "' but got: " + truncate(lastResult.text, 500));
        }
        return this;
    }

    /** Assert the result text equals the given string exactly. */
    public SubprocessTestHarness assertResultEquals(String expected) {
        requireResult();
        if (!expected.equals(lastResult.text)) {
            throw new AssertionError("Expected result text '" + expected
                    + "' but got: '" + lastResult.text + "'");
        }
        return this;
    }

    /** Assert token usage values on the last result. */
    public SubprocessTestHarness assertTokenUsage(long expectedInput, long expectedOutput) {
        requireResult();
        if (lastResult.inputTokens != expectedInput) {
            throw new AssertionError("Expected inputTokens=" + expectedInput
                    + " but got " + lastResult.inputTokens);
        }
        if (lastResult.outputTokens != expectedOutput) {
            throw new AssertionError("Expected outputTokens=" + expectedOutput
                    + " but got " + lastResult.outputTokens);
        }
        return this;
    }

    /** Assert cache token values on the last result. */
    public SubprocessTestHarness assertCacheTokens(long expectedRead, long expectedCreation) {
        requireResult();
        if (lastResult.cacheReadTokens != expectedRead) {
            throw new AssertionError("Expected cacheReadTokens=" + expectedRead
                    + " but got " + lastResult.cacheReadTokens);
        }
        if (lastResult.cacheCreationTokens != expectedCreation) {
            throw new AssertionError("Expected cacheCreationTokens=" + expectedCreation
                    + " but got " + lastResult.cacheCreationTokens);
        }
        return this;
    }

    /** Assert that the result was cancelled. */
    public SubprocessTestHarness assertCancelled() {
        requireResult();
        if (!lastResult.cancelled) {
            throw new AssertionError("Expected result to be cancelled");
        }
        return this;
    }

    /** Assert that a tool call rendering appeared in stdout. */
    public SubprocessTestHarness assertToolCallRendered(String toolName) {
        String stdout = getCapturedStdout();
        if (!stdout.contains("[tool: " + toolName)) {
            throw new AssertionError("Expected tool call rendering for '" + toolName
                    + "' in stdout but got:\n" + truncate(stdout, 1000));
        }
        return this;
    }

    // ========================================================================
    // Cleanup
    // ========================================================================

    @Override
    public void close() {
        client.close();
    }

    // ========================================================================
    // Internals
    // ========================================================================

    private void requireResult() {
        if (lastResult == null) {
            throw new IllegalStateException("No chat result — call chat() first");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return ai.kompile.utils.StringUtils.truncateWithSize(s, max);
    }

    /**
     * Testable subclass of AgentSubprocessClient that overrides binary resolution
     * and command building for deterministic unit tests.
     */
    static class TestableSubprocessClient extends AgentSubprocessClient {
        private final String[] overrideCommand;

        TestableSubprocessClient(String agent, String workingDir, ObjectMapper objectMapper,
                                 String... command) {
            super(agent, workingDir, objectMapper, command[0]);
            this.overrideCommand = command;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        protected List<String> buildCommand(String message) {
            return Arrays.asList(overrideCommand);
        }
    }
}
