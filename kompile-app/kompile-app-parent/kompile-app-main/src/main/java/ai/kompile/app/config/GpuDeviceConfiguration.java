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

package ai.kompile.app.config;

import ai.kompile.app.services.GpuResourceManager;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Configures GPU device properties that cannot be auto-detected.
 *
 * <p>On systems where the nvidia-smi device index differs from the CUDA runtime
 * device index (as seen by the JVM), this configuration applies the correct mapping.
 * The mapping is persisted at {@code ~/.kompile/config/gpu-device-config.json}.</p>
 *
 * <p>If no config file exists, no mapping override is applied; ND4J discovery remains the source of truth.</p>
 *
 * <p>Service placement is left to ND4J and the resource manager unless a project supplies an explicit route.</p>
 */
@Configuration(proxyBeanMethods = false)
public class GpuDeviceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GpuDeviceConfiguration.class);
    private static final String CONFIG_FILENAME = "gpu-device-config.json";

    @Autowired
    private GpuResourceManager gpuResourceManager;

    private final Path configFilePath;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    public GpuDeviceConfiguration(
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
    }

    @PostConstruct
    public void configure() {
        applyCudaRuntimeIndexMapping();
    }

    /**
     * Apply CUDA runtime index overrides from persisted config, if present.
     * The GpuResourceManager already auto-detects the mapping using compute capabilities,
     * so this config file serves as a manual override for edge cases where the
     * auto-detection is wrong.
     */
    private void applyCudaRuntimeIndexMapping() {
        GpuDeviceConfig config = loadConfig();
        if (config == null || config.cudaIndexMappings() == null) {
            return;
        }

        for (CudaIndexMapping mapping : config.cudaIndexMappings()) {
            gpuResourceManager.setCudaRuntimeIndex(mapping.nvidiaSmiIndex(), mapping.cudaRuntimeIndex());
            log.info("Applied CUDA runtime index mapping: nvidia-smi {} -> CUDA runtime {}",
                    mapping.nvidiaSmiIndex(), mapping.cudaRuntimeIndex());
        }
    }

    /**
     * Load a persisted GPU mapping override, if present.
     */
    private GpuDeviceConfig loadConfig() {
        if (!Files.exists(configFilePath)) {
            log.debug("No GPU device mapping override found at {}; using ND4J device discovery", configFilePath);
            return null;
        }
        try {
            String json = Files.readString(configFilePath);
            GpuDeviceConfig config = objectMapper.readValue(json, GpuDeviceConfig.class);
            log.info("Loaded GPU device config from {} with {} mapping(s)",
                    configFilePath,
                    config.cudaIndexMappings() != null ? config.cudaIndexMappings().size() : 0);
            return config;
        } catch (Exception e) {
            log.warn("Failed to load GPU device config from {}: {}", configFilePath, e.getMessage());
            return null;
        }
    }

    // ==================== Config DTOs ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GpuDeviceConfig(
            @JsonProperty("cudaIndexMappings") List<CudaIndexMapping> cudaIndexMappings
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CudaIndexMapping(
            @JsonProperty("nvidiaSmiIndex") int nvidiaSmiIndex,
            @JsonProperty("cudaRuntimeIndex") int cudaRuntimeIndex,
            @JsonProperty("deviceName") String deviceName
    ) {}
}
