/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The launch dialog contract: the opt-in question is asked by the setup wizard
 * and the workflow-mode picker only — never on {@code --resume}/{@code
 * --continue} or other explicit-action launches, and never twice in one launch.
 */
class WorkflowLaunchScopingTest {

    private static ChatCommand parse(String... args) {
        ChatCommand command = new ChatCommand();
        new CommandLine(command).parseArgs(args);
        return command;
    }

    @Test
    void resolveWorkflowNeverPromptsWithoutTheFlag() {
        // Interactive process (headless=false), no --workflow: must return null
        // without opening any terminal dialog.
        assertNull(parse().resolveWorkflow(false));
        assertNull(parse("--resume", "some-session").resolveWorkflow(false));
        assertNull(parse("--mode", "standard").resolveWorkflow(false));
    }

    @Test
    void explicitWorkflowFlagStillResolvesAndAbortsCleanly() {
        // Unknown names abort rather than start unenforced.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("--workflow", "no-such-team").resolveWorkflow(false));
        assertTrue(error.getMessage().contains("No workflow named 'no-such-team'"));
    }

    @Test
    void headlessLaunchesNeverPromptEvenWithWorkflowSupport() {
        assertNull(parse().resolveWorkflow(true));
    }
}
