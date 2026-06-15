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

package ai.kompile.core.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.ResourceHints;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class ResourceDiagnosticComponent implements RuntimeHintsRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ResourceDiagnosticComponent.class);

    /**
     * Add this to your AnseriniConfig to debug during hint registration
     */
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        log.debug("=== RuntimeHints Registration Debug ===");

        // Check what resources actually exist during build time
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        String[] patterns = {
                "classpath:/ai/kompile/bindings/**/*.so",
                "classpath:/org/eclipse/deeplearning4j/tokenizers/**/*.so",
                "classpath*:/ai/kompile/bindings/**/*.so",
                "classpath*:/org/eclipse/deeplearning4j/tokenizers/**/*.so"
        };

        for (String pattern : patterns) {
            try {
                Resource[] resources = resolver.getResources(pattern);
                log.debug("Pattern: {} found {} resources", pattern, resources.length);
                for (Resource resource : resources) {
                    log.debug("  - {}", resource.getURI());
                    log.debug("    exists: {}", resource.exists());
                    log.debug("    readable: {}", resource.isReadable());
                    log.debug("    class: {}", resource.getClass().getName());
                    if (resource instanceof org.springframework.core.io.ClassPathResource) {
                        org.springframework.core.io.ClassPathResource cpr = (org.springframework.core.io.ClassPathResource) resource;
                        log.debug("    path: {}", cpr.getPath());
                    }
                }
            } catch (Exception e) {
                log.debug("Pattern: {} ERROR: {}", pattern, e.getMessage());
            }
        }

        // Try to register individual resources and see what fails
        String[] individualResources = {
                "/ai/kompile/bindings/linux-x86_64/libjnitokenizers.so",
                "/org/eclipse/deeplearning4j/tokenizers/linux-x86_64/libtokenizers_wrapper.so"
        };

        for (String resourcePath : individualResources) {
            try {
                org.springframework.core.io.ClassPathResource resource =
                        new org.springframework.core.io.ClassPathResource(resourcePath);

                log.debug("Individual resource: {}", resourcePath);
                log.debug("  exists: {}", resource.exists());
                log.debug("  readable: {}", resource.isReadable());

                if (resource.exists()) {
                    hints.resources().registerResource(resource);
                    log.debug("  registered successfully: {}", resourcePath);
                } else {
                    log.debug("  not found, skipping registration: {}", resourcePath);
                }
            } catch (Exception e) {
                log.debug("  ERROR registering: {} - {}", resourcePath, e.getMessage());
            }
        }
    }

    /**
     * Runtime method to check what's actually available
     */
    public void checkRuntimeResources() {
        log.debug("=== Runtime Resource Check ===");

        String[] resourcesToCheck = {
                "/ai/kompile/bindings/linux-x86_64/libjnitokenizers.so",
                "/org/eclipse/deeplearning4j/tokenizers/linux-x86_64/libtokenizers_wrapper.so"
        };

        for (String resource : resourcesToCheck) {
            java.net.URL url = getClass().getResource(resource);
            if (url != null) {
                log.debug("Found: {} -> {}", resource, url);
                try {
                    java.io.InputStream stream = getClass().getResourceAsStream(resource);
                    if (stream != null) {
                        log.debug("  Stream available, size: {}", stream.available());
                        stream.close();
                    }
                } catch (java.io.IOException e) {
                    log.debug("  Error reading: {}", e.getMessage());
                }
            } else {
                log.debug("Not found: {}", resource);
            }
        }
    }
}