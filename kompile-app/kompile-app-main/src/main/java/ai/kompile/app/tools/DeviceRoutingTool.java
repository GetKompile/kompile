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

package ai.kompile.app.tools;

import ai.kompile.app.services.DeviceRoutingConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DeviceRoutingTool {

    private static final Logger logger = LoggerFactory.getLogger(DeviceRoutingTool.class);

    private final DeviceRoutingConfigService deviceRoutingConfigService;

    @Autowired
    public DeviceRoutingTool(@Autowired(required = false) DeviceRoutingConfigService deviceRoutingConfigService) {
        this.deviceRoutingConfigService = deviceRoutingConfigService;
        logger.info("DeviceRoutingTool initialized");
    }

    public record GetDeviceRoutingInput() {}
    public record GetServiceDeviceConfigInput(String serviceType) {}
    public record ResetDeviceRoutingInput() {}

    @Tool(name = "get_device_routing",
            description = "Gets the current device routing configuration, including CPU/GPU assignments for different service types.")
    public Map<String, Object> getDeviceRouting(GetDeviceRoutingInput input) {
        try {
            if (deviceRoutingConfigService == null) return Map.of("status", "error", "error", "DeviceRoutingConfigService not available");
            var config = deviceRoutingConfigService.getConfiguration();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("config", config);
            return result;
        } catch (Exception e) {
            logger.error("Error getting device routing: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_service_device_config",
            description = "Gets the device configuration for a specific service type (e.g. 'embedding', 'inference', 'indexing').")
    public Map<String, Object> getServiceDeviceConfig(GetServiceDeviceConfigInput input) {
        try {
            if (deviceRoutingConfigService == null) return Map.of("status", "error", "error", "DeviceRoutingConfigService not available");
            if (input.serviceType() == null) return Map.of("status", "error", "error", "serviceType is required");
            var config = deviceRoutingConfigService.getServiceConfig(input.serviceType());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("serviceType", input.serviceType());
            result.put("config", config);
            return result;
        } catch (Exception e) {
            logger.error("Error getting service device config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "reset_device_routing",
            description = "Resets the device routing configuration to defaults.")
    public Map<String, Object> resetDeviceRouting(ResetDeviceRoutingInput input) {
        try {
            if (deviceRoutingConfigService == null) return Map.of("status", "error", "error", "DeviceRoutingConfigService not available");
            deviceRoutingConfigService.resetToDefaults();
            return Map.of("status", "success", "message", "Device routing reset to defaults");
        } catch (Exception e) {
            logger.error("Error resetting device routing: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
