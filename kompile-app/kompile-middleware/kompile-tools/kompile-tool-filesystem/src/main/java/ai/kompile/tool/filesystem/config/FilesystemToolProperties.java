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

package ai.kompile.tool.filesystem.config;

import lombok.Data;

import java.util.Map;

/**
 * Plain data class representing the filesystem tool roots configuration.
 *
 * <p>Previously used as a Spring {@code @ConfigurationProperties} bean. Settings are now
 * managed via {@link FilesystemToolConfigService} and persisted to
 * {@code ~/.kompile/config/filesystem-tool-config.json}.</p>
 *
 * <p>This class is retained as the shape of the root config entries used by
 * {@link FilesystemToolConfigService} and {@link ai.kompile.tool.filesystem.FilesystemToolImpl}.</p>
 */
@Data
public class FilesystemToolProperties {
    private Map<String, RootConfig> roots;

    @Data
    public static class RootConfig {
        private String path;  // Actual path on the filesystem
        private String alias; // Alias expected by the tool in its input
    }
}
