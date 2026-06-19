/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.build;

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.ModelDescriptor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Generates the Spring Boot application.properties file for a RAG application instance.
 * Extracted from RagPomGenerator to separate property-file generation concerns.
 */
public class ApplicationPropertiesWriter {

    private final String instanceArtifactId;
    private final String instanceGroupId;
    private final String appTitle;
    private final String databaseUrl;
    private final String databaseUsername;
    private final String databasePassword;
    private final boolean enableSchemaInit;
    private final boolean includeVectorstorePgvector;
    private final boolean includeEmbeddingPostgresml;
    private final boolean includePgmlIndexer;
    private final boolean includeEmbeddingOpenai;
    private final boolean includeEmbeddingSentenceTransformer;
    private final boolean includeLlmOpenai;
    private final boolean includeLlmAnthropic;
    private final boolean includeLlmGemini;
    private final boolean includeVectorstoreChroma;
    private final boolean includeAnserini;
    private final boolean includeChunkerSentence;
    private final List<String> supportedLanguages;
    private final List<String> anseriniIndexIds;
    private final List<String> anseriniEncoderModelIds;
    private final Map<String, Path> resolvedModelPaths;
    private final KompileModelManager modelManager;

    public ApplicationPropertiesWriter(
            String instanceArtifactId,
            String instanceGroupId,
            String appTitle,
            String databaseUrl,
            String databaseUsername,
            String databasePassword,
            boolean enableSchemaInit,
            boolean includeVectorstorePgvector,
            boolean includeEmbeddingPostgresml,
            boolean includePgmlIndexer,
            boolean includeEmbeddingOpenai,
            boolean includeEmbeddingSentenceTransformer,
            boolean includeLlmOpenai,
            boolean includeLlmAnthropic,
            boolean includeLlmGemini,
            boolean includeVectorstoreChroma,
            boolean includeAnserini,
            boolean includeChunkerSentence,
            List<String> supportedLanguages,
            List<String> anseriniIndexIds,
            List<String> anseriniEncoderModelIds,
            Map<String, Path> resolvedModelPaths,
            KompileModelManager modelManager) {
        this.instanceArtifactId = instanceArtifactId;
        this.instanceGroupId = instanceGroupId;
        this.appTitle = appTitle;
        this.databaseUrl = databaseUrl;
        this.databaseUsername = databaseUsername;
        this.databasePassword = databasePassword;
        this.enableSchemaInit = enableSchemaInit;
        this.includeVectorstorePgvector = includeVectorstorePgvector;
        this.includeEmbeddingPostgresml = includeEmbeddingPostgresml;
        this.includePgmlIndexer = includePgmlIndexer;
        this.includeEmbeddingOpenai = includeEmbeddingOpenai;
        this.includeEmbeddingSentenceTransformer = includeEmbeddingSentenceTransformer;
        this.includeLlmOpenai = includeLlmOpenai;
        this.includeLlmAnthropic = includeLlmAnthropic;
        this.includeLlmGemini = includeLlmGemini;
        this.includeVectorstoreChroma = includeVectorstoreChroma;
        this.includeAnserini = includeAnserini;
        this.includeChunkerSentence = includeChunkerSentence;
        this.supportedLanguages = supportedLanguages != null ? supportedLanguages : new ArrayList<>();
        this.anseriniIndexIds = anseriniIndexIds != null ? anseriniIndexIds : new ArrayList<>();
        this.anseriniEncoderModelIds = anseriniEncoderModelIds != null ? anseriniEncoderModelIds : new ArrayList<>();
        this.resolvedModelPaths = resolvedModelPaths;
        this.modelManager = modelManager;
    }

    public void generateApplicationPropertiesFile(File projectDir, Properties pomProperties) throws IOException {
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            throw new IOException("Could not create resources directory: " + resourcesDir.getAbsolutePath());
        }
        File appPropsFile = new File(resourcesDir, "application.properties");

        try (FileWriter writer = new FileWriter(appPropsFile)) {
            writeApplicationPropertiesHeaderCustom(writer, pomProperties);

            if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
                writeDatabaseConfiguration(writer);
                writeSchemaManagementConfiguration(writer);
            }
            writeAutoConfigurationExclusions(writer);
            writeProviderEnablementFlags(writer);

            writeStructuralCustom(writer, pomProperties);

