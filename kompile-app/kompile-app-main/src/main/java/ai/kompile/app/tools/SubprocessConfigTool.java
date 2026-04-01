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

import ai.kompile.app.services.subprocess.SubprocessConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SubprocessConfigTool {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessConfigTool.class);

    private final SubprocessConfigService subprocessConfigService;

    @Autowired
    public SubprocessConfigTool(@Autowired(required = false) SubprocessConfigService subprocessConfigService) {
        this.subprocessConfigService = subprocessConfigService;
        logger.info("SubprocessConfigTool initialized");
    }

    public record GetSubprocessConfigInput() {}
    public record ResetSubprocessConfigInput() {}
    public record GetHeapSizeOptionsInput() {}

    @Tool(name = "get_subprocess_config",
            description = "Gets the current subprocess configuration including heap size, timeout, parallel indexing settings, " +
                    "restart policy, and native executable configuration.")
    public Map<String, Object> getSubprocessConfig(GetSubprocessConfigInput input) {
        try {
            if (subprocessConfigService == null) return Map.of("status", "error", "error", "SubprocessConfigService not available");
            var config = subprocessConfigService.getConfiguration();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("config", config);
            return result;
        } catch (Exception e) {
            logger.error("Error getting subprocess config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "reset_subprocess_config",
            description = "Resets the subprocess configuration to defaults.")
    public Map<String, Object> resetSubprocessConfig(ResetSubprocessConfigInput input) {
        try {
            if (subprocessConfigService == null) return Map.of("status", "error", "error", "SubprocessConfigService not available");
            subprocessConfigService.resetToDefaults();
            return Map.of("status", "success", "message", "Subprocess config reset to defaults");
        } catch (Exception e) {
            logger.error("Error resetting subprocess config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_subprocess_heap_options",
            description = "Gets the available heap size options for subprocess configuration.")
    public Map<String, Object> getHeapSizeOptions(GetHeapSizeOptionsInput input) {
        try {
            if (subprocessConfigService == null) return Map.of("status", "error", "error", "SubprocessConfigService not available");
            var options = subprocessConfigService.getHeapSizeOptions();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("currentHeapSize", subprocessConfigService.getHeapSize());
            result.put("maxHeapSize", subprocessConfigService.getMaxHeapSize());
            result.put("options", options);
            return result;
        } catch (Exception e) {
            logger.error("Error getting heap size options: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
