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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Warns about the obsolete GPU index override file {@code ~/.kompile/config/gpu-device-config.json}.
 *
 * <p>That file mapped nvidia-smi device indices to CUDA runtime indices while GPUs were discovered
 * through nvidia-smi. GPUs are now discovered through ND4J, and a device's ND4J index is already the
 * index placement uses, so there is nothing left to remap. Applying the old mappings to ND4J-indexed
 * devices pointed each record at the other card: on a 4090 + 3070 Ti host the 4090's record placed
 * jobs on the 3070 Ti. The file is only read to warn that it is ignored.</p>
 */
@Configuration(proxyBeanMethods = false)
public class GpuDeviceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GpuDeviceConfiguration.class);
    private static final String CONFIG_FILENAME = "gpu-device-config.json";

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
        int ignored = obsoleteMappingCount();
        if (ignored > 0) {
            log.warn("Ignoring {} GPU index mapping(s) in {}: GPUs are discovered through ND4J, whose device "
                    + "index is already the placement index, so nvidia-smi -> CUDA mappings no longer apply. "
                    + "Delete the file to silence this warning.", ignored, configFilePath);
        }
    }

    /**
     * Number of mappings in the obsolete override file; 0 when the file is absent or unreadable.
     */
    int obsoleteMappingCount() {
        GpuDeviceConfig config = loadConfig();
        return config == null || config.cudaIndexMappings() == null ? 0 : config.cudaIndexMappings().size();
    }

    private GpuDeviceConfig loadConfig() {
        if (!Files.exists(configFilePath)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(configFilePath), GpuDeviceConfig.class);
        } catch (Exception e) {
            log.warn("Failed to read obsolete GPU device config {}: {}", configFilePath, e.getMessage());
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
