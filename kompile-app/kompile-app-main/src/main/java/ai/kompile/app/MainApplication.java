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

package ai.kompile.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.stream.StreamSupport;

@SpringBootApplication(scanBasePackages = "ai.kompile")
@EnableConfigurationProperties({}) // Keep if other @ConfigurationProperties are used elsewhere
public class MainApplication {

    private static final Logger logger = LoggerFactory.getLogger(MainApplication.class);

    // Define constants for our custom command-line properties
    public static final String MAX_FILE_SIZE_PROPERTY = "kompile.multipart.max-file-size";
    public static final String MAX_REQUEST_SIZE_PROPERTY = "kompile.multipart.max-request-size";
    // Default values if not provided via command line
    public static final String DEFAULT_MAX_FILE_SIZE = "5000MB"; // Your 5GB default
    public static final String DEFAULT_MAX_REQUEST_SIZE = "5000MB"; // Your 5GB default



    public static void main(String[] args) {
        String maxFileSizeArg = DEFAULT_MAX_FILE_SIZE;
        String maxRequestSizeArg = DEFAULT_MAX_REQUEST_SIZE;

        for (String arg : args) {
            if (arg.startsWith("--" + MAX_FILE_SIZE_PROPERTY + "=")) {
                maxFileSizeArg = arg.substring(("--" + MAX_FILE_SIZE_PROPERTY + "=").length());
            } else if (arg.startsWith("--" + MAX_REQUEST_SIZE_PROPERTY + "=")) {
                maxRequestSizeArg = arg.substring(("--" + MAX_REQUEST_SIZE_PROPERTY + "=").length());
            }
        }

        System.setProperty(MAX_FILE_SIZE_PROPERTY, maxFileSizeArg);
        System.setProperty(MAX_REQUEST_SIZE_PROPERTY, maxRequestSizeArg);

        logger.info("Attempting to set Max File Size (from command line or default) to: {}", maxFileSizeArg);
        logger.info("Attempting to set Max Request Size (from command line or default) to: {}", maxRequestSizeArg);

        ConfigurableApplicationContext context = SpringApplication.run(MainApplication.class, args);
        logger.info("RAG MCP Assistant (Multi-Module) is running!");
        // ... (rest of your logging)

        logger.info("\n--- Final System Properties (includes multipart config if passed) ---");
        Properties systemProperties = System.getProperties();
        for (Map.Entry<Object, Object> entry : systemProperties.entrySet()) {
            String key = entry.getKey().toString();
            if (key.startsWith("kompile.multipart") || key.startsWith("java.runtime") || key.startsWith("os.name")) { // Filter for relevance
                logger.info("{}: {}", key, entry.getValue());
            }
        }

        logger.info("\n--- Spring Boot Environment Properties (filtered for multipart) ---");
        ConfigurableEnvironment springEnv = context.getEnvironment();
        StreamSupport.stream(springEnv.getPropertySources().spliterator(), false)
                .filter(ps -> ps instanceof EnumerablePropertySource)
                .map(ps -> (EnumerablePropertySource<?>) ps)
                .forEach(ps -> {
                    Arrays.stream(ps.getPropertyNames())
                            .filter(propName -> propName.startsWith("kompile.multipart"))
                            .sorted()
                            .forEach(propName -> {
                                try {
                                    Object propValue = ps.getProperty(propName);
                                    logger.info("  From Property Source '{}': {} = {}", ps.getName(), propName, propValue);
                                } catch (Exception e) {
                                    logger.error("  Error retrieving property {} from {}: {}", propName, ps.getName(), e.getMessage());
                                }
                            });
                });
    }
}