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
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A harness event sent to a CLI agent as its prompt used to show only the reminders
 * attached to it, so the event itself was invisible in the chat.
 */
@TemporaryUserHome
class SubprocessAgentRunnerNotificationDisplayTest {

    @TempDir
    Path tempDir;

    @Test
    void aSystemEventPromptShowsItsNotificationAndUserTextShowsNothingExtra() {
        TerminalRenderer renderer = new TerminalRenderer(false);
        SubprocessAgentRunner runner = new SubprocessAgentRunner("claude", tempDir.toString(), true, true,
                "", 8123, null, renderer, new AsciiRenderer(renderer, 100));
        List<String> output = new ArrayList<>();
        runner.setOutputConsumer(output::add);
        try {
            runner.emitAgentNotification("[System background task completion]\n"
                    + "Background task t7 finished with state completed.\nResult log: /tmp/t7.log");
            assertEquals(1, output.size(), String.join("\n", output));
            assertTrue(output.get(0).contains("Agent notification · Background task completion"), output.get(0));
            assertTrue(output.get(0).contains("Background task t7 finished with state completed."), output.get(0));

            output.clear();
            runner.emitAgentNotification("Fix the failing build");
            runner.emitAgentNotification(null);
            assertEquals(List.of(), output);
        } finally {
            runner.cleanup();
        }
    }
}
