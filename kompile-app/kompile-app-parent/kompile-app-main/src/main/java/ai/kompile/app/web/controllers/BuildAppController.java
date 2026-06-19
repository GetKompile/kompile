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

package ai.kompile.app.web.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller exposing the web equivalent of the {@code kompile build app} CLI command.
 *
 * <ul>
 *   <li>{@code GET  /api/build/modules}          — full module catalog organized by category</li>
 *   <li>{@code GET  /api/build/presets}           — available build presets with their module sets</li>
 *   <li>{@code POST /api/build/app}               — submit a build request (queued; actual execution TBD)</li>
 *   <li>{@code GET  /api/build/status/{buildId}}  — poll build status by ID</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/build")
public class BuildAppController {

    private static final Logger logger = LoggerFactory.getLogger(BuildAppController.class);

    // =========================================================================
    // Static catalog — mirrors ModuleCatalog.java from the CLI
    // =========================================================================

    /** Immutable list of all module entries, preserving insertion order. */
    private static final List<Map<String, String>> ALL_MODULES;

    /** Catalog grouped by category name, preserving insertion order within each group. */
    private static final Map<String, List<Map<String, String>>> MODULES_BY_CATEGORY;

    /** Preset definitions, keyed by preset name. */
    private static final List<Map<String, Object>> PRESETS;

