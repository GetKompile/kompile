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

package ai.kompile.app.subprocess;

import ai.kompile.app.subprocess.SubprocessRegistry.SubprocessInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RSS watchdog restarts a child through the handler it finds under the id
 * {@link SubprocessRegistry#listAll()} reports for that child. The launcher registers each run as
 * {@code <subprocessId>-<runId>}, so a run's handler has to be found under that id, restart only
 * that run, and go when the run does. {@link IdleChild} runs through the launcher's real start,
 * registration and stop.
 */
@Timeout(60)
class ManagedSubprocessLauncherRestartHandlerTest {

    private final SubprocessRegistry registry = new SubprocessRegistry();
    private final IdleChildLauncher launcher = new IdleChildLauncher();

    @BeforeEach
    void setUp() {
        launcher.subprocessRegistry = registry;
    }

    @AfterEach
    void tearDown() {
        launcher.shutdown();
        registry.shutdownAll();
    }

    @Test
    void theWatchdogRestartsOnlyTheRunItFoundOverItsLimit() throws Exception {
        Process first = launcher.startProcess("first", null, List.of(), null).process();
        Process second = launcher.startProcess("second", null, List.of(), null).process();
        String firstId = watchdogIdOf(first);
        String secondId = watchdogIdOf(second);

        registry.getRestartHandler(firstId)
                .orElseThrow(() -> new AssertionError("no restart handler under " + firstId))
                .requestRestart("RSS over the limit");

        assertTrue(first.waitFor(20, TimeUnit.SECONDS), "the restarted run's child is still running");
        assertFalse(second.waitFor(2, TimeUnit.SECONDS), "restarting one run stopped another");
        awaitDeregistered(firstId);
        assertEquals(Optional.empty(), registry.getRestartHandler(firstId), "the stopped run kept its handler");
        assertTrue(registry.getRestartHandler(secondId).isPresent(), "the other run lost its handler");
    }

    @Test
    void aHandlerRegisteredUnderItsChildsOwnIdIsStillFound() {
        // The embedding and serving launchers register their child and their handler under one id themselves
        registry.registerRestartHandler("serving", launcher);

        assertSame(launcher, registry.getRestartHandler("serving").orElseThrow());
    }

    /** The id {@link SubprocessRegistry#listAll()} reports for a child: the watchdog looks handlers up by it. */
    private String watchdogIdOf(Process child) {
        return registry.listAll().stream()
                .filter(info -> info.pid() == child.pid())
                .map(SubprocessInfo::id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("child " + child.pid() + " is not registered"));
    }

    private void awaitDeregistered(String id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (registry.get(id).isPresent()) {
            assertTrue(System.nanoTime() < deadline, id + " is still registered");
            Thread.sleep(20);
        }
    }

    /** Runs {@link IdleChild} through the launcher's real start, registration and stop. */
    static final class IdleChildLauncher extends ManagedSubprocessLauncher {

        @Override
        public String getSubprocessId() {
            return "idle";
        }

        @Override
        protected String getTypeLabel() {
            return "idle";
        }

        @Override
        protected String getMainClass() {
            return IdleChild.class.getName();
        }

        @Override
        protected int getHeapMb() {
            return 32;
        }

        @Override
        protected List<String> buildJvmCommand(List<String> programArgs) {
            // Only registration and restart are under test, so the child gets none of the backend or native flags
            return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx" + getHeapMb() + "m", "-cp", System.getProperty("java.class.path"), getMainClass());
        }
    }

    /** A child that runs until it is stopped, or until the test JVM exits and so closes its stdin. */
    public static final class IdleChild {

        private IdleChild() {
        }

        public static void main(String[] args) throws IOException {
            while (System.in.read() != -1) {
                // nothing to do but wait
            }
        }
    }
}
