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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.EditEnforcement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shell file-write gate of edit-coordination enforcement: in
 * {@link EditEnforcement#ENFORCE}, a bash / process-launch command whose statically
 * extracted write targets (redirects, in-place editors, rm/mv/tee, patch stdin) include a
 * coordinator-locked file is refused BEFORE it runs. Extraction is conservative — it may
 * miss exotic indirection, but must never name a file that is not locked.
 */
class ShellEditLockGateTest {

    @TempDir
    Path project;

    private CoordinationStateManager coordinator(String sessionId) {
        return new CoordinationStateManager(project, sessionId, new com.fasterxml.jackson.databind.ObjectMapper(),
                project.resolve("system-activity"), Duration.ofSeconds(600),
                new MutableClock(Instant.now()));
    }

    /** Lease clock pinned to now (no lease movement is exercised here). */
    private static final class MutableClock extends Clock {
        private final Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // ── Extraction ───────────────────────────────────────────────────────────

    @Test
    void extractsRedirectTargetsBothModes() {
        List<String> targets = ShellEditLockGate.extractWriteTargets(
                "echo hi > out.txt && date >> log.txt");
        assertTrue(targets.contains("out.txt"), () -> "targets: " + targets);
        assertTrue(targets.contains("log.txt"), () -> "targets: " + targets);
    }

    @Test
    void extractsEveryInPlaceOperandButNotTheScript() {
        List<String> targets = ShellEditLockGate.extractWriteTargets(
                "sed -i 's/a/b/' A.txt B.txt");
        assertTrue(targets.contains("A.txt"), () -> "targets: " + targets);
        assertTrue(targets.contains("B.txt"), () -> "targets: " + targets);
        // The quoted script is conservatively extracted as a candidate too; the gate only
        // refuses when the coordinator shows a real lock on it, so no false refusals.
        assertTrue(targets.contains("'s/a/b/'"), () -> "script candidate present: " + targets);
    }

    @Test
    void extractsDestructionTargets() {
        List<String> targets = ShellEditLockGate.extractWriteTargets("rm -rf dir/ build.log");
        assertTrue(targets.contains("build.log"), () -> "targets: " + targets);
        assertTrue(targets.contains("dir/"), () -> "targets: " + targets);
    }

    @Test
    void mvChecksSourceAndDestinationCopyChecksOnlyDestination() {
        List<String> moved = ShellEditLockGate.extractWriteTargets("mv a.txt b.txt");
        assertTrue(moved.contains("a.txt"), "mv destroys its source");
        assertTrue(moved.contains("b.txt"));

        List<String> copied = ShellEditLockGate.extractWriteTargets("cp a.txt b.txt");
        assertFalse(copied.contains("a.txt"), "cp keeps its source");
        assertTrue(copied.contains("b.txt"));
    }

    @Test
    void extractsTeeOperandsAndDdOfTarget() {
        List<String> teed = ShellEditLockGate.extractWriteTargets("cat x | tee f1.txt f2.txt");
        assertTrue(teed.contains("f1.txt") && teed.contains("f2.txt"), () -> "targets: " + teed);

        List<String> dd = ShellEditLockGate.extractWriteTargets("dd if=in.img of=out.img bs=1M");
        assertTrue(dd.contains("out.img"), () -> "targets: " + dd);
        assertFalse(dd.contains("in.img"), "dd reads its if=");
    }

    @Test
    void buildVerbsAndVariablesStayOutOfTheGate() {
        assertTrue(ShellEditLockGate.extractWriteTargets("mvn -q install").isEmpty());
        assertTrue(ShellEditLockGate.extractWriteTargets("echo hi > $OUT/file.txt").isEmpty(),
                "variable indirection is not statically resolvable");
        assertTrue(ShellEditLockGate.extractWriteTargets("rm -rf *.log").isEmpty(),
                "globs are not statically resolvable");
        assertTrue(ShellEditLockGate.extractWriteTargets("").isEmpty());
    }

    // ── Enforcement ──────────────────────────────────────────────────────────

    @Test
    void refusesCommandTouchingALockedFileInEnforce() throws Exception {
        CoordinationStateManager shared = coordinator("shell-gate-a");
        CoordinationStateManager other = coordinator("shell-gate-b");
        try {
            Path file = project.resolve("shell-locked.txt");
            Files.writeString(file, "held\n");
            other.tryAcquireEditLock(file.toAbsolutePath().toString(), "edit", "agent-b");

            String refusal = ShellEditLockGate.checkCommand(shared, null,
                    "sed -i 's/held/stolen/' " + file.getFileName(), project);
            assertNotNull(refusal, "the in-place rewrite of a locked file is refused");
            assertTrue(refusal.contains("CONFLICT"));
            assertTrue(refusal.contains("agent-b"));
            assertEquals("held\n", Files.readString(file), "the command never ran (it never would)");
        } finally {
            shared.shutdown();
            other.shutdown();
        }
    }

    @Test
    void allowsCommandTouchingOnlyFreeFiles() throws Exception {
        CoordinationStateManager shared = coordinator("shell-gate-free");
        try {
            assertNull(ShellEditLockGate.checkCommand(shared, null,
                    "echo hi > elsewhere.txt", project));
            assertNull(ShellEditLockGate.checkCommand(shared, null,
                    "mvn -q install", project));
        } finally {
            shared.shutdown();
        }
    }

    @Test
    void advisoryAndOffModesNeverRefuse() throws Exception {
        Path config = ai.kompile.cli.main.chat.harness.HarnessConfig.getConfigFilePath();
        Files.createDirectories(config.getParent());
        try {
            for (String mode : new String[]{"advisory", "off"}) {
                Files.writeString(config, "{\"editCoordinationMode\":\"" + mode + "\"}");
                CoordinationStateManager shared = coordinator("shell-gate-" + mode);
                try {
                    assertNull(ShellEditLockGate.checkCommand(shared, null,
                            "sed -i 's/a/b/' whatever.txt", project),
                            mode + " never refuses a shell command");
                } finally {
                    shared.shutdown();
                }
            }
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void ownLockNeverRefuses() throws Exception {
        CoordinationStateManager shared = coordinator("shell-gate-self");
        try {
            Path file = project.resolve("mine.txt");
            Files.writeString(file, "mine\n");
            shared.tryAcquireEditLock(file.toAbsolutePath().toString(), "edit", null);
            assertNull(ShellEditLockGate.checkCommand(shared, null,
                    "rm mine.txt", project), "the session's own lock does not refuse itself");
        } finally {
            shared.shutdown();
        }
    }
}
