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

package ai.kompile.embedding.postgresml.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.postgresml.PostgresMlEmbeddingModel;
import org.springframework.ai.postgresml.PostgresMlEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * Configuration that creates PostgresML Spring AI embedding model beans
 * and ensures PostgresML is properly set up when the application starts.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PostgresMlEmbeddingProperties.class)
@ConditionalOnProperty(prefix = "spring.ai.postgresml.embedding", name = "enabled", havingValue = "true", matchIfMissing = false)
public class PostgresMlEmbeddingConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(PostgresMlEmbeddingConfiguration.class);

    private final PostgresMlEmbeddingProperties properties;
    private final ObjectMapper objectMapper;

    public PostgresMlEmbeddingConfiguration(PostgresMlEmbeddingProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        logger.info("PostgresMlEmbeddingConfiguration initialized with properties: {}", properties);
    }

    /**
     * Creates the PostgresML Spring AI EmbeddingModel bean.
     * This is the core Spring AI interface that will be injected into PostgresMlEmbeddingModelImpl.
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.postgresml.embedding", name = "enabled", havingValue = "true")
    public EmbeddingModel postgresMlEmbeddingModel(JdbcTemplate jdbcTemplate) {
        
        logger.info("Creating PostgresML Spring AI EmbeddingModel bean with transformer: {}", properties.getTransformer());
        
        // Parse kwargs JSON string to Map
        Map<String, Object> kwargsMap;
        try {
            if (properties.getKwargs() == null || properties.getKwargs().trim().isEmpty()) {
                kwargsMap = Map.of();
            } else {
                kwargsMap = objectMapper.readValue(properties.getKwargs(), new TypeReference<Map<String, Object>>() {});
            }
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse kwargs JSON '{}', using empty map instead. Error: {}", 
                    properties.getKwargs(), e.getMessage());
            kwargsMap = Map.of();
        }
        
        // Create PostgresML embedding options
        PostgresMlEmbeddingOptions options = PostgresMlEmbeddingOptions.builder()
                .transformer(properties.getTransformer())
                .vectorType(properties.getVectorType())
                .kwargs(kwargsMap)  // Pass Map directly instead of String
                .metadataMode(properties.getMetadataMode())
                .build();
        
        // Create and return the PostgresML embedding model
        // Pass createExtension=true if auto-create-extension is enabled
        PostgresMlEmbeddingModel embeddingModel = new PostgresMlEmbeddingModel(
                jdbcTemplate, 
                options, 
                properties.isAutoCreateExtension()
        );
        
        logger.info("PostgresML Spring AI EmbeddingModel bean created successfully");
        return embeddingModel;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(JdbcTemplate jdbcTemplate) {
        if (properties.isVerifyInstallation()) {
            logger.info("Verifying PostgresML installation on application startup...");
            verifyPostgresMlSetup(jdbcTemplate);
        }
    }

    public void verifyPostgresMlSetup(JdbcTemplate jdbcTemplate) {
        try {
            // Check if extension is installed
            if (!isPgmlExtensionInstalled(jdbcTemplate)) {
                if (properties.isAutoCreateExtension()) {
                    logger.info("PostgresML extension not found. It will be created automatically when the EmbeddingModel is used.");
                } else {
                    throw new IllegalStateException(
                            "PostgresML extension is not installed and auto-creation is disabled. " +
                                    "Please install manually: CREATE EXTENSION IF NOT EXISTS pgml;"
                    );
                }
            }

            // Verify the setup if extension exists
            if (isPgmlExtensionInstalled(jdbcTemplate)) {
                verifyPgmlFunctionality(jdbcTemplate);
            }
            
            logger.info("PostgresML setup verification completed successfully");

        } catch (Exception e) {
            logger.error("PostgresML setup verification failed", e);
            printSetupGuidance();
            throw e;
        }
    }

    private boolean isPgmlExtensionInstalled(JdbcTemplate jdbcTemplate) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pgml'",
                    Integer.class
            );
            return count != null && count > 0;
        } catch (DataAccessException e) {
            logger.warn("Failed to check if pgml extension is installed: {}", e.getMessage());
            return false;
        }
    }

    private void verifyPgmlFunctionality(JdbcTemplate jdbcTemplate) throws DataAccessException {
        // Check if pgml schema exists
        Integer schemaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pgml'",
                Integer.class
        );

        if (schemaCount == null || schemaCount == 0) {
            throw new IllegalStateException("PostgresML schema 'pgml' not found after extension creation");
        }

        // Check if essential functions exist
        Integer embedFunctionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.routines " +
                        "WHERE routine_schema = 'pgml' AND routine_name = 'embed'",
                Integer.class
        );

        if (embedFunctionCount == null || embedFunctionCount == 0) {
            throw new IllegalStateException("PostgresML function 'pgml.embed' not found");
        }

        // Check if we can call pgml.version() (basic functionality test)
        try {
            String version = jdbcTemplate.queryForObject("SELECT pgml.version()", String.class);
            logger.info("PostgresML version: {}", version);
        } catch (DataAccessException e) {
            logger.warn("Could not retrieve PostgresML version: {}", e.getMessage());
            // This is not a fatal error - the extension might still work
        }

        logger.info("PostgresML functionality verification completed");
    }

    private void printSetupGuidance() {
        logger.error("");
        logger.error("═".repeat(100));
        logger.error("                      POSTGRESML SETUP GUIDANCE");
        logger.error("═".repeat(100));
        logger.error("");
        logger.error("PostgresML requires proper installation on your PostgreSQL server.");
        logger.error("");
        logger.error("QUICK START OPTIONS:");
        logger.error("");
        logger.error("1. DOCKER (Recommended for development):");
        logger.error("   docker run -d \\");
        logger.error("     --name postgresml \\");
        logger.error("     -p 5432:5432 \\");
        logger.error("     -v postgresml_data:/var/lib/postgresql \\");
        logger.error("     ghcr.io/postgresml/postgresml:2.10.0");
        logger.error("");
        logger.error("2. MANUAL INSTALLATION:");
        logger.error("   - Ubuntu: apt install postgresql-pgml-15");
        logger.error("   - From source: https://postgresml.org/docs/installation");
        logger.error("");
        logger.error("3. CLOUD (PostgresML managed service):");
        logger.error("   - Sign up at https://postgresml.org/");
        logger.error("");
        logger.error("AFTER INSTALLATION:");
        logger.error("   Connect to your database and run:");
        logger.error("   CREATE EXTENSION IF NOT EXISTS pgml;");
        logger.error("   SELECT pgml.version();");
        logger.error("");
        logger.error("CONFIGURATION PROPERTIES:");
        logger.error("   spring.ai.postgresml.embedding.enabled=true");
        logger.error("   spring.ai.postgresml.embedding.transformer=distilbert-base-uncased");
        logger.error("   spring.ai.postgresml.embedding.vector-type=PG_ARRAY");
        logger.error("   spring.ai.postgresml.embedding.kwargs={}");
        logger.error("   spring.ai.postgresml.embedding.auto-create-extension=true");
        logger.error("   spring.ai.postgresml.embedding.verify-installation=true");
        logger.error("");
        logger.error("TRANSFORMER OPTIONS:");
        logger.error("   # Lightweight and fast");
        logger.error("   spring.ai.postgresml.embedding.transformer=sentence-transformers/all-MiniLM-L6-v2");
        logger.error("   # High quality");
        logger.error("   spring.ai.postgresml.embedding.transformer=sentence-transformers/all-mpnet-base-v2");
        logger.error("   # Optimized for Q&A");
        logger.error("   spring.ai.postgresml.embedding.transformer=sentence-transformers/multi-qa-MiniLM-L6-cos-v1");
        logger.error("");
        logger.error("═".repeat(100));
        logger.error("");
    }

    /**
     * Manual method to check PostgresML status - useful for health checks
     */
    public PostgresMlStatus checkStatus(JdbcTemplate jdbcTemplate) {
        try {
            boolean extensionInstalled = isPgmlExtensionInstalled(jdbcTemplate);

            if (!extensionInstalled) {
                return new PostgresMlStatus(false, "PostgresML extension not installed", null);
            }

            try {
                String version = jdbcTemplate.queryForObject("SELECT pgml.version()", String.class);
                return new PostgresMlStatus(true, "PostgresML is working correctly", version);
            } catch (Exception e) {
                return new PostgresMlStatus(false, "PostgresML extension installed but not functional: " + e.getMessage(), null);
            }

        } catch (Exception e) {
            return new PostgresMlStatus(false, "Failed to check PostgresML status: " + e.getMessage(), null);
        }
    }

    /**
     * Status information about PostgresML installation
     */
    public static class PostgresMlStatus {
        private final boolean working;
        private final String message;
        private final String version;

        public PostgresMlStatus(boolean working, String message, String version) {
            this.working = working;
            this.message = message;
            this.version = version;
        }

        public boolean isWorking() { return working; }
        public String getMessage() { return message; }
        public String getVersion() { return version; }

        @Override
        public String toString() {
            return String.format("PostgresMlStatus{working=%s, message='%s', version='%s'}",
                    working, message, version);
        }
    }
}
