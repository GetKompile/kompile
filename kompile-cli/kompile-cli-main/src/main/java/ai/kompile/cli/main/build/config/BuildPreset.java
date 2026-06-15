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

/**
 * Predefined module sets for common application types.
 */
public enum BuildPreset {

    /**
     * CLI Agent RAG: local CLI agent (Claude Code, Codex, etc.) + Anserini embeddings + vectorstore.
     */
    CLI_AGENT_RAG("CLI Agent RAG application (local, no API keys)",
            "app-main", "app-core", "loaders-orchestrator", "app-anserini", "chat-history", "pipelines-llm",
            "llm-cli-agent",
            "embedding-anserini",
            "vectorstore-anserini",
            "loader-pdf-extended",
            "chunker-sentence",
            "tool-filesystem", "tool-rag",
            "kvcache", "model-manager"
    ),

    /**
     * Hosted LLM RAG: OpenAI LLM + Anserini embeddings/vectorstore + PDF loader + sentence chunker.
     */
    HOSTED_LLM_RAG("Hosted LLM RAG application",
            "app-main", "app-core", "loaders-orchestrator", "app-anserini", "chat-history", "pipelines-llm",
            "llm-openai",
            "embedding-anserini",
            "vectorstore-anserini",
            "loader-pdf-extended",
            "chunker-sentence",
            "tool-filesystem", "tool-rag",
            "kvcache", "model-manager"
    ),

    /**
     * SameDiff RAG: No LLM + Anserini embeddings/vectorstore + PDF loader + sentence chunker.
     */
    SAMEDIFF_RAG("SameDiff RAG application (no hosted LLM)",
            "app-main", "app-core", "loaders-orchestrator", "app-anserini", "chat-history", "pipelines-llm",
            "embedding-anserini",
            "vectorstore-anserini",
            "loader-pdf-extended",
            "loader-tika",
            "chunker-sentence",
            "tool-filesystem", "tool-rag",
            "kvcache", "model-manager"
    ),

    /**
     * Pipeline executor mode (no web app modules).
     */
    PIPELINE("Pipeline executor",
            "pipelines-core", "pipelines-runtime"
    ),

    /**
     * Full: All non-deprecated modules enabled.
     */
    FULL("All modules enabled",
            "app-main", "app-core", "loaders-orchestrator", "app-anserini", "chat-history", "pipelines-llm",
            "llm-openai", "llm-anthropic", "llm-gemini", "cli-llm",
            "embedding-openai", "embedding-anserini", "embedding-sentence-transformer",
            "vectorstore-anserini", "vectorstore-chroma", "vectorstore-pgvector",
            "loader-pdf-extended", "loader-pdf-tables", "loader-microsoft", "loader-mail", "loader-excel", "loader-tika",
            "chunker-sentence", "chunker-recursive-character", "chunker-markdown", "chunker-token",
            "tool-filesystem", "tool-rag", "tool-crawler",
            "ocr-core", "ocr-models", "ocr-postprocess", "ocr-integration", "ocr-datapipeline",
            "crawler-core", "crawl-graph", "process-discovery", "code-indexer",
            "kvcache", "model-staging", "model-manager", "pipeline-management",
            "graph-neo4j", "knowledge-graph", "graph-algorithms"
    ),

    /**
     * Minimal: OpenAI LLM + OpenAI embeddings + Anserini vectorstore + RAG tool only.
     */
    MINIMAL("Minimal RAG application",
            "app-main", "app-core", "loaders-orchestrator", "app-anserini", "chat-history", "pipelines-llm",
            "llm-openai",
            "embedding-openai",
            "vectorstore-anserini",
            "tool-rag", "model-manager"
    ),

    /**
     * Lite: Self-contained chat + RAG + Graph RAG with minimal UI. No MCP, no pipelines.
     */
    LITE("Kompile Lite - self-contained chat + RAG + Graph RAG",
            "app-lite", "app-core", "app-anserini", "chat-history",
            "embedding-anserini",
            "vectorstore-anserini",
            "loader-pdf-extended",
            "chunker-sentence",
            "tool-rag", "knowledge-graph", "model-manager", "model-staging"
    );

    /**
     * Backend affinity hint for code generation and build configuration.
     */
    public enum BackendAffinity {
        /** Prefer CPU (nd4j-native) — works everywhere. */
        CPU_ONLY,
        /** Prefer CUDA when available, fall back to CPU. */
        CUDA_PREFERRED,
        /** Any backend is acceptable. */
        ANY
    }

    private final String description;
    private final Set<String> defaultModuleIds;

    BuildPreset(String description, String... moduleIds) {
        this.description = description;
        this.defaultModuleIds = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(moduleIds)));
    }

    public String getDescription() {
        return description;
    }

    /**
     * Get the default set of module IDs for this preset.
     */
    public Set<String> getDefaultModules() {
        return defaultModuleIds;
    }

    /**
     * Returns the recommended backend affinity for this preset.
     */
    public BackendAffinity getBackendAffinity() {
        switch (this) {
            case PIPELINE:
                return BackendAffinity.CUDA_PREFERRED;
            case CLI_AGENT_RAG:
            case SAMEDIFF_RAG:
            case LITE:
                return BackendAffinity.CPU_ONLY;
            default:
                return BackendAffinity.ANY;
        }
    }
}
