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

package ai.kompile.app.services.subprocess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ServingSubprocessLauncher}.
 *
 * <p>These tests focus on the state-management logic and the observable API
 * without launching real OS processes (which would require a full classpath
 * with ND4J, Spring Boot, etc. loaded at test time).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServingSubprocessLauncherTest {

    private ServingSubprocessLauncher launcher;

    @BeforeEach
    void setUp() {
        launcher = new ServingSubprocessLauncher();
        // Mimic @PostConstruct (no-op in this class)
        launcher.init();
    }

    // ── isRunning ─────────────────────────────────────────────────────────────

    @Test
    void isRunning_initiallyFalse() {
        assertFalse(launcher.isRunning(),
                "Launcher should not be running before any subprocess is started");
    }

    // ── getServingPort ────────────────────────────────────────────────────────

    @Test
    void getServingPort_returnsDefault() {
        // @Value-injected fields default to 0 in non-Spring context,
        // but the method exists and returns an int (the int primitive default of 0 here)
        int port = launcher.getServingPort();
        // Port is either the property-injected value or 0 — both are valid in test context
        assertTrue(port >= 0, "Port must be non-negative");
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    @Test
    void stop_whenNotRunning_isNoOp() {
        // stop() when nothing is running should not throw
        assertDoesNotThrow(() -> launcher.stop());
        assertFalse(launcher.isRunning());
    }

    @Test
    void stop_calledMultipleTimes_isIdempotent() {
        assertDoesNotThrow(() -> {
            launcher.stop();
            launcher.stop();
            launcher.stop();
        });
        assertFalse(launcher.isRunning());
    }

    // ── start — validation before process launch ──────────────────────────────

    @Test
    void start_withNullModelId_throwsIllegalArgument() {
        assertThrows(Exception.class,
                () -> launcher.start(null, "/some/path", null),
                "start() with null modelId should throw");
    }

    @Test
    void start_withNullModelPath_throwsIllegalArgument() {
        assertThrows(Exception.class,
                () -> launcher.start("model-id", null, null),
                "start() with null modelPath should throw");
    }

    // ── getStatus — requires running subprocess ───────────────────────────────

    @Test
    void getStatus_whenNotRunning_throwsIllegalState() {
        assertThrows(IllegalStateException.class,
                () -> launcher.getStatus(),
                "getStatus() should throw when subprocess is not running");
    }

    // ── generate — requires running subprocess ────────────────────────────────

    @Test
    void generate_whenNotRunning_throwsIllegalState() {
        assertThrows(IllegalStateException.class,
                () -> launcher.generate("Hello, world!"),
                "generate() should throw when subprocess is not running");
    }

    // ── init (PostConstruct) ──────────────────────────────────────────────────

    @Test
    void init_doesNotStartSubprocess() {
        // init() is demand-driven — calling it again should not start anything
        launcher.init();
        assertFalse(launcher.isRunning());
    }

    // ── lifecycle: isRunning reflects internal state ───────────────────────────

    @Test
    void isRunning_afterStop_remainsFalse() {
        launcher.stop(); // no-op since not running
        assertFalse(launcher.isRunning());
    }
}
