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

import ai.kompile.app.services.AppIndexConfigService;
import ai.kompile.app.services.ServerPortService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for application configuration management.
 * Exposes functionality to view application configuration and status.
 */
@Component
public class ApplicationConfigTool {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationConfigTool.class);

    private final ApplicationContext applicationContext;
    private final Environment environment;
    private final ServerPortService serverPortService;
    private final AppIndexConfigService appIndexConfigService;

    @Value("${spring.application.name:kompile-app}")
    private String applicationName;

    @Value("${kompile.app.title:Kompile RAG Console}")
    private String appTitle;

    @Value("${app.document.uploads-path:./data/input_documents/uploads}")
    private String uploadsPath;

    @Value("${kompile.embedding.anserini.enabled:false}")
    private boolean anseriniEmbeddingEnabled;

    @Value("${kompile.embedding.anserini.model-identifier:}")
    private String embeddingModelIdentifier;

    @Value("${kompile.vectorstore.anserini.enabled:false}")
    private boolean anseriniVectorStoreEnabled;

    @Autowired
    public ApplicationConfigTool(ApplicationContext applicationContext, Environment environment,
            ServerPortService serverPortService,
            @Autowired(required = false) AppIndexConfigService appIndexConfigService) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.serverPortService = serverPortService;
        this.appIndexConfigService = appIndexConfigService;
        logger.info("ApplicationConfigTool initialized");
    }

    // Input records for tools
    public record GetAppConfigInput(Boolean includeDetails) {
    }

    public record GetActiveProfilesInput() {
    }

    public record GetConfigPropertyInput(String propertyName) {
    }

    public record GetComponentStatusInput() {
    }

    public record ListBeansInput(String packageFilter, Integer limit) {
    }

    /**
     * Gets the application configuration.
     */
    @Tool(name = "get_app_config", description = "Gets the application configuration including name, title, server port, and key paths. Set includeDetails=true for additional configuration details.")
    public Map<String, Object> getAppConfig(GetAppConfigInput input) {
        logger.info("Getting app config, includeDetails: {}", input.includeDetails());

        try {
            boolean includeDetails = input.includeDetails() != null && input.includeDetails();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");

            // Basic config
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("applicationName", applicationName);
            config.put("appTitle", appTitle);
            config.put("serverPort", serverPortService.getActualPort());
            result.put("application", config);

            // Paths
            Map<String, Object> paths = new LinkedHashMap<>();
            String resolvedIndexPath = appIndexConfigService != null
                    ? appIndexConfigService.getConfiguration().getKeywordIndexPath()
                    : "unconfigured";
            paths.put("indexPath", resolvedIndexPath);
            paths.put("uploadsPath", uploadsPath);
            result.put("paths", paths);

            // Embedding config
            Map<String, Object> embedding = new LinkedHashMap<>();
            embedding.put("anseriniEnabled", anseriniEmbeddingEnabled);
            if (embeddingModelIdentifier != null && !embeddingModelIdentifier.isEmpty()) {
                embedding.put("modelIdentifier", embeddingModelIdentifier);
            }
            result.put("embedding", embedding);

            // Vector store config
            Map<String, Object> vectorStore = new LinkedHashMap<>();
            vectorStore.put("anseriniEnabled", anseriniVectorStoreEnabled);
            result.put("vectorStore", vectorStore);

            // Additional details
            if (includeDetails) {
                String[] activeProfiles = environment.getActiveProfiles();
                result.put("activeProfiles", activeProfiles.length > 0 ? activeProfiles : new String[] { "default" });

                // Bean counts by package
                Map<String, Integer> beanCounts = new LinkedHashMap<>();
                String[] beanNames = applicationContext.getBeanDefinitionNames();
                for (String beanName : beanNames) {
                    try {
                        Object bean = applicationContext.getBean(beanName);
                        String pkg = bean.getClass().getPackage() != null ? bean.getClass().getPackage().getName()
                                : "unknown";
                        // Simplify package name
                        if (pkg.startsWith("ai.kompile")) {
                            pkg = pkg.replace("ai.kompile.", "kompile.");
                            int lastDot = pkg.indexOf('.', 8);
                            if (lastDot > 0) {
                                pkg = pkg.substring(0, lastDot);
                            }
                        } else if (pkg.startsWith("org.springframework")) {
                            pkg = "spring.*";
                        } else {
                            pkg = "other";
                        }
                        beanCounts.merge(pkg, 1, Integer::sum);
                    } catch (Exception e) {
                        logger.debug("Skipping bean '{}' during package count (bean not accessible): {}", beanName, e.getMessage());
                    }
                }
                result.put("beanCountsByPackage", beanCounts);
                result.put("totalBeanCount", beanNames.length);
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting app config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get config: " + e.getMessage());
        }
    }

    /**
     * Gets the active Spring profiles.
     */
    @Tool(name = "get_active_profiles", description = "Gets the currently active Spring profiles which determine which configuration and beans are loaded.")
    public Map<String, Object> getActiveProfiles(GetActiveProfilesInput input) {
        logger.info("Getting active profiles");

        try {
            String[] activeProfiles = environment.getActiveProfiles();
            String[] defaultProfiles = environment.getDefaultProfiles();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("activeProfiles", activeProfiles.length > 0 ? activeProfiles : new String[] { "none" });
            result.put("defaultProfiles", defaultProfiles);
            result.put("usingDefaultProfiles", activeProfiles.length == 0);

            return result;

        } catch (Exception e) {
            logger.error("Error getting active profiles: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get profiles: " + e.getMessage());
        }
    }

    /**
     * Gets a specific configuration property value.
     */
    @Tool(name = "get_config_property", description = "Gets the value of a specific configuration property by name. Property names use dot notation (e.g., 'server.port', 'spring.application.name').")
    public Map<String, Object> getConfigProperty(GetConfigPropertyInput input) {
        logger.info("Getting config property: {}", input.propertyName());

        if (input.propertyName() == null || input.propertyName().trim().isEmpty()) {
            return Map.of("status", "error", "error", "Property name is required");
        }

        // Security check - don't expose sensitive properties
        String propName = input.propertyName().toLowerCase();
        if (propName.contains("password") || propName.contains("secret") ||
                propName.contains("key") || propName.contains("token") ||
                propName.contains("credential")) {
            return Map.of("status", "error", "error", "Cannot retrieve sensitive properties");
        }

        try {
            String value = environment.getProperty(input.propertyName());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("propertyName", input.propertyName());

            if (value != null) {
                result.put("value", value);
                result.put("found", true);
            } else {
                result.put("found", false);
                result.put("message", "Property not found or not set");
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting config property: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get property: " + e.getMessage());
        }
    }

    /**
     * Gets the status of key application components.
     */
    @Tool(name = "get_component_status", description = "Gets the status of key application components including embedding models, vector stores, document loaders, and other services.")
    public Map<String, Object> getComponentStatus(GetComponentStatusInput input) {
        logger.info("Getting component status");

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("timestamp", new Date().toString());

            Map<String, Object> components = new LinkedHashMap<>();

            // Check for key components
            String[] componentTypes = {
                    "ai.kompile.core.embeddings.EmbeddingModel",
                    "ai.kompile.core.embeddings.VectorStore",
                    "ai.kompile.core.retrievers.DocumentRetriever",
                    "ai.kompile.core.loaders.DocumentLoader",
                    "ai.kompile.core.chunkers.TextChunker"
            };

            for (String typeName : componentTypes) {
                try {
                    Class<?> type = Class.forName(typeName);
                    String[] beanNames = applicationContext.getBeanNamesForType(type);

                    String simpleName = typeName.substring(typeName.lastIndexOf('.') + 1);
                    Map<String, Object> componentInfo = new LinkedHashMap<>();
                    componentInfo.put("available", beanNames.length > 0);
                    componentInfo.put("count", beanNames.length);

                    if (beanNames.length > 0) {
                        List<String> implementations = new ArrayList<>();
                        for (String beanName : beanNames) {
                            try {
                                Object bean = applicationContext.getBean(beanName);
                                implementations.add(bean.getClass().getSimpleName());
                            } catch (Exception e) {
                                logger.debug("Could not introspect bean '{}' for component listing: {}", beanName, e.getMessage());
                            }
                        }
                        componentInfo.put("implementations", implementations);
                    }

                    components.put(simpleName, componentInfo);

                } catch (ClassNotFoundException ignored) {
                    // Component type not on classpath
                }
            }

            result.put("components", components);

            // Overall health
            boolean hasEmbedding = components.containsKey("EmbeddingModel") &&
                    Boolean.TRUE.equals(((Map<?, ?>) components.get("EmbeddingModel")).get("available"));
            boolean hasVectorStore = components.containsKey("VectorStore") &&
                    Boolean.TRUE.equals(((Map<?, ?>) components.get("VectorStore")).get("available"));
            boolean hasRetriever = components.containsKey("DocumentRetriever") &&
                    Boolean.TRUE.equals(((Map<?, ?>) components.get("DocumentRetriever")).get("available"));

            Map<String, Object> health = new LinkedHashMap<>();
            health.put("ragCapable", hasEmbedding && hasVectorStore && hasRetriever);
            health.put("embeddingAvailable", hasEmbedding);
            health.put("vectorStoreAvailable", hasVectorStore);
            health.put("retrieverAvailable", hasRetriever);
            result.put("health", health);

            return result;

        } catch (Exception e) {
            logger.error("Error getting component status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get status: " + e.getMessage());
        }
    }

    /**
     * Lists Spring beans with optional package filtering.
     */
    @Tool(name = "list_beans", description = "Lists Spring beans in the application context. Optionally filter by package prefix (e.g., 'ai.kompile') and limit results (default 50, max 200).")
    public Map<String, Object> listBeans(ListBeansInput input) {
        logger.info("Listing beans, packageFilter: {}, limit: {}", input.packageFilter(), input.limit());

        try {
            int limit = input.limit() != null && input.limit() > 0 ? Math.min(input.limit(), 200) : 50;
            String packageFilter = input.packageFilter();

            String[] beanNames = applicationContext.getBeanDefinitionNames();
            List<Map<String, Object>> beans = new ArrayList<>();

            for (String beanName : beanNames) {
                if (beans.size() >= limit)
                    break;

                try {
                    Object bean = applicationContext.getBean(beanName);
                    String className = bean.getClass().getName();

                    // Apply package filter
                    if (packageFilter != null && !packageFilter.isEmpty()) {
                        if (!className.startsWith(packageFilter)) {
                            continue;
                        }
                    }

                    Map<String, Object> beanInfo = new LinkedHashMap<>();
                    beanInfo.put("name", beanName);
                    beanInfo.put("className", className);
                    beanInfo.put("simpleClassName", bean.getClass().getSimpleName());
                    beans.add(beanInfo);

                } catch (Exception ignored) {
                    // Skip beans that can't be retrieved
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("totalBeanCount", beanNames.length);
            result.put("returnedCount", beans.size());
            result.put("limit", limit);
            if (packageFilter != null) {
                result.put("packageFilter", packageFilter);
            }
            result.put("beans", beans);

            return result;

        } catch (Exception e) {
            logger.error("Error listing beans: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to list beans: " + e.getMessage());
        }
    }
}
