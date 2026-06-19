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

package ai.kompile.cli.main.build.config;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Single source of truth for all kompile-app modules.
 * Each module has an id, Maven coordinates, category, and description.
 */
public final class ModuleCatalog {

    public enum Category {
        CORE,
        LLM,
        EMBEDDING,
        VECTORSTORE,
        LOADER,
        CHUNKER,
        TOOL,
        ENTERPRISE,
        ADVANCED,
        OCR,
        QUERY,
        PIPELINE
    }

    public static final class ModuleEntry {
        private final String id;
        private final String groupId;
        private final String artifactId;
        private final Category category;
        private final String description;
        private final boolean bundled;
        private final String displayName;

        public ModuleEntry(String id, String groupId, String artifactId, Category category, String description) {
            this.id = id;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.category = category;
            this.description = description;
            this.bundled = false;
            this.displayName = toDisplayName(id);
        }

        public ModuleEntry(String id, String groupId, String artifactId, Category category,
                           String description, boolean bundled, String displayName) {
            this.id = id;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.category = category;
            this.description = description;
            this.bundled = bundled;
            this.displayName = displayName != null ? displayName : toDisplayName(id);
        }

        public String getId() { return id; }
        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public Category getCategory() { return category; }
        public String getDescription() { return description; }

        /**
         * Returns true if this module is bundled with the full application (app-main)
         * and is always included rather than optional.
         */
        public boolean isBundled() { return bundled; }

        /**
         * Returns a human-readable display name for this module.
         */
        public String getDisplayName() { return displayName; }

