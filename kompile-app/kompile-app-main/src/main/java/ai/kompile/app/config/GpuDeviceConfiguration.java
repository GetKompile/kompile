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

import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.GpuResourceManager;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Configures GPU device properties that cannot be auto-detected.
 *
 * <p>On systems where the nvidia-smi device index differs from the CUDA runtime
 * device index (as seen by the JVM), this configuration applies the correct mapping.
 * The mapping is persisted at {@code ~/.kompile/config/gpu-device-config.json}.</p>
 *
 * <p>If no config file exists, it auto-generates one from the discovered devices
 * with a default assumption that nvidia-smi index == CUDA runtime index.</p>
 *
 * <p>Also ensures device routing is enabled and VLM is routed to the largest GPU.</p>
 */
@Configuration
public class GpuDeviceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GpuDeviceConfiguration.class);
    private static final String CONFIG_FILENAME = "gpu-device-config.json";

    @Autowired
    private GpuResourceManager gpuResourceManager;

    @Autowired(required = false)
    private DeviceRoutingConfigService deviceRoutingConfigService;

    private final Path configFilePath;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        ensureVlmDeviceRouting();
    }

    /**
     * Apply CUDA runtime index overrides from persisted config, if present.
     * The GpuResourceManager already auto-detects the mapping using compute capabilities,
     * so this config file serves as a manual override for edge cases where the
     * auto-detection is wrong.
     */
    private void applyCudaRuntimeIndexMapping() {
        GpuDeviceConfig config = loadOrCreateConfig();
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
     * Ensure VLM is routed to the largest GPU if device routing is available.
     * Only sets this up if no VLM route is already configured.
     */
    private void ensureVlmDeviceRouting() {
        if (deviceRoutingConfigService == null) {
            log.debug("DeviceRoutingConfigService not available — skipping VLM device routing setup");
            return;
        }

        // Enable device routing if not already enabled
        DeviceRoutingConfig currentConfig = deviceRoutingConfigService.getConfiguration();
        if (!Boolean.TRUE.equals(currentConfig.enabled())) {
            try {
                deviceRoutingConfigService.saveConfiguration(
                        new DeviceRoutingConfig(
                                currentConfig.serviceRoutes() != null ? currentConfig.serviceRoutes() : Map.of(),
                                true));
                log.info("Enabled device routing for GPU lifecycle management");
            } catch (IOException e) {
                log.warn("Failed to enable device routing: {}", e.getMessage());
            }
        }

        // Set up VLM route to largest GPU if not already configured
        DeviceRoutingConfig.ServiceDeviceConfig vlmConfig =
                deviceRoutingConfigService.getServiceConfig(DeviceRoutingConfig.SERVICE_VLM);

        if (vlmConfig == null) {
            gpuResourceManager.getLargestDevice().ifPresent(largestDevice -> {
                try {
                    DeviceRoutingConfig.ServiceDeviceConfig vlmRoute =
                            new DeviceRoutingConfig.ServiceDeviceConfig(
                                    "cuda",
                                    largestDevice.cudaRuntimeIndex(),
                                    null,
                                    null);
                    deviceRoutingConfigService.updateServiceConfig(
                            DeviceRoutingConfig.SERVICE_VLM, vlmRoute);
                    log.info("Auto-configured VLM to use {} (CUDA runtime index {})",
                            largestDevice.name(), largestDevice.cudaRuntimeIndex());
                } catch (IOException e) {
                    log.warn("Failed to save VLM device routing: {}", e.getMessage());
                }
            });
        } else {
            log.info("VLM device routing already configured: deviceType={}, cudaDeviceId={}",
                    vlmConfig.deviceType(), vlmConfig.cudaDeviceId());
        }
    }

    /**
     * Load persisted config or create a default one from discovered devices.
     */
    private GpuDeviceConfig loadOrCreateConfig() {
        if (Files.exists(configFilePath)) {
            try {
                String json = Files.readString(configFilePath);
                GpuDeviceConfig config = objectMapper.readValue(json, GpuDeviceConfig.class);
                log.info("Loaded GPU device config from {} with {} mapping(s)",
                        configFilePath,
                        config.cudaIndexMappings() != null ? config.cudaIndexMappings().size() : 0);
                return config;
            } catch (Exception e) {
                log.warn("Failed to load GPU device config from {}: {}", configFilePath, e.getMessage());
            }
        }

        // Create default config: assume nvidia-smi index == CUDA runtime index
        List<GpuDevice> devices = gpuResourceManager.getDevices();
        if (devices.isEmpty()) {
            log.info("No GPU devices discovered — skipping GPU device config creation");
            return null;
        }

        List<CudaIndexMapping> mappings = devices.stream()
                .map(d -> new CudaIndexMapping(d.nvidiaSmiIndex(), d.cudaRuntimeIndex(),
                        d.name()))
                .toList();

        GpuDeviceConfig config = new GpuDeviceConfig(mappings);

        // Persist the default config so the user can edit it
        try {
            Path configDir = configFilePath.getParent();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configFilePath, json);
            log.info("Created default GPU device config at {} — edit to override CUDA runtime index mappings",
                    configFilePath);
        } catch (IOException e) {
            log.warn("Failed to persist default GPU device config: {}", e.getMessage());
        }

        return config;
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
