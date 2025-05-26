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

package ai.kompile.embedding.postgresml;

import ai.kompile.core.embeddings.EmbeddingModel; // Your core interface
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service("postgresMlEmbeddingModelImpl")
@ConditionalOnProperty(prefix = "spring.ai.postgresml.embedding", name = "enabled", havingValue = "true", matchIfMissing = false)
public class PostgresMlEmbeddingModelImpl implements EmbeddingModel {

    private static final Logger logger = LoggerFactory.getLogger(PostgresMlEmbeddingModelImpl.class);

    // This is the generic Spring AI interface. The actual implementation (PGML) will be injected.
    private final org.springframework.ai.embedding.EmbeddingModel springAiEmbeddingModel;
    private final JdbcTemplate jdbcTemplate;

    // Track initialization state
    private volatile boolean schemaValidated = false;
    private volatile String validationError = null;

    @Autowired
    public PostgresMlEmbeddingModelImpl(
            org.springframework.ai.embedding.EmbeddingModel springAiEmbeddingModel,
            JdbcTemplate jdbcTemplate) {
        this.springAiEmbeddingModel = springAiEmbeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        logger.info("PostgresMlEmbeddingModelImpl initialized with Spring AI EmbeddingModel: {}",
                springAiEmbeddingModel.getClass().getName());
    }

    @PostConstruct
    public void validateAndInitializeSchema() {
        logger.info("Validating PostgresML schema and functions...");

        try {
            // Step 1: Check if pgml schema exists
            validatePgmlSchema();

            // Step 2: Check if pgml.embed function exists
            validatePgmlEmbedFunction();

            // Step 3: Test the function with a simple call
            testPgmlEmbedFunction();

            schemaValidated = true;
            logger.info("PostgresML schema validation completed successfully");

        } catch (Exception e) {
            validationError = "PostgresML schema validation failed: " + e.getMessage();
            logger.error(validationError, e);

            // Provide detailed troubleshooting information
            logTroubleshootingInfo();
        }
    }

    private void validatePgmlSchema() throws DataAccessException {
        logger.debug("Checking if pgml schema exists...");

        Integer schemaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pgml'",
                Integer.class
        );

        if (schemaCount == null || schemaCount == 0) {
            throw new IllegalStateException(
                    "PostgresML schema 'pgml' does not exist. " +
                            "Please install the PostgresML extension: CREATE EXTENSION IF NOT EXISTS pgml;"
            );
        }