        private static String toDisplayName(String id) {
            if (id == null || id.isEmpty()) return id;
            String[] parts = id.split("-");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (!part.isEmpty()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(Character.toUpperCase(part.charAt(0)));
                    if (part.length() > 1) sb.append(part.substring(1));
                }
            }
            return sb.toString();
        }
    }

    private static final Map<String, ModuleEntry> MODULES = new LinkedHashMap<>();

    static {
        // Core modules
        register("app-main", "ai.kompile", "kompile-app-main", Category.CORE, "Main application with UI and web layer");
        register("app-lite", "ai.kompile", "kompile-app-lite", Category.CORE, "Lightweight self-contained chat + RAG + Graph RAG application");
        register("app-core", "ai.kompile", "kompile-app-core", Category.CORE, "Core interfaces and abstractions");
        register("loaders-orchestrator", "ai.kompile", "kompile-app-loaders-orchestrator", Category.CORE, "Document loader orchestration");
        register("app-anserini", "ai.kompile", "kompile-app-anserini", Category.CORE, "Anserini search integration");
        register("chat-history", "ai.kompile", "kompile-chat-history", Category.CORE, "Chat history persistence");
        register("pipelines-llm", "ai.kompile", "kompile-app-llm-pipeline", Category.CORE, "Pipeline LLM auto-configuration");

        // LLM providers
        register("llm-openai", "ai.kompile", "kompile-app-openai-llm", Category.LLM, "OpenAI LLM provider");
        register("llm-anthropic", "ai.kompile", "kompile-app-anthropic-llm", Category.LLM, "Anthropic LLM provider");
        register("llm-gemini", "ai.kompile", "kompile-app-gemini-llm", Category.LLM, "Google Gemini LLM provider");
        register("llm-cli-agent", "ai.kompile", "kompile-app-cli-llm", Category.LLM, "Local CLI agent LLM (no API key required)");

        // Embedding providers
        register("embedding-openai", "ai.kompile", "kompile-embedding-openai", Category.EMBEDDING, "OpenAI embeddings");
        register("embedding-anserini", "ai.kompile", "kompile-embedding-anserini", Category.EMBEDDING, "SameDiff-based embeddings (BGE, Arctic Embed)");
        register("embedding-sentence-transformer", "ai.kompile", "kompile-embedding-sentence-transformer", Category.EMBEDDING, "Sentence Transformer embeddings");

        // Vector stores
        register("vectorstore-anserini", "ai.kompile", "kompile-vectorstore-anserini", Category.VECTORSTORE, "Lucene HNSW vector store");
        register("vectorstore-chroma", "ai.kompile", "kompile-vectorstore-chroma", Category.VECTORSTORE, "Chroma vector store client");
        register("vectorstore-pgvector", "ai.kompile", "kompile-vectorstore-pgvector", Category.VECTORSTORE, "PostgreSQL pgvector store");

        // Document loaders
        register("loader-pdf-extended", "ai.kompile", "kompile-loader-pdf-extended", Category.LOADER, "Advanced PDF processing");
        register("loader-microsoft", "ai.kompile", "kompile-loader-microsoft", Category.LOADER, "Microsoft Office documents");
        register("loader-mail", "ai.kompile", "kompile-loader-mail", Category.LOADER, "Email/mail parsing");
        register("loader-tika", "ai.kompile", "kompile-loader-tika", Category.LOADER, "Apache Tika multi-format loader (text, markdown, html, etc.)");

        // Chunkers
        register("chunker-sentence", "ai.kompile", "kompile-chunker-sentence", Category.CHUNKER, "OpenNLP sentence chunker");
        register("chunker-recursive-character", "ai.kompile", "kompile-chunker-recursivecharacter", Category.CHUNKER, "Recursive character text splitter");
        register("chunker-markdown", "ai.kompile", "kompile-chunker-markdown", Category.CHUNKER, "Markdown-aware chunker");
        register("chunker-token", "ai.kompile", "kompile-chunker-token", Category.CHUNKER, "Token-based chunker");

        // Tools
        register("tool-filesystem", "ai.kompile", "kompile-tool-filesystem", Category.TOOL, "File system MCP tool");
        register("tool-rag", "ai.kompile", "kompile-tool-rag", Category.TOOL, "RAG query tool");
        register("tool-model-staging", "ai.kompile", "kompile-tool-model-staging", Category.TOOL, "Model staging operations (separate process)");

        // Document loaders (extended)
        register("loader-pdf-tables", "ai.kompile", "kompile-loader-pdf-tables", Category.LOADER, "PDF table extraction");
        register("loader-excel", "ai.kompile", "kompile-loader-excel", Category.LOADER, "Excel spreadsheet loader");

        // OCR and VLM
        register("ocr-core", "ai.kompile", "kompile-ocr-core", Category.OCR, "OCR pipeline interfaces and config");
        register("ocr-models", "ai.kompile", "kompile-ocr-models", Category.OCR, "OCR and VLM model implementations (SameDiff)");
        register("ocr-postprocess", "ai.kompile", "kompile-ocr-postprocess", Category.OCR, "LLM-based OCR post-processing");
        register("ocr-integration", "ai.kompile", "kompile-ocr-integration", Category.OCR, "OCR pipeline service and document loader bridge");
        register("ocr-datapipeline", "ai.kompile", "kompile-ocr-datapipeline", Category.OCR, "OCR output parsing and entity indexing");

        // Crawl and graph
        register("crawler-core", "ai.kompile", "kompile-crawler-core", Category.ADVANCED, "Web/file/HTML/Excel crawlers and pipeline routing");
        register("crawl-graph", "ai.kompile", "kompile-crawl-graph", Category.ADVANCED, "Unified crawl-to-graph extraction service");
        register("tool-crawler", "ai.kompile", "kompile-tool-crawler", Category.TOOL, "Crawler MCP tools (start/status/cancel)");
        register("process-discovery", "ai.kompile", "kompile-process-discovery", Category.ADVANCED, "LLM-based business process discovery from graphs");
        register("cli-llm", "ai.kompile", "kompile-app-cli-llm", Category.LLM, "CLI agent LLM provider (subprocess-managed Claude/Codex)");

        // Data enrichment
        register("data-enrichment", "ai.kompile", "kompile-data-enrichment", Category.TOOL, "LLM-based data enrichment and labeling");

        // Enterprise / Advanced
        register("kvcache", "ai.kompile", "kompile-kvcache", Category.ENTERPRISE, "KV cache management");
        register("model-staging", "ai.kompile", "kompile-model-staging", Category.ENTERPRISE, "Model staging and registry");
        register("model-manager", "ai.kompile", "kompile-model-manager", Category.ENTERPRISE, "Model download and caching");
        register("pipeline-management", "ai.kompile", "kompile-pipeline-management", Category.ENTERPRISE, "Pipeline management service");
        register("code-indexer", "ai.kompile", "kompile-code-indexer", Category.ENTERPRISE, "Code project indexing and search");
        register("graph-neo4j", "ai.kompile", "kompile-graph-neo4j", Category.ADVANCED, "Graph RAG with Neo4j");
        register("knowledge-graph", "ai.kompile", "kompile-knowledge-graph", Category.ADVANCED, "Knowledge graph integration");
        register("graph-algorithms", "ai.kompile", "kompile-graph-algorithms", Category.ADVANCED, "Graph algorithms (PageRank, communities, shortest path)");

        // Pipeline
        register("pipelines-core", "ai.kompile", "kompile-pipelines-framework-core", Category.PIPELINE, "Pipeline execution engine");
        register("pipelines-runtime", "ai.kompile", "kompile-pipelines-framework-runtime", Category.PIPELINE, "Pipeline runtime support");
        register("pipeline-step-python", "ai.kompile", "kompile-pipelines-steps-python", Category.PIPELINE, "Python pipeline step");
        register("pipeline-step-samediff", "ai.kompile", "kompile-pipelines-steps-samediff", Category.PIPELINE, "SameDiff pipeline step");
        register("pipeline-step-onnx", "ai.kompile", "kompile-pipelines-steps-onnx", Category.PIPELINE, "ONNX pipeline step");
        register("pipeline-step-dl4j", "ai.kompile", "kompile-pipelines-steps-dl4j", Category.PIPELINE, "DL4J pipeline step");
    }

    private static void register(String id, String groupId, String artifactId, Category category, String description) {
        MODULES.put(id, new ModuleEntry(id, groupId, artifactId, category, description));
    }

    /**
     * Get a module by its ID.
     */
    public static ModuleEntry get(String id) {
        return MODULES.get(id);
    }

    /**
     * Get all modules in a given category.
     */
    public static List<ModuleEntry> getByCategory(Category category) {
        return MODULES.values().stream()
                .filter(m -> m.getCategory() == category)
                .collect(Collectors.toList());
    }

    /**
     * Get all registered modules.
     */
    public static Collection<ModuleEntry> getAll() {
        return Collections.unmodifiableCollection(MODULES.values());
    }

    /**
     * Resolve a set of module IDs to their corresponding ModuleEntry objects.
     * Throws IllegalArgumentException if any ID is unknown.
     */
    public static List<ModuleEntry> resolve(Set<String> moduleIds) {
        List<ModuleEntry> result = new ArrayList<>();
        for (String id : moduleIds) {
            ModuleEntry entry = MODULES.get(id);
            if (entry == null) {
                throw new IllegalArgumentException("Unknown module ID: " + id + ". Available: " + MODULES.keySet());
            }
            result.add(entry);
        }
        return result;
    }

    /**
     * Resolve a set of module IDs to their artifact IDs.
     */
    public static Set<String> resolveArtifactIds(Set<String> moduleIds) {
        return moduleIds.stream()
                .map(id -> {
                    ModuleEntry entry = MODULES.get(id);
                    if (entry == null) {
                        throw new IllegalArgumentException("Unknown module ID: " + id);
                    }
                    return entry.getArtifactId();
                })
                .collect(Collectors.toSet());
    }

    /**
     * Get all known module IDs.
     */
    public static Set<String> allIds() {
        return Collections.unmodifiableSet(MODULES.keySet());
    }

    private ModuleCatalog() {}
}
