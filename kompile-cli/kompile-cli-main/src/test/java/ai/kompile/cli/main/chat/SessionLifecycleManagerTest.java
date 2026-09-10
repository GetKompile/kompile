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

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        String longUserBody = "the prior user question " + "x".repeat(240)
                + " FULL_STANDARD_USER_TAIL";
        history.logUserMessage(longUserBody);
        history.logAssistantMessage("the prior **assistant answer** with `code`", 0, 0);
        for (int index = 2; index < 12; index++) {
            String roleContent = "complete restored turn " + index;
            if (index % 2 == 0) {
                history.logUserMessage(roleContent);
            } else {
                history.logAssistantMessage(roleContent, 0, 0);
            }
        }
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
        assertTrue(rendered.contains("FULL_STANDARD_USER_TAIL"),
                "standard resume must preserve the complete turn body");
        assertTrue(rendered.contains("the prior assistant answer"));
        assertTrue(rendered.contains("complete restored turn 2"));
        assertTrue(rendered.contains("complete restored turn 11"));
        assertTrue(rendered.contains("You:"));
        assertTrue(rendered.contains("Assistant:"));
        assertTrue(!rendered.contains("> the prior user question"));
        assertTrue(!rendered.contains("< the prior **assistant answer** with `code`"));
        assertTrue(!rendered.contains("earlier turns"));
        assertTrue(rendered.contains("end of previous conversation (12 turns)"));
    }

    @Test
    void restoredConversationSchedulesRestartAwarenessForOnlyTheFirstOutboundTurn()
            throws Exception {
        String sessionId = "resume-reminder-test";
        ChatHistory history = new ChatHistory(sessionId);
        history.open("(local)", "kompile", false, tempDir);
        history.logUserMessage("unfinished work");
        history.logAssistantMessage("work in progress", 0, 0);
        history.close();

        ChatConfig config = new ChatConfig(
                "custom", null, "resume-reminder-test", "http://unused.invalid");
        ChatRepl repl = new ChatRepl(
                null, null, sessionId, false, "kompile", false, config, tempDir);
        try {
            TerminalRenderer renderer = new TerminalRenderer(false);
            SessionLifecycleManager manager = new SessionLifecycleManager(
                    repl, sessionId, false, history, new ChatSessionMetrics(sessionId),
                    renderer, new AsciiRenderer(renderer), null, null, null);

            manager.restoreSession(ignored -> { });

            ReminderManager reminders = repl.getReminderManager();
            assertTrue(reminders.previewUserTurn("Continue").contains(
                    "[system] " + ReminderManager.SESSION_RESUMED_REMINDER));
            assertTrue(reminders.decorateUserTurn("Continue").contains(
                    "[system] " + ReminderManager.SESSION_RESUMED_REMINDER));
            assertEquals("Then continue", reminders.decorateUserTurn("Then continue"));
        } finally {
            field(repl, "processManager", BackgroundProcessManager.class).close();
            DirectLlmClient client = field(repl, "directClient", DirectLlmClient.class);
            if (client != null) client.close();
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void liveStatsDoNotExitRegistryAndFinalSummaryUsesRecordedProject() throws Exception {
        String sessionId = "live-stats-session";
        Path project = tempDir.resolve("explicit project").toAbsolutePath().normalize();
        Files.createDirectories(project);
        ChatHistory history = new ChatHistory(sessionId);
        history.open("(local)", "kompile", false, project);
        ChatConfig config = new ChatConfig(
                "custom", null, "stats-test", "http://unused.invalid");
        ChatRepl repl = new ChatRepl(
                null, null, sessionId, false, "kompile", false, config, project);
        try {
            TerminalRenderer renderer = new TerminalRenderer(false);
            SessionLifecycleManager manager = new SessionLifecycleManager(
                    repl, sessionId, true, history, new ChatSessionMetrics(sessionId),
                    renderer, new AsciiRenderer(renderer), null, null, null);
            manager.registerSession();

            SessionEntry registered = SessionRegistry.load().get(sessionId).orElseThrow();
            assertEquals(project.toString(), registered.getProjectDirectory());
            assertEquals("running", registered.getStatus());

            manager.printSessionSummary();
            assertEquals("running", SessionRegistry.load().get(sessionId)
                    .orElseThrow().getStatus(), "/stats must not make a live chat resumable");

            manager.printSessionSummary(false);
            assertEquals("exited", SessionRegistry.load().get(sessionId)
                    .orElseThrow().getStatus());
        } finally {
            history.close();
            field(repl, "processManager", BackgroundProcessManager.class).close();
            DirectLlmClient client = field(repl, "directClient", DirectLlmClient.class);
            if (client != null) client.close();
            ChatCompleter.setActivity(null);
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
