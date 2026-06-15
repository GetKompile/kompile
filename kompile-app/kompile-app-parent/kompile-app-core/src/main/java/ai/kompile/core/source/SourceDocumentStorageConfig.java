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

package ai.kompile.core.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring configuration for SourceDocumentStorageService.
 *
 * Configurable properties:
 * - kompile.source-storage.enabled: Whether to store copies of original documents (default: true)
 * - kompile.source-storage.path: Path to store documents (default: ~/.kompile/documents)
 */
@Configuration(proxyBeanMethods = false)
public class SourceDocumentStorageConfig {

    @Value("${kompile.source-storage.enabled:true}")
    private boolean enabled;

    @Value("${kompile.source-storage.path:#{null}}")
    private String storagePath;

    /**
     * Creates the SourceDocumentStorageService bean.
     *
     * @return Configured SourceDocumentStorageService
     */
    @Bean
    public SourceDocumentStorageService sourceDocumentStorageService() {
        Path path = (storagePath != null && !storagePath.isEmpty())
                ? Paths.get(storagePath)
                : SourceDocumentStorageService.getDefaultStorageRoot();

        return new SourceDocumentStorageService(path, enabled);
    }
}
