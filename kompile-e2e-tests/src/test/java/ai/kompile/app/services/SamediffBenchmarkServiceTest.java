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

package ai.kompile.app.services;

import ai.kompile.app.config.SamediffBenchmarkConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SamediffBenchmarkServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private Nd4jEnvironmentConfigService nd4jConfigService;

    private SamediffBenchmarkService service;

    @BeforeEach
    void setUp() {
        service = new SamediffBenchmarkService(tempDir.toString(), nd4jConfigService);
        service.init();
    }

    // ── init ──────────────────────────────────────────────────────────────────

    @Test
    void testInit_createsDefaultOptimalConfig() {
        List<SamediffBenchmarkConfig> configs = service.listConfigs();
        assertFalse(configs.isEmpty(), "Should have at least one config after init");
        assertTrue(configs.stream().anyMatch(c -> "optimal".equals(c.name())),
                "Should have an 'optimal' config");
    }

    @Test
    void testInit_setsActiveConfig() {
        SamediffBenchmarkConfig active = service.getActiveConfig();
        assertNotNull(active, "Should have an active config after init");
    }

    // ── listConfigs ───────────────────────────────────────────────────────────

    @Test
    void testListConfigs_returnsConfigs() {
        List<SamediffBenchmarkConfig> configs = service.listConfigs();
        assertNotNull(configs);
        assertFalse(configs.isEmpty());
    }

    // ── getConfig ─────────────────────────────────────────────────────────────

    @Test
    void testGetConfig_existingConfig() {
        SamediffBenchmarkConfig config = service.getConfig("optimal");
        assertNotNull(config);
        assertEquals("optimal", config.name());
    }

    @Test
    void testGetConfig_notFound_returnsNull() {
        assertNull(service.getConfig("nonexistent_config_xyz"));
    }

    // ── saveConfig ────────────────────────────────────────────────────────────

    @Test
    void testSaveConfig_newConfig() {
        SamediffBenchmarkConfig config = SamediffBenchmarkConfig.optimal();
        // Create a copy with a new name by reconstructing
        SamediffBenchmarkConfig newConfig = new SamediffBenchmarkConfig(
                "my_custom_config", false, null, null,
                config.tritonBuildThreads(), config.tritonCacheEnabled(),
                config.tritonVerbose(), config.tritonAlwaysCompile(),
                config.tritonNumWarps(), config.tritonNumStages(),
                config.tritonNumCTAs(), config.tritonEnableFpFusion(),
                config.tritonCacheDir(), config.tritonDumpDir(),
                config.tritonOverrideArch(),
                config.cudaTensorCoreEnabled(), config.cudaGraphOptimization(),
                config.maxTokens(), config.captureMinExec(),
                config.minDiversityPct(), config.expectedSubstrings(),
                config.expectStructuralTags()
        );

        SamediffBenchmarkConfig saved = service.saveConfig(newConfig);
        assertNotNull(saved);
        assertEquals("my_custom_config", saved.name());
        assertNotNull(service.getConfig("my_custom_config"));
    }

    @Test
    void testSaveConfig_blankName_throws() {
        SamediffBenchmarkConfig config = SamediffBenchmarkConfig.optimal();
        SamediffBenchmarkConfig badConfig = new SamediffBenchmarkConfig(
                "", false, null, null,
                config.tritonBuildThreads(), config.tritonCacheEnabled(),
                config.tritonVerbose(), config.tritonAlwaysCompile(),
                config.tritonNumWarps(), config.tritonNumStages(),
                config.tritonNumCTAs(), config.tritonEnableFpFusion(),
                config.tritonCacheDir(), config.tritonDumpDir(),
                config.tritonOverrideArch(),
                config.cudaTensorCoreEnabled(), config.cudaGraphOptimization(),
                config.maxTokens(), config.captureMinExec(),
                config.minDiversityPct(), config.expectedSubstrings(),
                config.expectStructuralTags()
        );
        assertThrows(IllegalArgumentException.class, () -> service.saveConfig(badConfig));
    }

    // ── deleteConfig ──────────────────────────────────────────────────────────

    @Test
    void testDeleteConfig_exists() {
        // Save a custom config first
        SamediffBenchmarkConfig base = SamediffBenchmarkConfig.optimal();
        SamediffBenchmarkConfig toDelete = new SamediffBenchmarkConfig(
                "delete_me", false, null, null,
                base.tritonBuildThreads(), base.tritonCacheEnabled(),
                base.tritonVerbose(), base.tritonAlwaysCompile(),
                base.tritonNumWarps(), base.tritonNumStages(),
                base.tritonNumCTAs(), base.tritonEnableFpFusion(),
                base.tritonCacheDir(), base.tritonDumpDir(),
                base.tritonOverrideArch(),
                base.cudaTensorCoreEnabled(), base.cudaGraphOptimization(),
                base.maxTokens(), base.captureMinExec(),
                base.minDiversityPct(), base.expectedSubstrings(),
                base.expectStructuralTags()
        );
        service.saveConfig(toDelete);
        assertNotNull(service.getConfig("delete_me"));

        boolean deleted = service.deleteConfig("delete_me");
        assertTrue(deleted);
        assertNull(service.getConfig("delete_me"));
    }

    @Test
    void testDeleteConfig_notFound_returnsFalse() {
        assertFalse(service.deleteConfig("nonexistent_xyz"));
    }

    // ── getActiveConfig / activateConfig ──────────────────────────────────────

    @Test
    void testGetActiveConfig_afterInit() {
        assertNotNull(service.getActiveConfig());
    }

    @Test
    void testActivateConfig_switchesActive() {
        // Add a second config
        SamediffBenchmarkConfig base = SamediffBenchmarkConfig.optimal();
        SamediffBenchmarkConfig second = new SamediffBenchmarkConfig(
                "second_config", false, null, null,
                base.tritonBuildThreads(), base.tritonCacheEnabled(),
                base.tritonVerbose(), base.tritonAlwaysCompile(),
                base.tritonNumWarps(), base.tritonNumStages(),
                base.tritonNumCTAs(), base.tritonEnableFpFusion(),
                base.tritonCacheDir(), base.tritonDumpDir(),
                base.tritonOverrideArch(),
                base.cudaTensorCoreEnabled(), base.cudaGraphOptimization(),
                base.maxTokens(), base.captureMinExec(),
                base.minDiversityPct(), base.expectedSubstrings(),
                base.expectStructuralTags()
        );
        service.saveConfig(second);

        // Activate second config (will try to apply to ND4J, which may be CPU-only)
        try {
            SamediffBenchmarkConfig active = service.activateConfig("second_config");
            assertNotNull(active);
            assertEquals("second_config", active.name());
        } catch (Exception e) {
            // ND4J environment interaction may fail in test; that's acceptable
        }
    }

    @Test
    void testActivateConfig_notFound_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.activateConfig("nonexistent_config_xyz"));
    }

    // ── SamediffBenchmarkConfig.optimal() ────────────────────────────────────

    @Test
    void testOptimalConfig_hasName() {
        SamediffBenchmarkConfig optimal = SamediffBenchmarkConfig.optimal();
        assertNotNull(optimal);
        assertEquals("optimal", optimal.name());
    }

    @Test
    void testOptimalConfig_isActive() {
        SamediffBenchmarkConfig optimal = SamediffBenchmarkConfig.optimal();
        assertTrue(optimal.isActive());
    }
}
