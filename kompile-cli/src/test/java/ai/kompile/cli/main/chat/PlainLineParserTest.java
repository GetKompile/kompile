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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.PassthroughStreamParser.*;
import ai.kompile.cli.main.chat.testing.AgentOutputAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PassthroughStreamParser#parsePlainLine(String, Pattern)}.
 * <p>
 * Covers: null input, plain text → TextChunk with newline, prompt pattern detection
 * returning PromptDetected, various prompt patterns, and edge cases.
 */
class PlainLineParserTest {

    private PassthroughStreamParser parser;

    @BeforeEach
    void setUp() {
        parser = new PassthroughStreamParser();
    }

    // ===================================================================
    // Null input
    // ===================================================================

    @Test
    void nullLine_shouldReturnNull() {
        assertNull(parser.parsePlainLine(null, null));
    }

    @Test
    void nullLineWithPattern_shouldReturnNull() {
        Pattern prompt = Pattern.compile("^> $");
        assertNull(parser.parsePlainLine(null, prompt));
    }

    // ===================================================================
    // Plain text → TextChunk with newline appended
    // ===================================================================

    @Test
    void plainText_shouldReturnTextChunkWithNewline() {
        PassthroughEvent event = parser.parsePlainLine("Hello world", null);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("Hello world\n");
    }

    @Test
    void emptyString_shouldReturnTextChunkWithNewline() {
        PassthroughEvent event = parser.parsePlainLine("", null);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("\n");
    }

    @Test
    void whitespaceOnly_shouldReturnTextChunkWithNewline() {
        PassthroughEvent event = parser.parsePlainLine("   ", null);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("   \n");
    }

    @Test
    void multilineContent_shouldReturnTextChunkWithNewline() {
        // parsePlainLine processes one line at a time, but the line itself
        // might contain embedded content
        String line = "public class Foo { int x = 1; }";
        PassthroughEvent event = parser.parsePlainLine(line, null);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk(line + "\n");
    }

    @Test
    void specialCharacters_shouldReturnTextChunkWithNewline() {
        String line = "Error: file not found — check path [/tmp/test.txt]";
        PassthroughEvent event = parser.parsePlainLine(line, null);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk(line + "\n");
    }

    @Test
    void ansiEscapes_shouldReturnTextChunkWithNewline() {
        String line = "\033[1m\033[31mBold Red\033[0m normal";
        PassthroughEvent event = parser.parsePlainLine(line, null);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk(line + "\n");
    }

    // ===================================================================
    // Prompt pattern detection → PromptDetected
    // ===================================================================

    @Test
    void promptPatternMatch_shouldReturnPromptDetected() {
        Pattern prompt = Pattern.compile("^> $");
        PassthroughEvent event = parser.parsePlainLine("> ", prompt);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasPromptDetected();
    }

    @Test
    void promptPatternNoMatch_shouldReturnTextChunk() {
        Pattern prompt = Pattern.compile("^> $");
        PassthroughEvent event = parser.parsePlainLine("Hello world", prompt);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("Hello world\n");
    }

    @Test
    void complexPromptPattern_shouldDetect() {
        // Simulates a shell-style prompt like "user@host:~$ "
        Pattern prompt = Pattern.compile("^\\w+@\\w+:.*\\$\\s*$");
        PassthroughEvent event = parser.parsePlainLine("user@host:~/project$ ", prompt);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasPromptDetected();
    }

    @Test
    void complexPromptPattern_noMatchOnContent() {
        Pattern prompt = Pattern.compile("^\\w+@\\w+:.*\\$\\s*$");
        PassthroughEvent event = parser.parsePlainLine("The file contains user@host references", prompt);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("The file contains user@host references\n");
    }

    @Test
    void pythonPromptPattern_shouldDetect() {
        Pattern prompt = Pattern.compile("^>>> $");
        PassthroughEvent event = parser.parsePlainLine(">>> ", prompt);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasPromptDetected();
    }

    @Test
    void nullPatternAlwaysTextChunk() {
        // Even if the line looks like a prompt, without a pattern it's just text
        PassthroughEvent event = parser.parsePlainLine("> ", null);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("> \n");
    }

    // ===================================================================
    // Concrete event type verification
    // ===================================================================

    @Test
    void textChunkInstance_hasCorrectText() {
        PassthroughEvent event = parser.parsePlainLine("test line", null);
        assertInstanceOf(TextChunk.class, event);
        TextChunk chunk = (TextChunk) event;
        assertEquals("test line\n", chunk.text());
    }

    @Test
    void promptDetectedInstance_isCorrectType() {
        Pattern prompt = Pattern.compile("^\\$ $");
        PassthroughEvent event = parser.parsePlainLine("$ ", prompt);
        assertInstanceOf(PromptDetected.class, event);
    }
}