    static {
        // -----------------------------------------------------------------
        // Build the flat module list
        // -----------------------------------------------------------------
        List<Map<String, String>> modules = new ArrayList<>();

        // CORE
        modules.add(module("app-main",           "kompile-app-main",                      "CORE",        "Main application with UI and web layer"));
        modules.add(module("app-lite",            "kompile-app-lite",                      "CORE",        "Lightweight self-contained chat + RAG + Graph RAG application"));
        modules.add(module("app-core",            "kompile-app-core",                      "CORE",        "Core interfaces and abstractions"));
        modules.add(module("loaders-orchestrator","kompile-app-loaders-orchestrator",      "CORE",        "Document loader orchestration"));
        modules.add(module("app-anserini",        "kompile-app-anserini",                  "CORE",        "Anserini search integration"));
        modules.add(module("chat-history",        "kompile-chat-history",                  "CORE",        "Chat history persistence"));
        modules.add(module("pipelines-llm",       "kompile-app-llm-pipeline",             "CORE",        "Pipeline LLM auto-configuration"));

        // LLM
        modules.add(module("llm-openai",          "kompile-app-openai-llm",               "LLM",         "OpenAI LLM provider"));
        modules.add(module("llm-anthropic",       "kompile-app-anthropic-llm",            "LLM",         "Anthropic LLM provider"));
        modules.add(module("llm-agy",             "kompile-app-agy-llm",                  "LLM",         "Antigravity LLM provider"));

        // EMBEDDING
        modules.add(module("embedding-openai",              "kompile-embedding-openai",               "EMBEDDING",   "OpenAI embeddings"));
        modules.add(module("embedding-anserini",            "kompile-embedding-anserini",             "EMBEDDING",   "SameDiff-based embeddings (BGE, Arctic Embed)"));
        modules.add(module("embedding-sentence-transformer","kompile-embedding-sentence-transformer",  "EMBEDDING",   "Sentence Transformer embeddings"));

        // VECTORSTORE
        modules.add(module("vectorstore-anserini","kompile-vectorstore-anserini",         "VECTORSTORE", "Lucene HNSW vector store"));
        modules.add(module("vectorstore-chroma",  "kompile-vectorstore-chroma",           "VECTORSTORE", "Chroma vector store client"));
        modules.add(module("vectorstore-pgvector","kompile-vectorstore-pgvector",         "VECTORSTORE", "PostgreSQL pgvector store"));

        // LOADER
        modules.add(module("loader-pdf-extended", "kompile-loader-pdf-extended",          "LOADER",      "Advanced PDF processing"));
        modules.add(module("loader-microsoft",    "kompile-loader-microsoft",             "LOADER",      "Microsoft Office documents"));
        modules.add(module("loader-mail",         "kompile-loader-mail",                  "LOADER",      "Email/mail parsing"));
        modules.add(module("loader-tika",         "kompile-loader-tika",                  "LOADER",      "Apache Tika multi-format loader (text, markdown, html, etc.)"));

        // CHUNKER
        modules.add(module("chunker-sentence",            "kompile-chunker-sentence",              "CHUNKER",     "OpenNLP sentence chunker"));
        modules.add(module("chunker-recursive-character", "kompile-chunker-recursivecharacter",    "CHUNKER",     "Recursive character text splitter"));
        modules.add(module("chunker-markdown",            "kompile-chunker-markdown",              "CHUNKER",     "Markdown-aware chunker"));
        modules.add(module("chunker-token",               "kompile-chunker-token",                 "CHUNKER",     "Token-based chunker"));

        // TOOL
        modules.add(module("tool-filesystem",    "kompile-tool-filesystem",              "TOOL",        "File system MCP tool"));
        modules.add(module("tool-rag",           "kompile-tool-rag",                     "TOOL",        "RAG query tool"));
        modules.add(module("tool-model-staging", "kompile-tool-model-staging",           "TOOL",        "Model staging operations"));

        // ENTERPRISE
        modules.add(module("kvcache",            "kompile-kvcache",                      "ENTERPRISE",  "KV cache management"));
        modules.add(module("model-staging",      "kompile-model-staging",                "ENTERPRISE",  "Model staging and registry"));
        modules.add(module("model-manager",      "kompile-model-manager",                "ENTERPRISE",  "Model download and caching"));
        modules.add(module("pipeline-management","kompile-pipeline-management",          "ENTERPRISE",  "Pipeline management service"));

        // ADVANCED
        modules.add(module("graph-neo4j",        "kompile-graph-neo4j",                  "ADVANCED",    "Graph RAG with Neo4j"));
        modules.add(module("knowledge-graph",    "kompile-knowledge-graph",              "ADVANCED",    "Knowledge graph integration"));

        // PIPELINE
        modules.add(module("pipelines-core",         "kompile-pipelines-framework-core",    "PIPELINE",    "Pipeline execution engine"));
        modules.add(module("pipelines-runtime",      "kompile-pipelines-framework-runtime", "PIPELINE",    "Pipeline runtime support"));
        modules.add(module("pipeline-step-python",   "kompile-pipelines-steps-python",      "PIPELINE",    "Python pipeline step"));
        modules.add(module("pipeline-step-samediff", "kompile-pipelines-steps-samediff",    "PIPELINE",    "SameDiff pipeline step"));
        modules.add(module("pipeline-step-onnx",     "kompile-pipelines-steps-onnx",        "PIPELINE",    "ONNX pipeline step"));
        modules.add(module("pipeline-step-dl4j",     "kompile-pipelines-steps-dl4j",        "PIPELINE",    "DL4J pipeline step"));

        ALL_MODULES = Collections.unmodifiableList(modules);

        // -----------------------------------------------------------------
        // Group by category, preserving insertion order within each group
        // -----------------------------------------------------------------
        Map<String, List<Map<String, String>>> byCategory = new LinkedHashMap<>();
        for (Map<String, String> m : ALL_MODULES) {
            byCategory.computeIfAbsent(m.get("category"), k -> new ArrayList<>()).add(m);
        }
        MODULES_BY_CATEGORY = Collections.unmodifiableMap(byCategory);

        // -----------------------------------------------------------------
        // Presets — mirror BuildPreset.java from the CLI
        // -----------------------------------------------------------------
        List<Map<String, Object>> presets = new ArrayList<>();

        presets.add(preset(
                "hosted-llm-rag",
                "Hosted LLM RAG application",
                "app-main", "app-core", "loaders-orchestrator", "app-anserini", "chat-history", "pipelines-llm",
                "llm-openai",
                "embedding-anserini",
                "vectorstore-anserini",
                "loader-pdf-extended",
                "chunker-sentence",
                "tool-filesystem", "tool-rag", "tool-model-staging",
                "kvcache", "model-manager"
        ));

        presets.add(preset(
                "samediff-rag",
                "SameDiff RAG application (no hosted LLM)",
                "app-main", "app-core", "loaders-orchestrator", "app-anserini", "chat-history", "pipelines-llm",
                "embedding-anserini",
                "vectorstore-anserini",
                "loader-pdf-extended",
                "loader-tika",
                "chunker-sentence",
                "tool-filesystem", "tool-rag", "tool-model-staging",
                "kvcache", "model-manager"
        ));

        presets.add(preset(
                "pipeline",
                "Pipeline executor",
                "pipelines-core", "pipelines-runtime"
        ));

        presets.add(preset(
                "full",
                "All modules enabled",
                "app-main", "app-core", "loaders-orchestrator", "app-anserini", "chat-history", "pipelines-llm",
                "llm-openai", "llm-anthropic", "llm-agy",
                "embedding-openai", "embedding-anserini", "embedding-sentence-transformer",
                "vectorstore-anserini", "vectorstore-chroma", "vectorstore-pgvector",
                "loader-pdf-extended", "loader-microsoft", "loader-mail", "loader-tika",
                "chunker-sentence", "chunker-recursive-character", "chunker-markdown", "chunker-token",
                "tool-filesystem", "tool-rag", "tool-model-staging",
                "kvcache", "model-staging", "model-manager", "pipeline-management",
                "graph-neo4j", "knowledge-graph"
        ));

        presets.add(preset(
                "minimal",
                "Minimal RAG application",
                "app-main", "app-core", "loaders-orchestrator", "app-anserini", "chat-history", "pipelines-llm",
                "llm-openai",
                "embedding-openai",
                "vectorstore-anserini",
                "tool-rag", "model-manager"
        ));

        presets.add(preset(
                "lite",
                "Kompile Lite - self-contained chat + RAG + Graph RAG",
                "app-lite", "app-core", "app-anserini", "chat-history",
                "embedding-anserini",
                "vectorstore-anserini",
                "loader-pdf-extended",
                "chunker-sentence",
                "tool-rag", "knowledge-graph", "model-manager", "model-staging"
        ));

        PRESETS = Collections.unmodifiableList(presets);
    }