            writer.write("\n# --- Runtime Model Cache Configuration ---\n");
            writer.write("# Your application will attempt to load models from a central cache.\n");
            writer.write("# Set the " + ModelConstants.ENV_KOMPILE_MODEL_CACHE_DIR
                    + " environment variable to specify the cache location.\n");
            String defaultCachePath = Paths
                    .get(System.getProperty("user.home"), ModelConstants.DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR)
                    .toAbsolutePath().toString().replace("\\", "\\\\");
            writer.write("# If not set, it defaults to: " + defaultCachePath + "\n");
            writer.write("# RagPomGenerator used this cache path during generation: "
                    + modelManager.getBaseCachePath().toAbsolutePath().toString().replace("\\", "\\\\") + "\n");
            writer.write("kompile.model.cache.path=${" + ModelConstants.ENV_KOMPILE_MODEL_CACHE_DIR + ":"
                    + modelManager.getBaseCachePath().toAbsolutePath().toString().replace("\\", "\\\\") + "}\n\n");

            writeConfigurationTemplate(writer);
        }
        System.out.println("Generated application.properties: " + appPropsFile.getAbsolutePath());
    }

    private void writeApplicationPropertiesHeaderCustom(FileWriter writer, Properties pomProperties)
            throws IOException {
        writer.write("# Generated application.properties\n");
        writer.write("# Project: " + pomProperties.getProperty("instanceArtifactId", this.instanceArtifactId) + "\n");
        writer.write("# Generated on: " + new java.util.Date() + "\n");
        writer.write("# Configured providers: " + getProviderSummary() + "\n\n");

        writer.write("# Logging for model loading and general app behavior\n");
        writer.write("logging.level.ai.kompile.cli.main.models=INFO\n");
        writer.write("logging.level.ai.kompile.app=INFO\n");
        writer.write("logging.level.io.anserini=INFO\n\n");

        if (includeEmbeddingPostgresml || includePgmlIndexer) {
            writer.write("# =============================================================================\n");
            writer.write("# AUTOMATIC PostgresML ERROR DEBUGGING\n");
            writer.write("# =============================================================================\n");
            writer.write("logging.level.org.springframework.jdbc.core.JdbcTemplate=DEBUG\n");
            writer.write("logging.level.org.springframework.jdbc.core.StatementCreatorUtils=TRACE\n");
            writer.write("logging.level.org.springframework.ai.postgresml=DEBUG\n");
            writer.write("logging.level.org.postgresql=DEBUG\n");
            writer.write("logging.level.ai.kompile.app.pgml.indexer=DEBUG\n");
            writer.write("logging.level.ai.kompile.vectorstore=DEBUG\n");
            writer.write("logging.level.org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator=DEBUG\n");
            writer.write("logging.level.org.springframework.dao=DEBUG\n");
            writer.write("logging.level." + instanceGroupId + ".config=DEBUG\n\n");
        }
    }

    private void writeStructuralCustom(FileWriter writer, Properties pomProperties) throws IOException {
        writer.write("# =============================================================================\n");
        writer.write("# STRUCTURAL CONFIGURATION\n");
        writer.write("# =============================================================================\n");

        writer.write("spring.application.name="
                + pomProperties.getProperty("instanceArtifactId", this.instanceArtifactId) + "\n");
        writer.write("server.port=8080\n");
        writer.write("kompile.app.title=" + this.appTitle + "\n\n");

        writer.write("# Hibernate: disable ByteBuddy bytecode provider (not on classpath;\n");
        writer.write("# kompile-app excludes it for native-image compat, Hibernate falls back to none)\n");
        writer.write("spring.jpa.properties.hibernate.bytecode.provider=none\n\n");

        writer.write("# This property defines the base directory from which models will be loaded AT RUNTIME.\n");
        writer.write("# It defaults to the path used during generation if KOMPILE_MODEL_CACHE_DIR is not set.\n");
        String runtimeCachePathProperty = "kompile.runtime.model.cache.path";
        String runtimeCachePathValue = "${" + ModelConstants.ENV_KOMPILE_MODEL_CACHE_DIR + ":" +
                modelManager.getBaseCachePath().toAbsolutePath().toString().replace("\\", "\\\\") + "}";
        writer.write(runtimeCachePathProperty + "=" + runtimeCachePathValue + "\n\n");

        if (includeChunkerSentence) {
            writer.write("# OpenNLP Configuration (runtime will load models from cache)\n");
            writer.write("kompile.opennlp.models.basepath=${" + runtimeCachePathProperty + "}/opennlp\n");
            writer.write("kompile.opennlp.sentence.language="
                    + pomProperties.getProperty("kompile.opennlp.sentence.language", "en") + "\n\n");
        }

        if (includeAnserini) {
            writer.write("# Anserini Configuration (runtime will load indexes/models from cache)\n");
            writer.write("kompile.anserini.models.basepath=${" + runtimeCachePathProperty + "}/anserini\n");

            if (this.anseriniIndexIds != null && !this.anseriniIndexIds.isEmpty()) {
                for (String indexId : this.anseriniIndexIds) {
                    String trimmedIndexId = indexId.trim();
                    ModelDescriptor desc = ModelConstants.getAnseriniIndexDescriptor(trimmedIndexId);
                    if (desc != null && resolvedModelPaths.containsKey(desc.getModelId())) {
                        writer.write("anserini.indexPath." + trimmedIndexId
                                + "=${kompile.anserini.models.basepath}/indexes/" + trimmedIndexId + "\n");
                    }
                }
            } else {
                writer.write("# Default Anserini paths if no specific --anserini-indexes are given\n");
                writer.write("anserini.indexPath=${kompile.anserini.models.basepath}/indexes/keyword_index\n");
                writer.write(
                        "kompile.vectorstore.anserini.index-path=${kompile.anserini.models.basepath}/indexes/vector_index\n");
                writer.write("anserini.corpusPath=${kompile.anserini.models.basepath}/corpus/default_corpus\n");
            }

            if (this.anseriniEncoderModelIds != null && !this.anseriniEncoderModelIds.isEmpty()) {
                writer.write("\n# Anserini Encoder Model Paths (runtime will load from cache)\n");
                for (String encoderModelId : this.anseriniEncoderModelIds) {
                    String trimmedEncoderId = encoderModelId.trim();
                    ModelDescriptor desc = ModelConstants.getAnseriniEncoderModelDescriptor(trimmedEncoderId);
                    if (desc != null && resolvedModelPaths.containsKey(desc.getModelId())) {
                        String encoderModelPathValue = "${kompile.runtime.model.cache.path}/"
                                + desc.getExpectedCacheSubpath().replace("\\", "/");
                        writer.write(
                                "anserini.encoder." + trimmedEncoderId + ".model.path=" + encoderModelPathValue + "\n");
                        if ("bge-base-en-v1.5".equals(trimmedEncoderId)) {
                            writer.write("anserini.encoder." + trimmedEncoderId
                                    + ".vocab.path=${kompile.runtime.model.cache.path}/anserini/encoders/onnx/bge-base-en-v1.5/tokenizer.json\n");
                        }
                    }
                }
            }
            writer.write("\n");
        }

        writer.write("# Kompile Application Structure (original paths, review for deployment)\n");
        writer.write("app.document.sources=./data/input_documents/sample.txt,./data/input_documents/sample.pdf\n");
        writer.write("app.document.uploads-path=./data/input_documents/uploads\n");
        writer.write("mcp.filesystem.roots.default.path=./data/shared_files\n");
        writer.write("mcp.filesystem.roots.default.alias=default\n\n");
    }

    public void generateApplicationProperties(File projectDir) throws IOException {
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            throw new IOException("Could not create resources directory: " + resourcesDir.getAbsolutePath());
        }

        File appPropsFile = new File(resourcesDir, "application.properties");

        try (FileWriter writer = new FileWriter(appPropsFile)) {
            writeApplicationPropertiesHeader(writer);
            writeDatabaseConfiguration(writer);
            writeSchemaManagementConfiguration(writer);
            writeAutoConfigurationExclusions(writer);
            writeProviderEnablementFlags(writer);
            writeStructuralConfiguration(writer);
            writeConfigurationTemplate(writer);
        }

        System.out.println("Generated application.properties: " + appPropsFile.getAbsolutePath());
    }

    private void writeDatabaseConfiguration(FileWriter writer) throws IOException {
        if (!(includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer)) {
            return;
        }

        writer.write("# =============================================================================\n");
        writer.write("# DATABASE CONFIGURATION\n");
        writer.write("# Connects to real PostgreSQL server and auto-creates database if needed\n");
        writer.write("# =============================================================================\n");

        writer.write("# PostgreSQL Connection Settings\n");
        writer.write("spring.datasource.url=" + databaseUrl + "\n");
        writer.write("spring.datasource.username=" + databaseUsername + "\n");
        writer.write("spring.datasource.password=" + databasePassword + "\n");
        writer.write("spring.datasource.driver-class-name=org.postgresql.Driver\n");
        writer.write("\n");

        writer.write("# Connection Pool Configuration\n");
        writer.write("spring.datasource.type=com.zaxxer.hikari.HikariDataSource\n");
        writer.write("spring.datasource.hikari.maximum-pool-size=10\n");
        writer.write("spring.datasource.hikari.minimum-idle=2\n");
        writer.write("spring.datasource.hikari.connection-timeout=20000\n");
        writer.write("spring.datasource.hikari.idle-timeout=300000\n");
        writer.write("spring.datasource.hikari.max-lifetime=1800000\n");
        writer.write("spring.datasource.hikari.leak-detection-threshold=60000\n");
        writer.write("\n");
    }

    private void writeSchemaManagementConfiguration(FileWriter writer) throws IOException {
        if (!(includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer)) {
            return;
        }

        writer.write("# =============================================================================\n");
        writer.write("# SCHEMA MANAGEMENT CONFIGURATION\n");
        writer.write("# CRITICAL: pgml-schema.sql MUST be executed first to create pgml schema\n");
        writer.write("# =============================================================================\n");

        if (enableSchemaInit) {
            writer.write("# Simple SQL Schema Initialization\n");
            writer.write("spring.sql.init.enabled=true\n");
            writer.write("spring.sql.init.mode=always\n");

            List<String> schemaLocations = new ArrayList<>();
            if (includeEmbeddingPostgresml || includePgmlIndexer) {
                schemaLocations.add("classpath:pgml-schema.sql");
            }
            schemaLocations.add("classpath:schema.sql");

            writer.write("spring.sql.init.schema-locations=" + String.join(",", schemaLocations) + "\n");
            writer.write("spring.sql.init.data-locations=classpath:data.sql\n");
            writer.write("spring.sql.init.continue-on-error=true\n");
            writer.write("spring.sql.init.separator=;\n");
            writer.write("spring.sql.init.encoding=UTF-8\n");
        }

        writer.write("\n# Disable JPA/Hibernate Schema Management\n");
        writer.write("spring.jpa.hibernate.ddl-auto=none\n");
        writer.write("spring.jpa.generate-ddl=false\n");
        writer.write(
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration\n");
        writer.write("\n");
    }

    private void writeAutoConfigurationExclusions(FileWriter writer) throws IOException {
        List<String> exclusions = determineAutoConfigurationExclusions();

        if (!exclusions.isEmpty()) {
            writer.write("# =============================================================================\n");
            writer.write("# AUTO-CONFIGURATION EXCLUSIONS\n");
            writer.write("# These exclusions prevent bean conflicts by disabling unused provider configs\n");
            writer.write("# =============================================================================\n");
            writer.write("spring.autoconfigure.exclude=\\\n");

            for (int i = 0; i < exclusions.size(); i++) {
                writer.write("    " + exclusions.get(i));
                if (i < exclusions.size() - 1) {
                    writer.write(",\\\n");
                } else {
                    writer.write("\n\n");
                }
            }
        }
    }

    private List<String> determineAutoConfigurationExclusions() {
        List<String> exclusions = new ArrayList<>();
        // Always exclude ChatClientAutoConfiguration: when multiple Spring AI chat
        // model starters are on the classpath it fails with an ambiguous bean error.
        // Kompile manages its own chat client routing, so this auto-config is not needed.
        exclusions.add("org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration");
        return exclusions;
    }

    private void writeProviderEnablementFlags(FileWriter writer) throws IOException {
        writer.write("# =============================================================================\n");
        writer.write("# PROVIDER ENABLEMENT FLAGS\n");
        writer.write("# These flags explicitly control which providers are active\n");
        writer.write("# =============================================================================\n");

        writer.write("# Embedding Providers\n");
        writer.write("spring.ai.openai.embedding.enabled=" + includeEmbeddingOpenai + "\n");
        writer.write("spring.ai.postgresml.embedding.enabled=" + includeEmbeddingPostgresml + "\n");
        writer.write("spring.ai.transformers.embedding.enabled=" + includeEmbeddingSentenceTransformer + "\n");
        writer.write("\n");

        writer.write("# Chat Providers\n");
        writer.write("spring.ai.openai.chat.enabled=" + includeLlmOpenai + "\n");
        writer.write("spring.ai.anthropic.chat.enabled=" + includeLlmAnthropic + "\n");
        writer.write("spring.ai.vertex.ai.gemini.chat.enabled=" + includeLlmGemini + "\n");
        writer.write("\n");

        writer.write("# Vector Stores\n");
        writer.write("spring.ai.vectorstore.pgvector.enabled="
                + (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) + "\n");
        writer.write("spring.ai.vectorstore.chroma.enabled=" + includeVectorstoreChroma + "\n");
        writer.write("\n");
    }

    private void writeStructuralConfiguration(FileWriter writer) throws IOException {
        writer.write("# =============================================================================\n");
        writer.write("# STRUCTURAL CONFIGURATION\n");
        writer.write("# These are build-time decisions that don't change at runtime\n");
        writer.write("# =============================================================================\n");

        writer.write("spring.application.name=" + instanceArtifactId + "\n");
        writer.write("server.port=8080\n");
        writer.write("kompile.app.title=" + this.appTitle + "\n");
        writer.write("\n");

        writer.write("# Logging Configuration\n");
        writer.write("logging.level.ai.kompile=INFO\n");
        writer.write("logging.level.org.springframework.ai=INFO\n");
        writer.write("logging.level.org.springframework.boot.autoconfigure=INFO\n");
        writer.write("\n");

        if (includeVectorstorePgvector || includeEmbeddingPostgresml || includePgmlIndexer) {
            writer.write("# PgVector Structural Configuration\n");
            writer.write("spring.ai.vectorstore.pgvector.table-name=vector_store\n");
            writer.write("spring.ai.vectorstore.pgvector.schema-name=public\n");
            writer.write("spring.ai.vectorstore.pgvector.dimensions=1536\n");
            writer.write("spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE\n");
            writer.write("spring.ai.vectorstore.pgvector.initialize-schema=true\n");
            writer.write("spring.ai.vectorstore.pgvector.schema-validation=true\n");
            writer.write("\n");
        }

        if (includeVectorstoreChroma) {
            writer.write("# Chroma Structural Configuration\n");
            writer.write("spring.ai.vectorstore.chroma.collection-name=kompile_documents\n");
            writer.write("spring.ai.vectorstore.chroma.initialize-schema=true\n");
            writer.write("\n");
        }

        writer.write("# Kompile Application Structure\n");
        writer.write("anserini.indexPath=./data/index\n");
        writer.write("anserini.corpusPath=./data/anserini_corpus_json_staging\n");
        writer.write("app.document.sources=./data/input_documents/sample.txt,./data/input_documents/sample.pdf\n");
        writer.write("app.document.uploads-path=./data/input_documents/uploads\n");
        writer.write("mcp.filesystem.roots.default.path=./data/shared_files\n");
        writer.write("mcp.filesystem.roots.default.alias=default\n\n");

        if (includeChunkerSentence) {
            writer.write("# OpenNLP Configuration\n");
            writer.write("kompile.opennlp.sentence.language=" +
                    (supportedLanguages.isEmpty() ? "en" : supportedLanguages.get(0).toLowerCase()) + "\n");
            writer.write("kompile.opennlp.models.path=classpath:models/\n\n");
        }
    }

    private void writeConfigurationTemplate(FileWriter writer) throws IOException {
        writer.write("# =============================================================================\n");
        writer.write("# RUNTIME CONFIGURATION TEMPLATE\n");
        writer.write("# Copy and customize these settings in your environment-specific config\n");
        writer.write("# =============================================================================\n\n");

        if (includeEmbeddingOpenai || includeLlmOpenai) {
            writer.write("# OpenAI Configuration (set via environment variables or external config)\n");
            writer.write("# spring.ai.openai.api-key=${OPENAI_API_KEY}\n");
            writer.write("# spring.ai.openai.base-url=https://api.openai.com\n");
            if (includeEmbeddingOpenai) {
                writer.write("# spring.ai.openai.embedding.options.model=text-embedding-3-large\n");
            }
            if (includeLlmOpenai) {
                writer.write("# spring.ai.openai.chat.options.model=gpt-4o\n");
                writer.write("# spring.ai.openai.chat.options.temperature=0.7\n");
            }
            writer.write("\n");
        }

        if (includeEmbeddingPostgresml || includeVectorstorePgvector || includePgmlIndexer) {
            writer.write("# Database Configuration (set via environment variables or external config)\n");
            writer.write("# spring.datasource.url=jdbc:postgresql://localhost:5432/your_database\n");
            writer.write("# spring.datasource.username=${DB_USERNAME}\n");
            writer.write("# spring.datasource.password=${DB_PASSWORD}\n");
            writer.write("# spring.datasource.driver-class-name=org.postgresql.Driver\n");
            writer.write("\n");
        }

        if (includeLlmAnthropic) {
            writer.write("# Anthropic Configuration (set via environment variables or external config)\n");
            writer.write("# spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}\n");
            writer.write("# spring.ai.anthropic.chat.options.model=claude-3-sonnet-20240229\n");
            writer.write("# spring.ai.anthropic.chat.options.temperature=0.7\n");
            writer.write("\n");
        }

        if (includeLlmGemini) {
            writer.write("# Google Vertex AI Configuration (set via environment variables or external config)\n");
            writer.write("# spring.ai.vertex.ai.project-id=${GOOGLE_CLOUD_PROJECT_ID}\n");
            writer.write("# spring.ai.vertex.ai.location=us-central1\n");
            writer.write("# spring.ai.vertex.ai.gemini.chat.options.model=gemini-1.5-flash-latest\n");
            writer.write("\n");
        }

        if (includeVectorstoreChroma) {
            writer.write("# Chroma Configuration (set via environment variables or external config)\n");
            writer.write("# spring.ai.vectorstore.chroma.client.host=localhost\n");
            writer.write("# spring.ai.vectorstore.chroma.client.port=8000\n");
            writer.write("\n");
        }

        writer.write("# Example: Create application-dev.properties, application-prod.properties, etc.\n");
        writer.write("# Or set environment variables: OPENAI_API_KEY, DB_USERNAME, DB_PASSWORD, etc.\n");
        writer.write("# Or use command line: --spring.ai.openai.api-key=your-key\n");
    }

    private void writeApplicationPropertiesHeader(FileWriter writer) throws IOException {
        writer.write("# Generated application.properties\n");
        writer.write("# Project: " + instanceArtifactId + "\n");
        writer.write("# Generated on: " + new java.util.Date() + "\n");
        writer.write("# Configured providers: " + getProviderSummary() + "\n");
        writer.write("\n");

        if (includeEmbeddingPostgresml || includePgmlIndexer) {
            writer.write("# =============================================================================\n");
            writer.write("# AUTOMATIC PostgresML ERROR DEBUGGING\n");
            writer.write("# These settings will automatically show PostgresML debug info when errors occur\n");
            writer.write("# =============================================================================\n");
            writer.write("\n");
            writer.write("logging.level.org.springframework.jdbc.core.JdbcTemplate=DEBUG\n");
            writer.write("logging.level.org.springframework.jdbc.core.StatementCreatorUtils=TRACE\n");
            writer.write("logging.level.org.springframework.ai.postgresml=DEBUG\n");
            writer.write("logging.level.org.postgresql=DEBUG\n");
            writer.write("logging.level.ai.kompile.app.pgml.indexer=DEBUG\n");
            writer.write("logging.level.ai.kompile.vectorstore=DEBUG\n");
            writer.write("\n");
            writer.write("logging.level.org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator=DEBUG\n");
            writer.write("logging.level.org.springframework.dao=DEBUG\n");
            writer.write("\n");
            writer.write("logging.level." + instanceGroupId + ".config=DEBUG\n");
            writer.write("\n");
        }
    }

    public String getProviderSummary() {
        List<String> providers = new ArrayList<>();
        if (includeEmbeddingOpenai)
            providers.add("OpenAI Embedding");
        if (includeEmbeddingPostgresml)
            providers.add("PostgresML Embedding");
        if (includeEmbeddingSentenceTransformer)
            providers.add("Sentence Transformer");
        if (includeLlmOpenai)
            providers.add("OpenAI Chat");
        if (includeLlmAnthropic)
            providers.add("Anthropic Chat");
        if (includeLlmGemini)
            providers.add("Gemini Chat");
        if (includeVectorstorePgvector)
            providers.add("PgVector Store");
        if (includeVectorstoreChroma)
            providers.add("Chroma Store");
        return String.join(", ", providers);
    }
}
