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

package ai.kompile.app.web.controllers;

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.DeviceRoutingConfig.ServiceDeviceConfig;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.services.DeviceRoutingConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/device-routing")
public class DeviceRoutingController {

    private static final Logger log = LoggerFactory.getLogger(DeviceRoutingController.class);

    private final DeviceRoutingConfigService deviceRoutingConfigService;

    public DeviceRoutingController(DeviceRoutingConfigService deviceRoutingConfigService) {
        this.deviceRoutingConfigService = deviceRoutingConfigService;
    }

    @GetMapping
    public ResponseEntity<DeviceRoutingConfig> getConfiguration() {
        return ResponseEntity.ok(deviceRoutingConfigService.getConfiguration());
    }

    @PostMapping
    public ResponseEntity<DeviceRoutingConfig> saveConfiguration(@RequestBody DeviceRoutingConfig config) {
        try {
            deviceRoutingConfigService.saveConfiguration(config);
            return ResponseEntity.ok(deviceRoutingConfigService.getConfiguration());
        } catch (Exception e) {
            log.error("Failed to save device routing config: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/services/{serviceType}")
    public ResponseEntity<ServiceDeviceConfig> getServiceConfig(@PathVariable String serviceType) {
        ServiceDeviceConfig config = deviceRoutingConfigService.getServiceConfig(serviceType);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    @PutMapping("/services/{serviceType}")
    public ResponseEntity<DeviceRoutingConfig> updateServiceConfig(
            @PathVariable String serviceType,
            @RequestBody ServiceDeviceConfig serviceConfig) {
        try {
            deviceRoutingConfigService.updateServiceConfig(serviceType, serviceConfig);
            return ResponseEntity.ok(deviceRoutingConfigService.getConfiguration());
        } catch (Exception e) {
            log.error("Failed to update service config for '{}': {}", serviceType, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/services/{serviceType}")
    public ResponseEntity<DeviceRoutingConfig> removeServiceConfig(@PathVariable String serviceType) {
        try {
            deviceRoutingConfigService.removeServiceConfig(serviceType);
            return ResponseEntity.ok(deviceRoutingConfigService.getConfiguration());
        } catch (Exception e) {
            log.error("Failed to remove service config for '{}': {}", serviceType, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<DeviceRoutingConfig> resetToDefaults() {
        try {
            deviceRoutingConfigService.resetToDefaults();
            return ResponseEntity.ok(deviceRoutingConfigService.getConfiguration());
        } catch (Exception e) {
            log.error("Failed to reset device routing config: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/preview/{serviceType}")
    public ResponseEntity<Nd4jEnvironmentConfig> previewServiceConfig(@PathVariable String serviceType) {
        try {
            Nd4jEnvironmentConfig resolved = deviceRoutingConfigService.resolveNd4jConfigForService(serviceType);
            return ResponseEntity.ok(resolved);
        } catch (Exception e) {
            log.error("Failed to preview config for '{}': {}", serviceType, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
