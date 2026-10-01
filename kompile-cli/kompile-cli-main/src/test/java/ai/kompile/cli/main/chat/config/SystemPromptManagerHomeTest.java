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

package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The system prompt files under {@code ~/.kompile} follow the {@code user.home} current when
 * they are used, each manager writes its own temp prompt file per agent prompt, and the temp
 * prompt files of a process that exited without cleaning up are reclaimed by the next process
 * that writes one.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SystemPromptManagerHomeTest {

    @TempDir
    Path homes;

    @Test
    void configuredPromptsFollowTheCurrentHome() throws Exception {
        Path first = homes.resolve("first");
        Path second = homes.resolve("second");
        Path secondPrompts = second.resolve(".kompile").resolve("system-prompts");
        Files.createDirectories(secondPrompts);
        Files.writeString(second.resolve(".kompile").resolve("system-prompt.md"), "SECOND_HOME_PROMPT");
        Files.writeString(secondPrompts.resolve("claude.md"), "SECOND_HOME_CLAUDE_PROMPT");
        String original = System.getProperty("user.home");
        try {
            // The class can first load while an earlier home is current.
            System.setProperty("user.home", first.toString());
            assertNull(SystemPromptManager.resolve(null, null, null));

            System.setProperty("user.home", second.toString());
            SystemPromptManager manager = SystemPromptManager.resolve(null, null, null);

            assertNotNull(manager);
            assertEquals("SECOND_HOME_PROMPT\n\nSECOND_HOME_CLAUDE_PROMPT",
                    manager.resolveForAgent("claude"));
        } finally {
            restoreHome(original);
        }
    }

    @Test
    void promptFileReclaimsFilesLeftByExitedProcesses() throws Exception {
        Path home = homes.resolve("home");
        Path tmp = home.resolve(".kompile").resolve("tmp");
        Files.createDirectories(tmp);
        long dead = spawnDeadProcess();
        Path exited = tmp.resolve("system-prompt-" + dead + "-42.md");
        Path exitedOlderName = tmp.resolve("system-prompt-" + dead + ".md");
        Path running = tmp.resolve("system-prompt-"
                + ProcessHandle.current().parent().orElseThrow().pid() + "-42.md");
        Path unrelated = tmp.resolve("system-prompt-notes.md");
        for (Path file : List.of(exited, exitedOlderName, running, unrelated)) {
            Files.writeString(file, "OLD_PROMPT");
        }
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            SystemPromptManager manager = SystemPromptManager.resolve("PROMPT", null, null);

            List<String> args = manager.getExtraArgs("claude");

            assertEquals(2, args.size(), args.toString());
            assertEquals("--append-system-prompt-file", args.get(0));
            Path own = Path.of(args.get(1));
            assertEquals(tmp, own.getParent());
            assertTrue(own.getFileName().toString()
                    .startsWith("system-prompt-" + ProcessHandle.current().pid() + "-"), own.toString());
            assertEquals("PROMPT", Files.readString(own));
            assertFalse(Files.exists(exited), "an exited process's prompt file is reclaimed");
            assertFalse(Files.exists(exitedOlderName), "an exited process's older-named prompt file is reclaimed");
            assertTrue(Files.exists(running), "a running process's prompt file is kept");
            assertTrue(Files.exists(unrelated), "a file not named for a process is kept");

            manager.cleanup();
            assertFalse(Files.exists(own));
        } finally {
            restoreHome(original);
        }
    }

    @Test
    void eachManagerAndAgentKeepsItsOwnPromptFile() throws Exception {
        Path home = homes.resolve("home");
        Path prompts = home.resolve(".kompile").resolve("system-prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("claude.md"), "CLAUDE_ONLY");
        Files.writeString(prompts.resolve("gemini.md"), "GEMINI_ONLY");
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            SystemPromptManager manager = SystemPromptManager.resolve("CENTRAL", null, null);
            SystemPromptManager other = SystemPromptManager.resolve("OTHER", null, null);

            Path claude = Path.of(manager.getExtraArgs("claude").get(1));
            Path gemini = Path.of(manager.getExtraEnv("gemini").get("GEMINI_SYSTEM_MD"));
            Path otherClaude = Path.of(other.getExtraArgs("claude").get(1));

            // Launching another agent, or from another manager, leaves an earlier launch's prompt alone.
            assertEquals("CENTRAL\n\nCLAUDE_ONLY", Files.readString(claude));
            assertEquals("CENTRAL\n\nGEMINI_ONLY", Files.readString(gemini));
            assertEquals("OTHER\n\nCLAUDE_ONLY", Files.readString(otherClaude));
            assertEquals(claude, Path.of(manager.getExtraArgs("claude").get(1)), "a relaunch reuses its file");

            manager.cleanup();
            assertFalse(Files.exists(claude));
            assertFalse(Files.exists(gemini));
            assertTrue(Files.exists(otherClaude), "another manager's file outlives this one's cleanup");
            other.cleanup();
            assertFalse(Files.exists(otherClaude));
        } finally {
            restoreHome(original);
        }
    }

    private static long spawnDeadProcess() {
        try {
            String java = ProcessHandle.current().info().command()
                    .orElseThrow(() -> new IllegalStateException("Current Java command unavailable"));
            Process process = new ProcessBuilder(java, "-version").start();
            process.waitFor();
            return process.pid();
        } catch (Exception e) {
            throw new IllegalStateException("Could not spawn a dead process", e);
        }
    }

    private static void restoreHome(String original) {
        if (original == null) System.clearProperty("user.home");
        else System.setProperty("user.home", original);
    }
}
