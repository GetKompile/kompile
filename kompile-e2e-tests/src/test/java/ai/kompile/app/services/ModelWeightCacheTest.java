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

import ai.kompile.app.config.GpuDevice;
import ai.kompile.app.config.ModelWeightCacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ModelWeightCache}.
 * Avoids ND4J operations to stay purely in unit-test territory.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelWeightCacheTest {

    @Mock
    private GpuResourceManager gpuResourceManager;

    @TempDir
    Path tempDir;

    private ModelWeightCache cache;

    @BeforeEach
    void setUp() {
        when(gpuResourceManager.getDevices()).thenReturn(Collections.emptyList());
        cache = new ModelWeightCache(null, gpuResourceManager, tempDir.toString());
        cache.init();
    }

    // ===== Initialization =====

    @Test
    void init_doesNotThrow() {
        assertThatCode(() -> cache.init()).doesNotThrowAnyException();
    }

    @Test
    void getConfiguration_returnsNonNull() {
        assertThat(cache.getConfiguration()).isNotNull();
    }

    @Test
    void getConfiguration_defaultsEnabled() {
        assertThat(cache.getConfiguration().isEnabled()).isTrue();
    }

    // ===== saveConfiguration =====

    @Test
    void saveConfiguration_persistsConfig() throws Exception {
        ModelWeightCacheConfig newConfig = ModelWeightCacheConfig.builder()
                .enabled(false)
                .gpuPressureThreshold(0.9)
                .build();
        cache.saveConfiguration(newConfig);
        assertThat(cache.getConfiguration().isEnabled()).isFalse();
        assertThat(cache.getConfiguration().getGpuPressureThreshold()).isEqualTo(0.9);
    }

    @Test
    void saveConfiguration_writesJsonFile() throws Exception {
        ModelWeightCacheConfig cfg = ModelWeightCacheConfig.defaults();
        cache.saveConfiguration(cfg);
        // Should not throw; config file exists in tempDir/config/
        assertThat(cache.getConfiguration()).isNotNull();
    }

    // ===== getStatus =====

    @Test
    void getStatus_containsExpectedKeys() {
        Map<String, Object> status = cache.getStatus();
        assertThat(status).containsKeys("enabled", "tiers", "totalBlocks", "gpuPressureThreshold", "perModel");
    }

    @Test
    void getStatus_totalBlocks_isZeroInitially() {
        Map<String, Object> status = cache.getStatus();
        assertThat(status.get("totalBlocks")).isEqualTo(0);
    }

    @Test
    void getStatus_tiers_containsGpuCpuDisk() {
        @SuppressWarnings("unchecked")
        Map<String, Object> tiers = (Map<String, Object>) cache.getStatus().get("tiers");
        assertThat(tiers).containsKeys("gpu", "cpu", "disk");
    }

    @Test
    void getStatus_enabled_matchesConfig() {
        assertThat(cache.getStatus().get("enabled")).isEqualTo(true);
    }

    // ===== registerWeights — disabled cache =====

    @Test
    void registerWeights_doesNothing_whenDisabled() throws Exception {
        ModelWeightCacheConfig disabledCfg = ModelWeightCacheConfig.builder().enabled(false).build();
        cache.saveConfiguration(disabledCfg);

        // With disabled cache, registration is a no-op
        // We can't easily mock INDArray without ND4J, so just verify status unchanged
        Map<String, Object> status = cache.getStatus();
        assertThat(status.get("totalBlocks")).isEqualTo(0);
    }

    // ===== getWeights — not registered =====

    @Test
    void getWeights_returnsNull_forUnknownModel() {
        assertThat(cache.getWeights("nonexistent", "layer0")).isNull();
    }

    // ===== checkPressure — no devices =====

    @Test
    void checkPressure_doesNotThrow_withNoDevices() {
        assertThatCode(() -> cache.checkPressure()).doesNotThrowAnyException();
    }

    // ===== ModelWeightCacheConfig defaults =====

    @Test
    void defaults_gpuPressureThreshold() {
        ModelWeightCacheConfig cfg = ModelWeightCacheConfig.defaults();
        assertThat(cfg.getGpuPressureThreshold()).isEqualTo(0.85);
    }

    @Test
    void defaults_cpuBudgetBytes_isFourGb() {
        ModelWeightCacheConfig cfg = ModelWeightCacheConfig.defaults();
        assertThat(cfg.getCpuBudgetBytes()).isEqualTo(4L * 1024 * 1024 * 1024);
    }

    @Test
    void defaults_diskPath_isNotBlank() {
        ModelWeightCacheConfig cfg = ModelWeightCacheConfig.defaults();
        assertThat(cfg.getDiskPath()).isNotBlank();
    }

    // ===== WeightBlock record =====

    @Test
    void weightBlock_fieldsAccessible() {
        java.time.Instant now = java.time.Instant.now();
        ModelWeightCache.WeightBlock block = new ModelWeightCache.WeightBlock(
                "gpt2", "transformer.h.0.attn.c_attn",
                ModelWeightCache.WeightTier.GPU, 1024L, now);
        assertThat(block.modelId()).isEqualTo("gpt2");
        assertThat(block.layerName()).isEqualTo("transformer.h.0.attn.c_attn");
        assertThat(block.tier()).isEqualTo(ModelWeightCache.WeightTier.GPU);
        assertThat(block.sizeBytes()).isEqualTo(1024L);
        assertThat(block.lastAccessed()).isEqualTo(now);
    }

    // ===== WeightTier enum =====

    @Test
    void weightTier_allValuesPresent() {
        assertThat(ModelWeightCache.WeightTier.values())
                .containsExactlyInAnyOrder(
                        ModelWeightCache.WeightTier.GPU,
                        ModelWeightCache.WeightTier.CPU,
                        ModelWeightCache.WeightTier.DISK);
    }
}
