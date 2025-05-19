/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.app; // New package for the main application module

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.stream.StreamSupport;

@SpringBootApplication(scanBasePackages = "ai.kompile")
@EnableConfigurationProperties({})
public class MainApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(MainApplication.class, args);
        System.out.println("RAG MCP Assistant (Multi-Module) is running!");
        System.out.println("API Endpoints typically under /api/...");
        System.out.println("MCP Server SSE endpoint likely at: http://localhost:8080/mcp/sse (check Spring AI defaults)");
        System.out.println("MCP Server Message endpoint likely at: http://localhost:8080/mcp/message (check Spring AI defaults)");

        System.out.println("\n--- Classpath Dependencies ---");
        String classPath = System.getProperty("java.class.path");
        if (classPath != null) {
            String[] classPathEntries = classPath.split(System.getProperty("path.separator"));
            Arrays.stream(classPathEntries).forEach(entry -> System.out.println(entry));
        } else {
            System.out.println("Classpath not found.");
        }

        System.out.println("\n--- System Properties ---");
        Properties systemProperties = System.getProperties();
        for (Map.Entry<Object, Object> entry : systemProperties.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
        }

        System.out.println("\n--- Spring Boot Environment Properties ---");
        ConfigurableEnvironment springEnv = context.getEnvironment();
        StreamSupport.stream(springEnv.getPropertySources().spliterator(), false)
                .filter(ps -> ps instanceof EnumerablePropertySource)
                .map(ps -> (EnumerablePropertySource<?>) ps)
                .forEach(ps -> {
                    System.out.println("Property Source: " + ps.getName());
                    Arrays.stream(ps.getPropertyNames())
                            .sorted()
                            .forEach(propName -> {
                                try {
                                    Object propValue = ps.getProperty(propName);
                                    // Avoid printing sensitive properties or very long values if necessary
                                    // For example, you might want to skip properties containing "password", "secret", "key"
                                    // or truncate long strings.
                                    if (propValue != null && (propName.toLowerCase().contains("password") || propName.toLowerCase().contains("secret") || propName.toLowerCase().contains("key"))) {
                                        System.out.println("  " + propName + ": ******");
                                    } else {
                                        System.out.println("  " + propName + ": " + propValue);
                                    }
                                } catch (Exception e) {
                                    System.out.println("  " + propName + ": Error retrieving value - " + e.getMessage());
                                }
                            });
                });

        // Consider adding a health check endpoint or more specific startup messages.
    }
}