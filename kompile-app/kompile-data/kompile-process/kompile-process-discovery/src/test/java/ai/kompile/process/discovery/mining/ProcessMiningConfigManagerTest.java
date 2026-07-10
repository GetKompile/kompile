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

package ai.kompile.process.discovery.mining;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the kompile-managed process-mining config: defaults without a file, UI update
 * round-trip (persist + immediate effect), unknown-key rejection, clamping, and preservation of
 * foreign keys in a shared file.
 */
class ProcessMiningConfigManagerTest {

    @Test
    void missingFile_servesDefaults(@TempDir Path dir) {
        ProcessMiningConfigManager manager = new ProcessMiningConfigManager(dir.resolve("cfg.json"));
        ProcessMiningConfig config = manager.current();
        assertEquals(0.7, config.getEntailAssertThreshold());
        assertEquals(180.0, config.getRecencyHalfLifeDays());
        assertEquals(0.4, config.getHybridSemanticWeight());
        assertEquals("", config.getAnchorEntityType());
    }

    @Test
    void update_persistsAndAppliesImmediately(@TempDir Path dir) throws Exception {
        ProcessMiningConfigManager manager = new ProcessMiningConfigManager(dir.resolve("cfg.json"));

        Map<String, Object> result = manager.update(Map.of(
                "miningEntailAssertThreshold", 0.85,
                "miningRecencyHalfLifeDays", 30,
                "miningAnchorEntityType", "INVOICE",
                "notAMiningKey", "must be ignored"));

        assertEquals(0.85, result.get("miningEntailAssertThreshold"));
        assertEquals(0.85, manager.current().getEntailAssertThreshold(),
                "update must apply without waiting out the refresh interval");
        assertEquals(30.0, manager.current().getRecencyHalfLifeDays());
        assertEquals("INVOICE", manager.current().getAnchorEntityType());
        assertFalse(result.containsKey("notAMiningKey"), "foreign keys are never adopted");

        // A fresh manager over the same file sees the persisted values.
        ProcessMiningConfigManager reread = new ProcessMiningConfigManager(dir.resolve("cfg.json"));
        assertEquals(0.85, reread.current().getEntailAssertThreshold());
    }

    @Test
    void update_clampsAndPreservesForeignFileKeys(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cfg.json");
        Files.writeString(file, "{\"someOtherSection\": {\"x\": 1}}");
        ProcessMiningConfigManager manager = new ProcessMiningConfigManager(file);

        manager.update(Map.of(
                "miningClusterMaxProcesses", 9999,          // clamps to 100
                "miningHybridSemanticWeight", -0.5));        // clamps to 0

        assertEquals(100, manager.current().getClusterMaxProcesses());
        assertEquals(0.0, manager.current().getHybridSemanticWeight());
        assertTrue(Files.readString(file).contains("someOtherSection"),
                "a config push must never clobber other sections of a shared file");
    }
}
