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

package ai.kompile.cli.main.build.generators;

import ai.kompile.cli.main.build.config.BuildConfiguration;
import ai.kompile.cli.main.build.config.ModuleSelection;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelConstants;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates application.properties for a kompile application.
 * Extracted from RagPomGenerator's property generation logic.
 */
public class ApplicationPropertiesGenerator {

    private final BuildConfiguration config;
    private final KompileModelManager modelManager;
    private final Map<String, Path> resolvedModelPaths;

    public ApplicationPropertiesGenerator(BuildConfiguration config, KompileModelManager modelManager,
                                           Map<String, Path> resolvedModelPaths) {
        this.config = config;
        this.modelManager = modelManager;
        this.resolvedModelPaths = resolvedModelPaths;
    }

    /**
     * Generate application.properties file in the project's src/main/resources directory.
     */
    public void generate(File projectDir) throws IOException {
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            throw new IOException("Could not create resources directory: " + resourcesDir.getAbsolutePath());
        }
        File appPropsFile = new File(resourcesDir, "application.properties");
        ModuleSelection modules = config.getModules();

        try (FileWriter writer = new FileWriter(appPropsFile)) {
            writeHeader(writer);

            if (modules.has("vectorstore-pgvector")) {
                writeDatabaseConfiguration(writer);
                writeSchemaManagementConfiguration(writer);
            }

            writeAutoConfigurationExclusions(writer);
            writeProviderEnablementFlags(writer);
            writeStructuralConfiguration(writer);
            writeModelCacheConfiguration(writer);
            writeConfigurationTemplate(writer);
        }
        System.out.println("Generated application.properties: " + appPropsFile.getAbsolutePath());
    }

    private void writeHeader(FileWriter writer) throws IOException {
        writer.write("# Generated application.properties\n");
        writer.write("# Project: " + config.getConfigName() + "\n");
        writer.write("# Generated on: " + new java.util.Date() + "\n");
        writer.write("# Configured providers: " + getProviderSummary() + "\n\n");

        writer.write("# Logging\n");
        writer.write("logging.level.ai.kompile.cli.main.models=INFO\n");
        writer.write("logging.level.ai.kompile.app=INFO\n");
        writer.write("logging.level.io.anserini=INFO\n\n");
    }

    private void writeDatabaseConfiguration(FileWriter writer) throws IOException {
        writer.write("# =============================================================================\n");
        writer.write("# DATABASE CONFIGURATION\n");
        writer.write("# =============================================================================\n");
        writer.write("spring.datasource.url=" + config.getDatabaseUrl() + "\n");
        writer.write("spring.datasource.username=" + config.getDatabaseUsername() + "\n");
        writer.write("spring.datasource.password=" + config.getDatabasePassword() + "\n");
        writer.write("spring.datasource.driver-class-name=org.postgresql.Driver\n\n");

        writer.write("# Connection Pool\n");
        writer.write("spring.datasource.type=com.zaxxer.hikari.HikariDataSource\n");
        writer.write("spring.datasource.hikari.maximum-pool-size=10\n");
        writer.write("spring.datasource.hikari.minimum-idle=2\n");
        writer.write("spring.datasource.hikari.connection-timeout=20000\n");
        writer.write("spring.datasource.hikari.idle-timeout=300000\n");
        writer.write("spring.datasource.hikari.max-lifetime=1800000\n");
        writer.write("spring.datasource.hikari.leak-detection-threshold=60000\n\n");
    }

    private void writeSchemaManagementConfiguration(FileWriter writer) throws IOException {
        writer.write("# =============================================================================\n");
        writer.write("# SCHEMA MANAGEMENT\n");
        writer.write("# =============================================================================\n");

        if (config.isEnableSchemaInit()) {
            writer.write("spring.sql.init.enabled=true\n");
            writer.write("spring.sql.init.mode=always\n");
            writer.write("spring.sql.init.schema-locations=classpath:schema.sql\n");
            writer.write("spring.sql.init.data-locations=classpath:data.sql\n");
            writer.write("spring.sql.init.continue-on-error=true\n");
            writer.write("spring.sql.init.separator=;\n");
            writer.write("spring.sql.init.encoding=UTF-8\n");
        }

        writer.write("\nspring.jpa.hibernate.ddl-auto=none\n");
        writer.write("spring.jpa.generate-ddl=false\n\n");
    }

    private void writeAutoConfigurationExclusions(FileWriter writer) throws IOException {
        writer.write("# Hibernate: disable ByteBuddy bytecode provider (kompile-app excludes it for native-image compat)\n");
        writer.write("spring.jpa.properties.hibernate.bytecode.provider=none\n\n");
        writer.write("# Auto-configuration exclusions: prevent ambiguous bean errors\n");
        writer.write("spring.autoconfigure.exclude=\\\n");
        writer.write("    org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,\\\n");
        writer.write("    org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration\n\n");
    }

    private void writeProviderEnablementFlags(FileWriter writer) throws IOException {
        ModuleSelection modules = config.getModules();

        writer.write("# =============================================================================\n");
        writer.write("# PROVIDER ENABLEMENT FLAGS\n");
        writer.write("# =============================================================================\n");

        writer.write("# Embedding Providers\n");
        writer.write("spring.ai.openai.embedding.enabled=" + modules.has("embedding-openai") + "\n");
        writer.write("spring.ai.transformers.embedding.enabled=" + modules.has("embedding-sentence-transformer") + "\n\n");

        writer.write("# Chat Providers\n");
        writer.write("spring.ai.openai.chat.enabled=" + modules.has("llm-openai") + "\n");
        writer.write("spring.ai.anthropic.chat.enabled=" + modules.has("llm-anthropic") + "\n");
        writer.write("spring.ai.vertex.ai.gemini.chat.enabled=" + modules.has("llm-gemini") + "\n\n");

        writer.write("# Vector Stores\n");
        writer.write("spring.ai.vectorstore.pgvector.enabled=" + modules.has("vectorstore-pgvector") + "\n");
        writer.write("spring.ai.vectorstore.chroma.enabled=" + modules.has("vectorstore-chroma") + "\n\n");
    }

    private void writeStructuralConfiguration(FileWriter writer) throws IOException {
        ModuleSelection modules = config.getModules();

        writer.write("# =============================================================================\n");
        writer.write("# STRUCTURAL CONFIGURATION\n");
        writer.write("# =============================================================================\n");
        writer.write("spring.application.name=" + config.getConfigName() + "\n");
        writer.write("server.port=8080\n");
        writer.write("kompile.app.title=" + config.getAppTitle() + "\n\n");

        String runtimeCachePath = "${KOMPILE_MODEL_CACHE_DIR:" +
                modelManager.getBaseCachePath().toAbsolutePath().toString().replace("\\", "\\\\") + "}";
        writer.write("kompile.runtime.model.cache.path=" + runtimeCachePath + "\n\n");

        if (modules.has("chunker-sentence")) {
            writer.write("# OpenNLP Configuration\n");
            String defaultLang = config.getSupportedLanguages().isEmpty() ? "en" :
                    config.getSupportedLanguages().get(0).toLowerCase();
            writer.write("kompile.opennlp.models.basepath=${kompile.runtime.model.cache.path}/opennlp\n");
            writer.write("kompile.opennlp.sentence.language=" + defaultLang + "\n\n");
        }

        if (modules.has("app-anserini")) {
            writer.write("# Anserini Configuration\n");
            writer.write("kompile.anserini.models.basepath=${kompile.runtime.model.cache.path}/anserini\n");

            // Wire kompile-app to the Anserini-backed embedding + vector store. These two
            // properties are required for the embedding/vectorstore @ConditionalOnProperty
            // beans to register; without them the app boots but ingest / query no-op.
            writer.write("kompile.embedding.type=anserini\n");
            writer.write("kompile.vectorstore.type=anserini\n");
            writer.write("kompile.embedding.anserini.enabled=true\n");

            // Default to the model id that lives in the demo staging registry. The hard
            // default in AnseriniEmbeddingProperties is "bge-base-en-v1.5-onnx", which the
            // demo staging server does not have; override here so the generated app talks
            // to the staging registry out of the box.
            writer.write("kompile.embedding.anserini.model-identifier=bge-base-en-v1.5\n");

            // Point the generated app at the local staging server by default. Override at
            // runtime with --kompile.staging.url=... or an environment variable if needed.
            writer.write("kompile.staging.url=${KOMPILE_STAGING_URL:http://localhost:8090}\n");

            if (config.getAnseriniIndexIds() != null && !config.getAnseriniIndexIds().isEmpty()) {
                for (String indexId : config.getAnseriniIndexIds()) {
                    String trimmed = indexId.trim();
                    writer.write("anserini.indexPath." + trimmed + "=${kompile.anserini.models.basepath}/indexes/" + trimmed + "\n");
                }
            } else {
                writer.write("anserini.indexPath=${kompile.anserini.models.basepath}/indexes/keyword_index\n");
                writer.write("kompile.vectorstore.anserini.index-path=${kompile.anserini.models.basepath}/indexes/vector_index\n");
                writer.write("anserini.corpusPath=${kompile.anserini.models.basepath}/corpus/default_corpus\n");
            }

            if (config.getAnseriniEncoderModelIds() != null && !config.getAnseriniEncoderModelIds().isEmpty()) {
                writer.write("\n# Anserini Encoder Model Paths\n");
                for (String encoderModelId : config.getAnseriniEncoderModelIds()) {
                    String trimmed = encoderModelId.trim();
                    var desc = ModelConstants.getAnseriniEncoderModelDescriptor(trimmed);
                    if (desc != null && resolvedModelPaths.containsKey(desc.getModelId())) {
                        String path = "${kompile.runtime.model.cache.path}/" +
                                desc.getExpectedCacheSubpath().replace("\\", "/");
                        writer.write("anserini.encoder." + trimmed + ".model.path=" + path + "\n");
                    }
                }
            }
            writer.write("\n");
        }

        writer.write("# Chunker (recursive splitter is a sensible default for markdown / mixed text).\n");
        writer.write("kompile.chunker.type=recursive\n");
        writer.write("kompile.chunker.chunkSize=400\n");
        writer.write("kompile.chunker.chunkOverlap=40\n\n");

        writer.write("# Application Paths\n");
        writer.write("app.document.sources=./data/input_documents/sample.txt,./data/input_documents/sample.pdf\n");
        writer.write("app.document.uploads-path=./data/input_documents/uploads\n");
        writer.write("mcp.filesystem.roots.default.path=./data/shared_files\n");
        writer.write("mcp.filesystem.roots.default.alias=default\n\n");

        writer.write("# Ingest state directory (persists crawl/ingest state across restarts)\n");
        writer.write("kompile.ingest.state-directory=./data/ingest-state\n\n");

        if (modules.has("vectorstore-pgvector")) {
            writer.write("# PgVector Configuration\n");
            writer.write("spring.ai.vectorstore.pgvector.table-name=vector_store\n");
            writer.write("spring.ai.vectorstore.pgvector.schema-name=public\n");
            writer.write("spring.ai.vectorstore.pgvector.dimensions=1536\n");
            writer.write("spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE\n");
            writer.write("spring.ai.vectorstore.pgvector.initialize-schema=true\n");
            writer.write("spring.ai.vectorstore.pgvector.schema-validation=true\n\n");
        }

        if (modules.has("vectorstore-chroma")) {
            writer.write("# Chroma Configuration\n");
            writer.write("spring.ai.vectorstore.chroma.collection-name=kompile_documents\n");
            writer.write("spring.ai.vectorstore.chroma.initialize-schema=true\n\n");
        }
    }

    private void writeModelCacheConfiguration(FileWriter writer) throws IOException {
        writer.write("# --- Runtime Model Cache Configuration ---\n");
        String defaultCachePath = Paths.get(System.getProperty("user.home"), ModelConstants.DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR)
                .toAbsolutePath().toString().replace("\\", "\\\\");
        writer.write("# Default cache path: " + defaultCachePath + "\n");
        writer.write("kompile.model.cache.path=${" + ModelConstants.ENV_KOMPILE_MODEL_CACHE_DIR + ":" +
                modelManager.getBaseCachePath().toAbsolutePath().toString().replace("\\", "\\\\") + "}\n\n");
    }

    private void writeConfigurationTemplate(FileWriter writer) throws IOException {
        ModuleSelection modules = config.getModules();

        writer.write("# =============================================================================\n");
        writer.write("# RUNTIME CONFIGURATION TEMPLATE\n");
        writer.write("# =============================================================================\n\n");

        if (modules.has("embedding-openai") || modules.has("llm-openai")) {
            writer.write("# OpenAI Configuration\n");
            writer.write("# spring.ai.openai.api-key=${OPENAI_API_KEY}\n");
            writer.write("# spring.ai.openai.base-url=https://api.openai.com\n");
            if (modules.has("embedding-openai")) {
                writer.write("# spring.ai.openai.embedding.options.model=text-embedding-3-large\n");
            }
            if (modules.has("llm-openai")) {
                writer.write("# spring.ai.openai.chat.options.model=gpt-4o\n");
                writer.write("# spring.ai.openai.chat.options.temperature=0.7\n");
            }
            writer.write("\n");
        }

        if (modules.has("llm-anthropic")) {
            writer.write("# Anthropic Configuration\n");
            writer.write("# spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}\n");
            writer.write("# spring.ai.anthropic.chat.options.model=claude-3-sonnet-20240229\n\n");
        }

        if (modules.has("llm-gemini")) {
            writer.write("# Google Vertex AI Configuration\n");
            writer.write("# spring.ai.vertex.ai.project-id=${GOOGLE_CLOUD_PROJECT_ID}\n");
            writer.write("# spring.ai.vertex.ai.location=us-central1\n");
            writer.write("# spring.ai.vertex.ai.gemini.chat.options.model=gemini-1.5-flash-latest\n\n");
        }

        if (modules.has("vectorstore-chroma")) {
            writer.write("# Chroma Configuration\n");
            writer.write("# spring.ai.vectorstore.chroma.client.host=localhost\n");
            writer.write("# spring.ai.vectorstore.chroma.client.port=8000\n\n");
        }

        if (modules.has("vectorstore-pgvector")) {
            writer.write("# Database Configuration\n");
            writer.write("# spring.datasource.url=jdbc:postgresql://localhost:5432/your_database\n");
            writer.write("# spring.datasource.username=${DB_USERNAME}\n");
            writer.write("# spring.datasource.password=${DB_PASSWORD}\n\n");
        }
    }

    private String getProviderSummary() {
        ModuleSelection modules = config.getModules();
        List<String> providers = new ArrayList<>();
        if (modules.has("embedding-openai")) providers.add("OpenAI Embedding");
        if (modules.has("embedding-anserini")) providers.add("Anserini Embedding");
        if (modules.has("embedding-sentence-transformer")) providers.add("Sentence Transformer");
        if (modules.has("llm-openai")) providers.add("OpenAI Chat");
        if (modules.has("llm-anthropic")) providers.add("Anthropic Chat");
        if (modules.has("llm-gemini")) providers.add("Gemini Chat");
        if (modules.has("vectorstore-pgvector")) providers.add("PgVector Store");
        if (modules.has("vectorstore-chroma")) providers.add("Chroma Store");
        if (modules.has("vectorstore-anserini")) providers.add("Anserini Store");
        return String.join(", ", providers);
    }
}