        logger.debug("✓ pgml schema exists");
    }

    private void validatePgmlEmbedFunction() throws DataAccessException {
        logger.debug("Checking if pgml.embed function exists...");

        Integer functionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.routines " +
                        "WHERE routine_schema = 'pgml' AND routine_name = 'embed'",
                Integer.class
        );

        if (functionCount == null || functionCount == 0) {
            throw new IllegalStateException(
                    "PostgresML function 'pgml.embed' does not exist. " +
                            "The PostgresML extension may not be properly installed. " +
                            "Please ensure the complete PostgresML extension is installed."
            );
        }

        logger.debug("✓ pgml.embed function exists ({} variants found)", functionCount);
    }

    private void testPgmlEmbedFunction() throws DataAccessException {
        logger.debug("Testing pgml.embed function with a simple call...");

        try {
            // Test with a simple call - using a common model that should be available
            jdbcTemplate.queryForObject(
                    "SELECT array_length(pgml.embed('distilbert-base-uncased', 'test', '{}'), 1)",
                    Integer.class
            );
            logger.debug("✓ pgml.embed function test call successful");

        } catch (DataAccessException e) {
            String errorMessage = e.getMessage();

            // Check for common error patterns and provide specific guidance
            if (errorMessage != null) {
                if (errorMessage.contains("model not found") || errorMessage.contains("no such model")) {
                    logger.warn("pgml.embed function exists but default model 'distilbert-base-uncased' is not available. " +
                            "This is normal - models will be downloaded on first use.");
                } else if (errorMessage.contains("Python") || errorMessage.contains("runtime")) {
                    throw new IllegalStateException(
                            "PostgresML function exists but Python runtime is not properly configured. " +
                                    "Please ensure PostgresML is fully installed with Python dependencies."
                    );
                } else {
                    // Re-throw the original exception for other errors
                    throw e;
                }
            } else {
                throw e;
            }
        }
    }

    private void logTroubleshootingInfo() {
        logger.error("=".repeat(80));
        logger.error("POSTGRESQL POSTGRESML SETUP ISSUE DETECTED");
        logger.error("=".repeat(80));

        try {
            // Check PostgreSQL version
            String pgVersion = jdbcTemplate.queryForObject("SELECT version()", String.class);
            logger.error("PostgreSQL Version: {}", pgVersion);

            // Check available extensions
            List<String> extensions = jdbcTemplate.queryForList(
                    "SELECT name FROM pg_available_extensions WHERE name LIKE '%pgml%' OR name LIKE '%vector%'",
                    String.class
            );
            logger.error("Available extensions matching 'pgml' or 'vector': {}", extensions);

            // Check installed extensions
            List<String> installedExtensions = jdbcTemplate.queryForList(
                    "SELECT extname FROM pg_extension WHERE extname LIKE '%pgml%' OR extname LIKE '%vector%'",
                    String.class
            );
            logger.error("Installed extensions matching 'pgml' or 'vector': {}", installedExtensions);

        } catch (Exception e) {
            logger.error("Failed to gather troubleshooting information: {}", e.getMessage());
        }

        logger.error("-".repeat(80));
        logger.error("RESOLUTION STEPS:");
        logger.error("1. Install PostgresML extension: CREATE EXTENSION IF NOT EXISTS pgml;");
        logger.error("2. If using Docker: docker run -p 5432:5432 ghcr.io/postgresml/postgresml:2.10.0");
        logger.error("3. If self-hosting: Follow installation guide at https://postgresml.org/docs/installation");
        logger.error("4. Verify installation: SELECT pgml.version();");
        logger.error("=".repeat(80));
    }

    private void ensureSchemaValidated() {
        if (!schemaValidated) {
            if (validationError != null) {
                throw new IllegalStateException(validationError);
            } else {
                throw new IllegalStateException("PostgresML schema has not been validated yet. Call validateAndInitializeSchema() first.");
            }
        }
    }

    @Override
    public List<Float> embed(String text) {
        ensureSchemaValidated();

        if (text == null || text.trim().isEmpty()) {
            logger.warn("Received null or empty text for embedding, returning empty list.");
            return Collections.emptyList();
        }

        logger.debug("Embedding single text string using underlying Spring AI PGML EmbeddingModel...");

        try {
            // Based on compiler errors, the Spring AI EmbeddingModel interface call resolves to float[]
            float[] floatArrayEmbedding = this.springAiEmbeddingModel.embed(text);

            if (floatArrayEmbedding == null) {
                logger.error("Underlying Spring AI PGML EmbeddingModel returned null for text: {}",
                        text.substring(0, Math.min(text.length(), 70)) + "...");
                return Collections.emptyList();
            }

            List<Float> result = new ArrayList<>(floatArrayEmbedding.length);
            for (float f : floatArrayEmbedding) {
                result.add(f);
            }
            return result;

        } catch (Exception e) {
            logger.error("Failed to generate embedding for text: {}", e.getMessage(), e);

            // Check if this is a schema/function issue
            if (e.getMessage() != null &&
                    (e.getMessage().contains("pgml.embed") ||
                            e.getMessage().contains("function") ||
                            e.getMessage().contains("schema"))) {
                // Re-validate schema in case of connection issues
                try {
                    validateAndInitializeSchema();
                } catch (Exception validationException) {
                    logger.error("Schema re-validation also failed: {}", validationException.getMessage());
                }
            }

            throw new RuntimeException("Failed to generate embedding", e);
        }
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
        ensureSchemaValidated();

        if (texts == null || texts.isEmpty() || texts.stream().allMatch(t -> t == null || t.trim().isEmpty())) {
            logger.warn("Received null, empty, or all-empty list of texts for embedding, returning empty list.");
            return Collections.emptyList();
        }

        logger.debug("Embedding {} text strings using underlying Spring AI PGML EmbeddingModel...", texts.size());

        try {
            // Based on compiler errors, this resolves to List<float[]>
            List<float[]> listOfFloatArrayEmbeddings = this.springAiEmbeddingModel.embed(texts);

            if (listOfFloatArrayEmbeddings == null) {
                logger.error("Underlying Spring AI PGML EmbeddingModel returned null for a list of texts.");
                return Collections.emptyList();
            }

            return listOfFloatArrayEmbeddings.stream()
                    .map(floatArray -> {
                        if (floatArray == null) {
                            logger.warn("A null embedding was returned by the underlying Spring AI PGML EmbeddingModel for one of the texts in the batch.");
                            return Collections.<Float>emptyList();
                        }
                        List<Float> floatList = new ArrayList<>(floatArray.length);
                        for (float f : floatArray) {
                            floatList.add(f);
                        }
                        return floatList;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Failed to generate embeddings for {} texts: {}", texts.size(), e.getMessage(), e);
            throw new RuntimeException("Failed to generate embeddings", e);
        }
    }

    @Override
    public List<List<Float>> embedDocuments(List<Document> documents) {
        ensureSchemaValidated();

        if (documents == null || documents.isEmpty()) {
            logger.warn("Received null or empty list of documents for embedding, returning empty list.");
            return Collections.emptyList();
        }

        logger.debug("Embedding {} documents using underlying Spring AI PGML EmbeddingModel...", documents.size());

        List<String> contents = documents.stream()
                .map(Document::getText)
                .filter(content -> content != null && !content.trim().isEmpty())
                .collect(Collectors.toList());

        if (contents.isEmpty()) {
            logger.warn("All documents had null or empty content. Nothing to embed.");
            return Collections.emptyList();
        }

        return embed(contents);
    }

    @Override
    public int dimensions() {
        ensureSchemaValidated();

        try {
            int dims = this.springAiEmbeddingModel.dimensions();
            if (dims > 0) {
                return dims;
            }

            logger.warn("Underlying Spring AI PGML EmbeddingModel returned non-positive dimensions ({}). " +
                    "Attempting fallback by embedding a test string.", dims);

            float[] sampleEmbedding = this.springAiEmbeddingModel.embed("test");
            return (sampleEmbedding != null) ? sampleEmbedding.length : -1;

        } catch (Exception e) {
            logger.error("Could not determine embedding dimensions from underlying Spring AI PGML EmbeddingModel. Error: {}. " +
                    "Returning -1 to indicate failure to determine.", e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Public method to check if the schema validation was successful
     */
    public boolean isSchemaValidated() {
        return schemaValidated;
    }

    /**
     * Public method to get validation error details
     */
    public String getValidationError() {
        return validationError;
    }

    /**
     * Public method to manually re-validate schema (useful for testing/debugging)
     */
    public void revalidateSchema() {
        schemaValidated = false;
        validationError = null;
        validateAndInitializeSchema();
    }
}