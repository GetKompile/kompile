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

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.DeviceRoutingConfig.ServiceDeviceConfig;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for managing per-service device routing configuration.
 * Persists to ~/.kompile/config/device-routing-config.json.
 *
 * <p>At subprocess launch time, callers use {@link #resolveNd4jConfigForService(String)}
 * to get a merged ND4J config that includes device-specific overrides for that service.</p>
 */
@Service
public class DeviceRoutingConfigService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRoutingConfigService.class);
    private static final String CONFIG_FILENAME = "device-routing-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;

    @Autowired(required = false)
    private Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;

    private volatile DeviceRoutingConfig currentConfig;

    public DeviceRoutingConfigService(
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.objectMapper = new ObjectMapper();

        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.currentConfig = DeviceRoutingConfig.defaults();
    }

    @PostConstruct
    public void loadPersistedConfig() {
        if (Files.exists(configFilePath)) {
            try {
                String json = Files.readString(configFilePath);
                currentConfig = objectMapper.readValue(json, DeviceRoutingConfig.class);
                log.info("Loaded device routing config from {}: enabled={}, routes={}",
                        configFilePath, currentConfig.enabled(),
                        currentConfig.serviceRoutes() != null ? currentConfig.serviceRoutes().keySet() : "none");
            } catch (Exception e) {
                log.warn("Failed to load device routing config from {}: {}", configFilePath, e.getMessage());
                currentConfig = DeviceRoutingConfig.defaults();
            }
        } else {
            log.info("No device routing config found at {}, using defaults (disabled)", configFilePath);
        }
    }

    public DeviceRoutingConfig getConfiguration() {
        return currentConfig;
    }

    public void saveConfiguration(DeviceRoutingConfig config) throws IOException {
        this.currentConfig = config;
        persistToDisk();
        log.info("Saved device routing config: enabled={}, routes={}",
                config.enabled(),
                config.serviceRoutes() != null ? config.serviceRoutes().keySet() : "none");
    }

    public ServiceDeviceConfig getServiceConfig(String serviceType) {
        if (currentConfig.serviceRoutes() == null) return null;
        return currentConfig.serviceRoutes().get(serviceType);
    }

    public void updateServiceConfig(String serviceType, ServiceDeviceConfig serviceConfig) throws IOException {
        Map<String, ServiceDeviceConfig> routes = new HashMap<>(
                currentConfig.serviceRoutes() != null ? currentConfig.serviceRoutes() : Map.of());
        routes.put(serviceType, serviceConfig);
        currentConfig = new DeviceRoutingConfig(routes, currentConfig.enabled());
        persistToDisk();
    }

    public void removeServiceConfig(String serviceType) throws IOException {
        if (currentConfig.serviceRoutes() == null) return;
        Map<String, ServiceDeviceConfig> routes = new HashMap<>(currentConfig.serviceRoutes());
        routes.remove(serviceType);
        currentConfig = new DeviceRoutingConfig(routes, currentConfig.enabled());
        persistToDisk();
    }

    public void resetToDefaults() throws IOException {
        currentConfig = DeviceRoutingConfig.defaults();
        persistToDisk();
    }

    /**
     * Resolve the ND4J config for a specific service type.
     * Takes the global config and merges in device-specific overrides.
     *
     * @param serviceType one of the SERVICE_* constants from DeviceRoutingConfig
     * @return merged config, or global config if routing disabled/no override for service
     */
    public Nd4jEnvironmentConfig resolveNd4jConfigForService(String serviceType) {
        // Get base config
        Nd4jEnvironmentConfig baseConfig = null;
        if (nd4jEnvironmentConfigService != null) {
            try {
                baseConfig = nd4jEnvironmentConfigService.getConfiguration();
            } catch (Exception e) {
                log.warn("Failed to get base ND4J config: {}", e.getMessage());
            }
        }
        if (baseConfig == null) {
            baseConfig = Nd4jEnvironmentConfig.defaults();
        }

        // If routing not enabled or no route for this service, return base config
        if (!currentConfig.hasRouteFor(serviceType)) {
            return baseConfig;
        }

        ServiceDeviceConfig serviceDeviceConfig = currentConfig.serviceRoutes().get(serviceType);
        if (serviceDeviceConfig == null) {
            return baseConfig;
        }

        // Build an overlay config with device-specific settings
        Nd4jEnvironmentConfig.Builder overlay = Nd4jEnvironmentConfig.builder();

        String deviceType = serviceDeviceConfig.deviceType();
        if ("cpu".equalsIgnoreCase(deviceType)) {
            // Force CPU: set cudaCurrentDevice to -1 to signal no CUDA
            overlay.cudaCurrentDevice(-1);
            log.debug("Device routing for '{}': forcing CPU mode", serviceType);
        } else if ("cuda".equalsIgnoreCase(deviceType)) {
            Integer deviceId = serviceDeviceConfig.cudaDeviceId();
            overlay.cudaCurrentDevice(deviceId != null ? deviceId : 0);
            log.debug("Device routing for '{}': CUDA device {}", serviceType,
                    deviceId != null ? deviceId : 0);
        }

        if (serviceDeviceConfig.maxThreads() != null) {
            overlay.maxThreads(serviceDeviceConfig.maxThreads());
            overlay.maxMasterThreads(Math.max(1, serviceDeviceConfig.maxThreads() / 2));
        }

        if (serviceDeviceConfig.maxDeviceMemory() != null) {
            overlay.maxDeviceMemory(serviceDeviceConfig.maxDeviceMemory());
        }

        // Merge: base config with overlay on top
        return baseConfig.merge(overlay.build());
    }

    /**
     * Check if device routing is enabled.
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(currentConfig.enabled());
    }

    private void persistToDisk() throws IOException {
        Path configDir = configFilePath.getParent();
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfig);
        Files.writeString(configFilePath, json);
    }
}
