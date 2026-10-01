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

package ai.kompile.cli.main.chat.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The harness files follow the {@code user.home} current when they are used. Test classes
 * move it to a temporary home and delete that home afterwards ({@code @TemporaryUserHome}),
 * so a path fixed when the class loaded sent a later class's writes into an earlier class's
 * deleted home, recreating it under /tmp.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class HarnessHomePathsTest {

    @TempDir
    Path homes;

    @Test
    void harnessConfigSavesUnderTheCurrentHome() {
        Path first = homes.resolve("first");
        Path second = homes.resolve("second");
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", first.toString());
            assertEquals(first.resolve(".kompile").resolve("harness-config.json"),
                    HarnessConfig.getConfigFilePath());

            System.setProperty("user.home", second.toString());
            new HarnessConfig().save();

            assertTrue(Files.isRegularFile(second.resolve(".kompile").resolve("harness-config.json")));
            assertFalse(Files.exists(first.resolve(".kompile")), "an earlier home must not be recreated");
        } finally {
            restoreHome(original);
        }
    }

    @Test
    void performanceStoreFlushesUnderTheCurrentHome() {
        Path first = homes.resolve("first");
        Path second = homes.resolve("second");
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", first.toString());
            assertEquals(first.resolve(".kompile").resolve("perf-data.json"),
                    ModelPerformanceStore.getStoreFilePath());

            // PerformanceHarness's persisting store: the default file, flushed after a record.
            System.setProperty("user.home", second.toString());
            ModelPerformanceStore store = new ModelPerformanceStore();
            store.loadFromFile();
            store.record(ModelPerformanceRecord.builder()
                    .sessionId("home-paths")
                    .agentName("coder")
                    .model("test-model")
                    .taskType("general")
                    .timestamp(Instant.now())
                    .build());
            store.flush();

            assertTrue(Files.isRegularFile(second.resolve(".kompile").resolve("perf-data.json")));
            assertFalse(store.isDirty());
            assertFalse(Files.exists(first.resolve(".kompile")), "an earlier home must not be recreated");
        } finally {
            restoreHome(original);
        }
    }

    private static void restoreHome(String original) {
        if (original == null) System.clearProperty("user.home");
        else System.setProperty("user.home", original);
    }
}
