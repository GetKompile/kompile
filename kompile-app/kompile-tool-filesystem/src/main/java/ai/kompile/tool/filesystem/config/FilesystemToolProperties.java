/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.tool.filesystem.config; // New package for this module's config

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component; // Make it a bean for easy discovery by @EnableConfigurationProperties

import java.util.Map;

@Data
@Component // Ensure it's picked up by component scan if not explicitly listed in @EnableConfigurationProperties
@ConfigurationProperties(prefix = "mcp.filesystem")
public class FilesystemToolProperties {
    private Map<String, RootConfig> roots;

    @Data
    public static class RootConfig {
        private String path; // Actual path on the filesystem
        private String alias; // Alias expected by the tool in its input
        // Add permissions if needed: readOnly = true, etc.
    }
}