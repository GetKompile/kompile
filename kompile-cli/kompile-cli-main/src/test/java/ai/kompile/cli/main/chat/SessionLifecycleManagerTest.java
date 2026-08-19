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
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLifecycleManagerTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void useIsolatedHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void restoresTranscriptThroughCallerProvidedOutputSink() throws Exception {
        String sessionId = "resume-render-test";
        ChatHistory history = new ChatHistory(sessionId);
        history.open("(local)", "coder", false);
        history.logUserMessage("the prior user question");
        history.logAssistantMessage("the prior **assistant answer** with `code`", 0, 0);
        history.close();

        TerminalRenderer renderer = new TerminalRenderer(false);
        SessionLifecycleManager manager = new SessionLifecycleManager(
                null,
                sessionId,
                false,
                history,
                null,
                renderer,
                new AsciiRenderer(renderer),
                null,
                null,
                null);

        List<String> renderedLines = new ArrayList<>();
        manager.restoreSession(renderedLines::add);

        String rendered = String.join("\n", renderedLines);
        assertTrue(rendered.contains("the prior user question"));
        assertTrue(rendered.contains("the prior assistant answer"));
        assertTrue(rendered.contains("You:"));
        assertTrue(rendered.contains("Assistant:"));
        assertTrue(!rendered.contains("> the prior user question"));
        assertTrue(!rendered.contains("< the prior **assistant answer** with `code`"));
        assertTrue(rendered.contains("end of previous conversation (2 turns)"));
    }
}
