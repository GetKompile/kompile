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

import org.springframework.aot.hint.ResourceHints;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class ResourceDiagnosticComponent implements RuntimeHintsRegistrar {

    /**
     * Add this to your AnseriniConfig to debug during hint registration
     */
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        System.out.println("=== RuntimeHints Registration Debug ===");

        // Check what resources actually exist during build time
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        String[] patterns = {
                "classpath:/ai/kompile/bindings/**/*.so",
                "classpath:/ai/kompile/tokenizers/**/*.so",
                "classpath*:/ai/kompile/bindings/**/*.so",
                "classpath*:/ai/kompile/tokenizers/**/*.so"
        };

        for (String pattern : patterns) {
            try {
                Resource[] resources = resolver.getResources(pattern);
                System.out.println("Pattern: " + pattern + " found " + resources.length + " resources");
                for (Resource resource : resources) {
                    System.out.println("  - " + resource.getURI());
                    System.out.println("    exists: " + resource.exists());
                    System.out.println("    readable: " + resource.isReadable());
                    System.out.println("    class: " + resource.getClass().getName());
                    if (resource instanceof org.springframework.core.io.ClassPathResource) {
                        org.springframework.core.io.ClassPathResource cpr = (org.springframework.core.io.ClassPathResource) resource;
                        System.out.println("    path: " + cpr.getPath());
                    }
                }
            } catch (Exception e) {
                System.out.println("Pattern: " + pattern + " ERROR: " + e.getMessage());
            }
        }

        // Try to register individual resources and see what fails
        String[] individualResources = {
                "/ai/kompile/bindings/linux-x86_64/libjnitokenizers.so",
                "/ai/kompile/tokenizers/linux-x86_64/libtokenizers_wrapper.so"
        };

        for (String resourcePath : individualResources) {
            try {
                org.springframework.core.io.ClassPathResource resource =
                        new org.springframework.core.io.ClassPathResource(resourcePath);

                System.out.println("Individual resource: " + resourcePath);
                System.out.println("  exists: " + resource.exists());
                System.out.println("  readable: " + resource.isReadable());

                if (resource.exists()) {
                    hints.resources().registerResource(resource);
                    System.out.println("  ✓ registered successfully");
                } else {
                    System.out.println("  ✗ not found, skipping registration");
                }
            } catch (Exception e) {
                System.out.println("  ERROR registering: " + e.getMessage());
            }
        }
    }

    /**
     * Runtime method to check what's actually available
     */
    public void checkRuntimeResources() {
        System.out.println("\n=== Runtime Resource Check ===");

        String[] resourcesToCheck = {
                "/ai/kompile/bindings/linux-x86_64/libjnitokenizers.so",
                "/ai/kompile/tokenizers/linux-x86_64/libtokenizers_wrapper.so"
        };

        for (String resource : resourcesToCheck) {
            java.net.URL url = getClass().getResource(resource);
            if (url != null) {
                System.out.println("✓ Found: " + resource + " -> " + url);
                try {
                    java.io.InputStream stream = getClass().getResourceAsStream(resource);
                    if (stream != null) {
                        System.out.println("  Stream available, size: " + stream.available());
                        stream.close();
                    }
                } catch (java.io.IOException e) {
                    System.out.println("  Error reading: " + e.getMessage());
                }
            } else {
                System.out.println("✗ Not found: " + resource);
            }
        }
    }
}