    // No-arg constructor — no injected dependencies
    public BuildAppController() {
    }

    // =========================================================================
    // GET /api/build/modules
    // =========================================================================

    /**
     * Returns the full module catalog organized by category.
     * The response shape mirrors what the CLI's {@code ModuleCatalog} exposes.
     */
    @GetMapping("/modules")
    public ResponseEntity<Map<String, Object>> getModules() {
        try {
            List<Map<String, Object>> categories = MODULES_BY_CATEGORY.entrySet().stream()
                    .map(entry -> {
                        Map<String, Object> cat = new LinkedHashMap<>();
                        cat.put("name", entry.getKey());
                        // Strip the internal "category" key from the per-module maps
                        List<Map<String, String>> stripped = entry.getValue().stream()
                                .map(m -> {
                                    Map<String, String> slim = new LinkedHashMap<>();
                                    slim.put("id", m.get("id"));
                                    slim.put("artifactId", m.get("artifactId"));
                                    slim.put("description", m.get("description"));
                                    return slim;
                                })
                                .collect(Collectors.toList());
                        cat.put("modules", stripped);
                        return cat;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("categories", categories);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error building module catalog", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // =========================================================================
    // GET /api/build/presets
    // =========================================================================

    /**
     * Returns all available build presets with their default module sets.
     */
    @GetMapping("/presets")
    public ResponseEntity<List<Map<String, Object>>> getPresets() {
        return ResponseEntity.ok(PRESETS);
    }

    // =========================================================================
    // POST /api/build/app
    // =========================================================================

    /**
     * Accepts a build request, validates it, and returns an acknowledgement.
     * Actual Maven build execution will be wired in a future iteration.
     *
     * <p>Expected request body fields (all optional except {@code configName}):
     * <ul>
     *   <li>{@code configName}      — logical name / instance ID for the generated app</li>
     *   <li>{@code preset}          — preset name to use as the starting module set</li>
     *   <li>{@code modules}         — explicit list of module IDs (overrides preset if provided)</li>
     *   <li>{@code buildNative}     — whether to produce a GraalVM native image</li>
     *   <li>{@code skipTests}       — whether to skip tests during the Maven build</li>
     *   <li>{@code appTitle}        — display title for the generated application</li>
     *   <li>{@code backend}         — ND4J backend (e.g. {@code nd4j-cuda-12.9})</li>
     *   <li>{@code javacppPlatform} — target JavaCPP platform classifier</li>
     * </ul>
     */
    @PostMapping("/app")
    public ResponseEntity<Map<String, Object>> submitBuild(@RequestBody Map<String, Object> buildRequest) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            // --- Validate configName ---
            String configName = (String) buildRequest.get("configName");
            if (configName == null || configName.isBlank()) {
                response.put("status", "error");
                response.put("message", "configName is required");
                return ResponseEntity.badRequest().body(response);
            }

            // --- Resolve effective module list ---
            @SuppressWarnings("unchecked")
            List<String> requestedModules = (List<String>) buildRequest.get("modules");
            String presetName = (String) buildRequest.get("preset");

            List<String> effectiveModules;
            if (requestedModules != null && !requestedModules.isEmpty()) {
                effectiveModules = requestedModules;
            } else if (presetName != null && !presetName.isBlank()) {
                effectiveModules = resolvePresetModules(presetName);
                if (effectiveModules == null) {
                    response.put("status", "error");
                    response.put("message", "Unknown preset: " + presetName
                            + ". Available: " + PRESETS.stream()
                                    .map(p -> (String) p.get("name"))
                                    .collect(Collectors.joining(", ")));
                    return ResponseEntity.badRequest().body(response);
                }
            } else {
                response.put("status", "error");
                response.put("message", "Either 'preset' or 'modules' must be specified");
                return ResponseEntity.badRequest().body(response);
            }

            // --- Validate each module ID against the catalog ---
            Set<String> knownIds = ALL_MODULES.stream()
                    .map(m -> m.get("id"))
                    .collect(Collectors.toSet());
            List<String> unknownModules = effectiveModules.stream()
                    .filter(id -> !knownIds.contains(id))
                    .collect(Collectors.toList());
            if (!unknownModules.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Unknown module IDs: " + unknownModules);
                return ResponseEntity.badRequest().body(response);
            }

            // --- Assemble acknowledgement ---
            boolean buildNative = Boolean.TRUE.equals(buildRequest.get("buildNative"));
            String buildId = UUID.randomUUID().toString();

            response.put("buildId", buildId);
            response.put("configName", configName);
            response.put("status", "accepted");
            response.put("message", "Build request queued");
            response.put("moduleCount", effectiveModules.size());
            response.put("buildNative", buildNative);

            logger.info("Build request accepted: buildId={}, configName={}, modules={}, native={}",
                    buildId, configName, effectiveModules.size(), buildNative);

            return ResponseEntity.accepted().body(response);
        } catch (Exception e) {
            logger.error("Error processing build request", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // =========================================================================
    // GET /api/build/status/{buildId}
    // =========================================================================

    /**
     * Returns the status of a previously submitted build request.
     * The build execution engine is not yet implemented; this always returns {@code pending}.
     *
     * @param buildId the UUID returned by {@code POST /api/build/app}
     */
    @GetMapping("/status/{buildId}")
    public ResponseEntity<Map<String, Object>> getBuildStatus(@PathVariable String buildId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("buildId", buildId);
        response.put("status", "pending");
        response.put("message", "Build system not yet implemented");
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Creates a module descriptor map with {@code id}, {@code artifactId}, {@code category},
     * and {@code description} keys.
     */
    private static Map<String, String> module(String id, String artifactId, String category, String description) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("artifactId", artifactId);
        m.put("category", category);
        m.put("description", description);
        return Collections.unmodifiableMap(m);
    }

    /**
     * Creates a preset descriptor map with {@code name}, {@code description}, and {@code modules}
     * keys.
     */
    private static Map<String, Object> preset(String name, String description, String... moduleIds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("description", description);
        p.put("modules", Collections.unmodifiableList(Arrays.asList(moduleIds)));
        return Collections.unmodifiableMap(p);
    }

    /**
     * Resolves a preset name (case-insensitive, hyphen/underscore tolerant) to its module list,
     * or returns {@code null} if not found.
     */
    @SuppressWarnings("unchecked")
    private List<String> resolvePresetModules(String presetName) {
        String normalized = presetName.toLowerCase(Locale.ROOT).replace('_', '-');
        return PRESETS.stream()
                .filter(p -> normalized.equals(((String) p.get("name")).toLowerCase(Locale.ROOT)))
                .map(p -> (List<String>) p.get("modules"))
                .findFirst()
                .orElse(null);
    }
}
