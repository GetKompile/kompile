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

package ai.kompile.cli.main.app;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Interactive wizard for configuring and launching a crawl job.
 * Dynamically discovers available models, providers, schema presets,
 * chunkers, and source types from the running kompile-app instance.
 */
@CommandLine.Command(
        name = "wizard",
        description = "Interactive wizard to configure and launch a crawl job.%n%n" +
                "Walks through source selection, vector indexing, graph extraction,%n" +
                "model selection (with context length), schema presets, and runtime tuning.%n%n" +
                "Dynamically discovers available models, providers, and presets from%n" +
                "the running kompile-app instance.",
        mixinStandardHelpOptions = true
)
public class CrawlWizardCmd implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = {"--watch", "-w"},
            description = "Watch job progress after starting")
    private boolean watch;

    private KompileHttpClient client;
    private ObjectMapper mapper;
    private Scanner scanner;

    // Discovered server state
    private List<ProviderInfo> providers;
    private List<PresetInfo> schemaPresets;
    private List<String> chunkerNames;
    private List<String> embeddingModels;
    private List<String> suggestedEntityTypes;
    private List<String> suggestedRelationshipTypes;

    @Override
    public Integer call() {
        client = app.requireClient();
        if (client == null) return 1;
        mapper = client.getObjectMapper();
        scanner = new Scanner(System.in);

        try {
            printHeader();
            discoverServerCapabilities();

            // Step 1: Sources
            List<Map<String, Object>> sources = configureSources();
            if (sources.isEmpty()) {
                System.out.println("No sources configured. Aborting.");
                return 1;
            }

            // Step 2: Vector indexing
            Map<String, Object> vectorConfig = configureVectorIndex();

            // Step 3: Graph extraction
            Map<String, Object> graphConfig = configureGraphExtraction();

            // Step 4: Job metadata
            String jobName = promptString("Job name", "CLI wizard crawl - " + sources.size() + " source(s)");

            // Step 5: Review and confirm
            Map<String, Object> request = buildRequest(jobName, sources, vectorConfig, graphConfig);
            if (!confirmAndSubmit(request)) {
                System.out.println("Cancelled.");
                return 0;
            }

            // Submit
            return submitCrawl(request);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    private void discoverServerCapabilities() {
        System.out.println("Discovering server capabilities...");
        providers = discoverProviders();
        schemaPresets = discoverSchemaPresets();
        chunkerNames = discoverChunkers();
        embeddingModels = discoverEmbeddingModels();
        suggestedEntityTypes = discoverSuggestedTypes("/api/graph-extraction/suggested-entity-types");
        suggestedRelationshipTypes = discoverSuggestedTypes("/api/graph-extraction/suggested-relationship-types");

        int modelCount = providers.stream().mapToInt(p -> p.models.size()).sum();
        System.out.printf("  Found %d LLM provider(s) with %d model(s), %d schema preset(s), " +
                        "%d chunker(s), %d embedding model(s)%n%n",
                providers.size(), modelCount, schemaPresets.size(),
                chunkerNames.size(), embeddingModels.size());
    }

    private List<ProviderInfo> discoverProviders() {
        List<ProviderInfo> result = new ArrayList<>();
        try {
            String json = client.getString("/api/graph-extraction/model-providers");
            JsonNode array = mapper.readTree(json);
            if (array.isArray()) {
                for (JsonNode node : array) {
                    ProviderInfo p = new ProviderInfo();
                    p.id = node.path("id").asText();
                    p.name = node.path("name").asText(p.id);
                    p.available = node.path("available").asBoolean(true);
                    p.maxTokens = node.path("maxTokens").asInt(0);

                    JsonNode modelsNode = node.path("models");
                    if (modelsNode.isArray()) {
                        for (JsonNode m : modelsNode) {
                            ModelInfo mi = new ModelInfo();
                            mi.id = m.path("id").asText();
                            mi.name = m.path("name").asText(mi.id);
                            mi.contextWindow = m.path("contextWindow").asInt(0);
                            mi.supportsTools = m.path("supportsTools").asBoolean(false);
                            if (m.has("description")) {
                                mi.description = m.path("description").asText();
                            }
                            p.models.add(mi);
                        }
                    }
                    result.add(p);
                }
            }
        } catch (Exception e) {
            System.err.println("  Warning: Could not discover LLM providers: " + e.getMessage());
        }
        return result;
    }

    private List<PresetInfo> discoverSchemaPresets() {
        List<PresetInfo> result = new ArrayList<>();
        try {
            String json = client.getString("/api/graph-extraction/schema-presets");
            JsonNode array = mapper.readTree(json);
            if (array.isArray()) {
                for (JsonNode node : array) {
                    PresetInfo pi = new PresetInfo();
                    pi.id = node.path("id").asText();
                    pi.name = node.path("name").asText(pi.id);
                    pi.description = node.path("description").asText("");
                    pi.nodeTypeCount = node.path("nodeTypeCount").asInt(
                            node.path("schema").path("nodeTypes").size());
                    pi.relationshipTypeCount = node.path("relationshipTypeCount").asInt(
                            node.path("schema").path("relationshipTypes").size());
                    result.add(pi);
                }
            }
        } catch (Exception e) {
            System.err.println("  Warning: Could not discover schema presets: " + e.getMessage());
        }
        return result;
    }

    private List<String> discoverChunkers() {
        List<String> result = new ArrayList<>();
        try {
            String json = client.getString("/api/documents/chunkers");
            JsonNode array = mapper.readTree(json);
            if (array.isArray()) {
                for (JsonNode node : array) {
                    result.add(node.path("name").asText());
                }
            }
        } catch (Exception e) {
            // Chunker listing may not be available
        }
        return result;
    }

    private List<String> discoverEmbeddingModels() {
        List<String> result = new ArrayList<>();
        try {
            String json = client.getString("/api/models/registry/embedding/available");
            JsonNode node = mapper.readTree(json);
            String current = node.path("currentModel").asText(null);
            if (current != null) result.add(current);
            JsonNode available = node.path("availableModels");
            if (available.isArray()) {
                for (JsonNode m : available) {
                    String id = m.path("modelId").asText(m.path("id").asText(null));
                    if (id != null && !result.contains(id)) {
                        result.add(id);
                    }
                }
            }
        } catch (Exception e) {
            // Embedding model listing may not be available
        }
        return result;
    }

    private List<String> discoverSuggestedTypes(String endpoint) {
        try {
            String json = client.getString(endpoint);
            JsonNode array = mapper.readTree(json);
            List<String> result = new ArrayList<>();
            if (array.isArray()) {
                for (JsonNode n : array) result.add(n.asText());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Step 1: Sources
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> configureSources() {
        printSection("1", "SOURCES");
        System.out.println("  Add one or more sources to crawl (URLs, directories, file paths).");
        System.out.println("  Source types: web, directory, file, excel, slack, gmail, gdocs");
        System.out.println();

        List<Map<String, Object>> sources = new ArrayList<>();
        int idx = 0;

        while (true) {
            String pathOrUrl = promptString("Source path or URL" + (idx > 0 ? " (empty to finish)" : ""), null);
            if (pathOrUrl == null || pathOrUrl.isBlank()) {
                if (sources.isEmpty()) {
                    System.out.println("  At least one source is required.");
                    continue;
                }
                break;
            }

            Map<String, Object> source = new LinkedHashMap<>();
            String detectedType = detectSourceType(pathOrUrl);
            String typeStr = promptString("  Source type", detectedType);

            source.put("label", labelForSource(pathOrUrl, idx));
            source.put("sourceType", typeStr);
            source.put("pathOrUrl", pathOrUrl);

            int depth = promptInt("  Max crawl depth", 3, 0, 100);
            source.put("maxDepth", depth);

            int maxDocs = promptInt("  Max documents (0=unlimited)", 0, 0, 1000000);
            if (maxDocs > 0) source.put("maxDocuments", maxDocs);

            String include = promptString("  Include patterns (comma-separated, empty=all)", null);
            if (include != null && !include.isBlank()) {
                source.put("includePatterns", Arrays.asList(include.split("\\s*,\\s*")));
            }

            String exclude = promptString("  Exclude patterns (comma-separated, empty=none)", null);
            if (exclude != null && !exclude.isBlank()) {
                source.put("excludePatterns", Arrays.asList(exclude.split("\\s*,\\s*")));
            }

            sources.add(source);
            idx++;
            System.out.printf("  Added source #%d: %s (%s)%n%n", idx, pathOrUrl, typeStr);

            if (!promptYesNo("Add another source?", false)) {
                break;
            }
        }

        return sources;
    }

    // -----------------------------------------------------------------------
    // Step 2: Vector Index
    // -----------------------------------------------------------------------

    private Map<String, Object> configureVectorIndex() {
        printSection("2", "VECTOR INDEXING");
        Map<String, Object> config = new LinkedHashMap<>();

        boolean enabled = promptYesNo("Enable vector indexing?", true);
        config.put("enabled", enabled);
        if (!enabled) return config;

        String collection = promptString("  Collection name (empty=default)", null);
        if (collection != null && !collection.isBlank()) {
            config.put("collectionName", collection);
        }

        // Chunker selection
        if (!chunkerNames.isEmpty()) {
            System.out.println("  Available chunkers:");
            for (int i = 0; i < chunkerNames.size(); i++) {
                System.out.printf("    [%d] %s%n", i + 1, chunkerNames.get(i));
            }
            int chunkerIdx = promptInt("  Select chunker (0=default)", 0, 0, chunkerNames.size());
            if (chunkerIdx > 0) {
                config.put("chunkerName", chunkerNames.get(chunkerIdx - 1));
            }
        }

        int chunkSize = promptInt("  Chunk size in tokens (0=default)", 0, 0, 10000);
        if (chunkSize > 0) config.put("chunkSize", chunkSize);

        int chunkOverlap = promptInt("  Chunk overlap in tokens (0=default)", 0, 0, 5000);
        if (chunkOverlap > 0) config.put("chunkOverlap", chunkOverlap);

        boolean adaptive = promptYesNo("  Adaptive embedding batching?", true);
        config.put("adaptiveBatching", adaptive);

        return config;
    }

    // -----------------------------------------------------------------------
    // Step 3: Graph Extraction
    // -----------------------------------------------------------------------

    private Map<String, Object> configureGraphExtraction() {
        printSection("3", "GRAPH EXTRACTION");

        boolean enabled = promptYesNo("Enable knowledge graph extraction?", false);
        if (!enabled) return null;

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", true);

        // 3a: LLM Provider + Model selection with context length display
        configureExtractionModel(config);

        // 3b: Schema preset or custom entity/relationship types
        configureSchema(config);

        // 3c: Extraction parameters
        configureExtractionParams(config);

        // 3d: Entity resolution
        configureEntityResolution(config);

        // 3e: Advanced runtime tuning
        if (promptYesNo("Configure advanced runtime settings?", false)) {
            configureRuntimeTuning(config);
        }

        return config;
    }

    private void configureExtractionModel(Map<String, Object> config) {
        System.out.println();
        System.out.println("  -- Extraction LLM --");

        if (providers.isEmpty()) {
            System.out.println("  No LLM providers discovered. Using default.");
            config.put("llmProvider", "default");
            return;
        }

        // Show providers
        System.out.println("  Available LLM providers:");
        for (int i = 0; i < providers.size(); i++) {
            ProviderInfo p = providers.get(i);
            String avail = p.available ? "" : " (unavailable)";
            int modelCount = p.models.size();
            System.out.printf("    [%d] %s%s — %d model(s)%n", i + 1, p.name, avail, modelCount);
        }

        int providerIdx = promptInt("  Select provider", 1, 1, providers.size());
        ProviderInfo selected = providers.get(providerIdx - 1);
        config.put("llmProvider", selected.id);

        // Show models if available
        if (!selected.models.isEmpty()) {
            System.out.println();
            System.out.println("  Available models for " + selected.name + ":");
            System.out.printf("    %-4s %-35s %-15s %-12s %s%n",
                    "#", "MODEL", "CONTEXT", "TOOLS", "DESCRIPTION");
            System.out.printf("    %-4s %-35s %-15s %-12s %s%n",
                    "---", "-----------------------------------",
                    "---------------", "------------", "---");

            for (int i = 0; i < selected.models.size(); i++) {
                ModelInfo m = selected.models.get(i);
                String ctx = m.contextWindow > 0
                        ? formatTokenCount(m.contextWindow)
                        : "unknown";
                String tools = m.supportsTools ? "yes" : "no";
                String desc = m.description != null ? StringUtils.truncate(m.description, 30) : "";
                System.out.printf("    [%-2d] %-35s %-15s %-12s %s%n",
                        i + 1, StringUtils.truncate(m.name, 35), ctx, tools, desc);
            }

            int modelIdx = promptInt("  Select model (0=provider default)", 0, 0, selected.models.size());
            if (modelIdx > 0) {
                ModelInfo chosenModel = selected.models.get(modelIdx - 1);
                config.put("modelName", chosenModel.id);

                // Show context window guidance
                if (chosenModel.contextWindow > 0) {
                    System.out.printf("  Context window: %s tokens%n", formatTokenCount(chosenModel.contextWindow));
                    int suggestedMaxTokens = Math.min(4096, chosenModel.contextWindow / 4);
                    System.out.printf("  Suggested max output tokens: %s%n", formatTokenCount(suggestedMaxTokens));
                    int suggestedCharsPerCall = (int) (chosenModel.contextWindow * 2.5);
                    System.out.printf("  Suggested chars/extraction call: ~%s%n", formatNumber(suggestedCharsPerCall));
                }
            }
        }

        // Temperature
        double temp = promptDouble("  Extraction temperature (0.0=deterministic, 2.0=creative)", 0.0, 0.0, 2.0);
        config.put("temperature", temp);

        // Max tokens
        int defaultMaxTokens = 4096;
        if (!selected.models.isEmpty()) {
            // Try to derive from selected model's context window
            int modelIdx = 0;
            if (config.containsKey("modelName")) {
                String mn = (String) config.get("modelName");
                for (int i = 0; i < selected.models.size(); i++) {
                    if (selected.models.get(i).id.equals(mn)) {
                        modelIdx = i;
                        break;
                    }
                }
                if (selected.models.get(modelIdx).contextWindow > 0) {
                    defaultMaxTokens = Math.min(4096, selected.models.get(modelIdx).contextWindow / 4);
                }
            }
        }
        int maxTokens = promptInt("  Max output tokens for extraction", defaultMaxTokens, 64, 32000);
        config.put("maxTokens", maxTokens);
    }

    private void configureSchema(Map<String, Object> config) {
        System.out.println();
        System.out.println("  -- Schema --");

        boolean usePreset = false;
        if (!schemaPresets.isEmpty()) {
            System.out.println("  Available schema presets:");
            for (int i = 0; i < schemaPresets.size(); i++) {
                PresetInfo p = schemaPresets.get(i);
                System.out.printf("    [%d] %s — %d node types, %d relationship types%n",
                        i + 1, p.name, p.nodeTypeCount, p.relationshipTypeCount);
                if (!p.description.isEmpty()) {
                    System.out.printf("        %s%n", StringUtils.truncate(p.description, 70));
                }
            }
            System.out.println("    [0] Custom (define entity/relationship types manually)");

            int presetIdx = promptInt("  Select schema preset", 0, 0, schemaPresets.size());
            if (presetIdx > 0) {
                config.put("schemaPresetId", schemaPresets.get(presetIdx - 1).id);
                usePreset = true;
                System.out.println("  Using preset: " + schemaPresets.get(presetIdx - 1).name);
            }
        }

        if (!usePreset) {
            // Custom entity types
            if (!suggestedEntityTypes.isEmpty()) {
                System.out.println("  Suggested entity types: " + String.join(", ", suggestedEntityTypes));
            }
            String entities = promptString("  Entity types (comma-separated, empty=extract all)", null);
            if (entities != null && !entities.isBlank()) {
                config.put("entityTypes", Arrays.asList(entities.toUpperCase().split("\\s*,\\s*")));
            }

            // Custom relationship types
            if (!suggestedRelationshipTypes.isEmpty()) {
                System.out.println("  Suggested relationship types: " + String.join(", ", suggestedRelationshipTypes));
            }
            String relations = promptString("  Relationship types (comma-separated, empty=extract all)", null);
            if (relations != null && !relations.isBlank()) {
                config.put("relationshipTypes", Arrays.asList(relations.toUpperCase().split("\\s*,\\s*")));
            }
        }

        // Schema enforcement mode
        System.out.println("  Schema enforcement modes:");
        System.out.println("    [1] NONE    — Accept all entity/relationship types from LLM");
        System.out.println("    [2] LENIENT — Prefer defined types but allow new discoveries");
        System.out.println("    [3] STRICT  — Only accept types defined in schema");
        int modeIdx = promptInt("  Schema enforcement", 2, 1, 3);
        String[] modes = {"NONE", "LENIENT", "STRICT"};
        config.put("schemaMode", modes[modeIdx - 1]);
    }

    private void configureExtractionParams(Map<String, Object> config) {
        System.out.println();
        System.out.println("  -- Extraction Parameters --");

        double minConf = promptDouble("  Minimum confidence threshold (0.0-1.0)", 0.5, 0.0, 1.0);
        config.put("minConfidence", minConf);

        String customPrompt = promptString("  Custom extraction prompt (empty=use default)", null);
        if (customPrompt != null && !customPrompt.isBlank()) {
            config.put("customPrompt", customPrompt);
        }
    }

    private void configureEntityResolution(Map<String, Object> config) {
        System.out.println();
        System.out.println("  -- Entity Resolution --");

        boolean resolution = promptYesNo("  Run entity resolution to merge duplicates?", true);
        config.put("entityResolution", resolution);

        if (resolution) {
            boolean useEmbeddings = promptYesNo("  Use embedding-based fuzzy matching?", true);
            config.put("entityResolutionUseEmbeddings", useEmbeddings);

            if (useEmbeddings) {
                double threshold = promptDouble("  Embedding similarity threshold", 0.88, 0.5, 1.0);
                config.put("entityResolutionEmbeddingThreshold", threshold);
            }
        }
    }

    private void configureRuntimeTuning(Map<String, Object> config) {
        System.out.println();
        System.out.println("  -- Advanced Runtime Tuning --");
        System.out.println("  These settings control crawl pipeline concurrency and memory.");
        System.out.println("  Press Enter to accept defaults for each.");

        // Derive context-aware defaults from model selection
        int defaultCharsPerCall = 24000;
        if (config.containsKey("modelName") && config.containsKey("llmProvider")) {
            String providerId = (String) config.get("llmProvider");
            String modelId = (String) config.get("modelName");
            int ctxWindow = findContextWindow(providerId, modelId);
            if (ctxWindow > 0) {
                // Use ~60% of context window in chars (rough 1 token ~ 4 chars)
                defaultCharsPerCall = (int) (ctxWindow * 4 * 0.6);
                // Cap at reasonable maximum
                defaultCharsPerCall = Math.min(defaultCharsPerCall, 200000);
                System.out.printf("  (Model context: %s tokens -> suggested ~%s chars/call)%n",
                        formatTokenCount(ctxWindow), formatNumber(defaultCharsPerCall));
            }
        }

        // LLM call settings
        System.out.println();
        System.out.println("  LLM Call Settings:");
        int charsPerCall = promptInt("    Chars per LLM extraction call", defaultCharsPerCall, 240, 200000);
        // Store in a nested map or as flat fields — the server config uses flat fields
        config.put("_runtime_llmCharsPerCall", charsPerCall);

        int parallelism = promptInt("    LLM call parallelism", 4, 1, 32);
        config.put("_runtime_llmParallelism", parallelism);

        int timeoutSecs = promptInt("    LLM call timeout (seconds)", 600, 30, 7200);
        config.put("_runtime_llmCallTimeoutSeconds", timeoutSecs);

        // Pipeline concurrency
        System.out.println();
        System.out.println("  Pipeline Concurrency:");
        int sourceParallelism = promptInt("    Source loading parallelism", 2, 1, 32);
        config.put("_runtime_crawlSourceLoadParallelism", sourceParallelism);

        int chunkParallelism = promptInt("    Chunking parallelism", 2, 1, 32);
        config.put("_runtime_crawlChunkingParallelism", chunkParallelism);

        int extractionParallelism = promptInt("    Graph extraction parallelism", 2, 1, 32);
        config.put("_runtime_crawlGraphExtractionParallelism", extractionParallelism);

        int extractionBatchSize = promptInt("    Graph extraction batch size", 10, 1, 128);
        config.put("_runtime_crawlGraphExtractionBatchSize", extractionBatchSize);

        // Memory thresholds
        System.out.println();
        System.out.println("  Memory Thresholds:");
        int memWait = promptInt("    JVM heap wait threshold (%)", 82, 1, 99);
        config.put("_runtime_crawlMemoryWaitThresholdPercent", memWait);

        int memCritical = promptInt("    JVM heap critical threshold (%)", 90, memWait, 100);
        config.put("_runtime_crawlMemoryCriticalThresholdPercent", memCritical);
    }

    // -----------------------------------------------------------------------
    // Step 5: Review & Submit
    // -----------------------------------------------------------------------

    private Map<String, Object> buildRequest(String jobName, List<Map<String, Object>> sources,
                                              Map<String, Object> vectorConfig,
                                              Map<String, Object> graphConfig) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", jobName);
        request.put("sources", sources);
        if (vectorConfig != null) request.put("vectorIndex", vectorConfig);

        if (graphConfig != null) {
            // Separate runtime config keys (prefixed with _runtime_) from request config
            Map<String, Object> cleanGraph = new LinkedHashMap<>();
            Map<String, Object> runtimeUpdates = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : graphConfig.entrySet()) {
                if (entry.getKey().startsWith("_runtime_")) {
                    runtimeUpdates.put(entry.getKey().substring("_runtime_".length()), entry.getValue());
                } else {
                    cleanGraph.put(entry.getKey(), entry.getValue());
                }
            }
            request.put("graphExtraction", cleanGraph);
            if (!runtimeUpdates.isEmpty()) {
                request.put("_runtimeUpdates", runtimeUpdates);
            }
        }

        return request;
    }

    private boolean confirmAndSubmit(Map<String, Object> request) {
        printSection("REVIEW", "CRAWL CONFIGURATION");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) request.get("sources");
        System.out.println("  Job name: " + request.get("name"));
        System.out.println("  Sources: " + sources.size());
        for (int i = 0; i < sources.size(); i++) {
            Map<String, Object> s = sources.get(i);
            System.out.printf("    [%d] %s (%s) depth=%s%n",
                    i + 1, s.get("pathOrUrl"), s.get("sourceType"), s.get("maxDepth"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> vectorConfig = (Map<String, Object>) request.get("vectorIndex");
        if (vectorConfig != null) {
            System.out.println();
            System.out.println("  Vector Indexing: " + (Boolean.TRUE.equals(vectorConfig.get("enabled")) ? "enabled" : "disabled"));
            if (vectorConfig.containsKey("collectionName")) {
                System.out.println("    Collection: " + vectorConfig.get("collectionName"));
            }
            if (vectorConfig.containsKey("chunkerName")) {
                System.out.println("    Chunker: " + vectorConfig.get("chunkerName"));
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> graphConfig = (Map<String, Object>) request.get("graphExtraction");
        if (graphConfig != null && Boolean.TRUE.equals(graphConfig.get("enabled"))) {
            System.out.println();
            System.out.println("  Graph Extraction: enabled");
            if (graphConfig.containsKey("llmProvider")) {
                System.out.println("    Provider: " + graphConfig.get("llmProvider"));
            }
            if (graphConfig.containsKey("modelName")) {
                System.out.println("    Model: " + graphConfig.get("modelName"));
                // Show context window for the selected model
                String providerId = (String) graphConfig.get("llmProvider");
                String modelId = (String) graphConfig.get("modelName");
                int ctx = findContextWindow(providerId, modelId);
                if (ctx > 0) {
                    System.out.println("    Context window: " + formatTokenCount(ctx));
                }
            }
            if (graphConfig.containsKey("schemaPresetId")) {
                System.out.println("    Schema preset: " + graphConfig.get("schemaPresetId"));
            }
            if (graphConfig.containsKey("entityTypes")) {
                System.out.println("    Entity types: " + graphConfig.get("entityTypes"));
            }
            if (graphConfig.containsKey("relationshipTypes")) {
                System.out.println("    Relationship types: " + graphConfig.get("relationshipTypes"));
            }
            System.out.println("    Schema mode: " + graphConfig.getOrDefault("schemaMode", "LENIENT"));
            System.out.println("    Min confidence: " + graphConfig.getOrDefault("minConfidence", 0.5));
            System.out.println("    Max tokens: " + graphConfig.getOrDefault("maxTokens", 4096));
            System.out.println("    Temperature: " + graphConfig.getOrDefault("temperature", 0.0));
        } else {
            System.out.println();
            System.out.println("  Graph Extraction: disabled");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeUpdates = (Map<String, Object>) request.get("_runtimeUpdates");
        if (runtimeUpdates != null && !runtimeUpdates.isEmpty()) {
            System.out.println();
            System.out.println("  Runtime Tuning Overrides:");
            for (Map.Entry<String, Object> entry : runtimeUpdates.entrySet()) {
                System.out.printf("    %s: %s%n", entry.getKey(), entry.getValue());
            }
        }

        System.out.println();
        return promptYesNo("Start this crawl job?", true);
    }

    @SuppressWarnings("unchecked")
    private int submitCrawl(Map<String, Object> request) throws IOException, InterruptedException {
        // Apply runtime config updates to the server before starting the crawl
        Map<String, Object> runtimeUpdates = (Map<String, Object>) request.remove("_runtimeUpdates");
        if (runtimeUpdates != null && !runtimeUpdates.isEmpty()) {
            System.out.println("Applying runtime configuration...");
            try {
                client.put("/api/graph-extraction/config", runtimeUpdates, String.class);
            } catch (Exception e) {
                System.err.println("Warning: Could not apply runtime config: " + e.getMessage());
                System.err.println("The crawl will use current server defaults.");
            }
        }

        System.out.println("Submitting crawl job...");
        String response = client.postString("/api/unified-crawl/start", request);

        if (app.isJsonOutput()) {
            OutputFormatter.printJson(response);
            return 0;
        }

        JsonNode node = mapper.readTree(response);
        String jobId = node.path("jobId").asText(node.path("id").asText(null));

        if (jobId != null) {
            System.out.println();
            System.out.println("Crawl job started successfully.");
            OutputFormatter.printKv("Job ID", jobId);
            if (node.has("status")) OutputFormatter.printKv("Status", node.get("status").asText());
            if (node.has("name")) OutputFormatter.printKv("Name", node.get("name").asText());
            System.out.println();
            System.out.println("Track progress with: kompile app crawl status " + jobId);

            if (watch) {
                return watchJob(jobId);
            }
        } else {
            System.out.println("Response:");
            OutputFormatter.printJson(response);
        }

        return 0;
    }

    private int watchJob(String jobId) throws IOException, InterruptedException {
        String endpoint = "/api/unified-crawl/jobs/" + jobId;
        String lastLine = "";

        while (true) {
            String response = client.getString(endpoint);
            JsonNode job = mapper.readTree(response);
            String status = job.path("status").asText("UNKNOWN");

            int loaded = job.path("documentsLoaded").asInt(0);
            int indexed = job.path("documentsIndexed").asInt(0);
            int entities = job.path("entitiesExtracted").asInt(0);
            int errors = job.path("errorCount").asInt(0);
            String line = String.format("  %-12s  Loaded: %-6d  Indexed: %-6d  Entities: %-6d  Errors: %d",
                    status, loaded, indexed, entities, errors);

            if (!line.equals(lastLine)) {
                System.out.print("\r" + line);
                System.out.flush();
                lastLine = line;
            }

            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                System.out.println();
                System.out.println();
                if ("COMPLETED".equals(status)) {
                    System.out.println("Crawl completed successfully.");
                } else {
                    System.out.println("Crawl ended with status: " + status);
                    if (job.has("error")) {
                        OutputFormatter.printKv("Error", job.path("error").asText());
                    }
                }
                return "COMPLETED".equals(status) ? 0 : 1;
            }

            Thread.sleep(2000);
        }
    }

    // -----------------------------------------------------------------------
    // Prompt helpers
    // -----------------------------------------------------------------------

    private String promptString(String label, String defaultValue) {
        if (defaultValue != null) {
            System.out.printf("  %s [%s]: ", label, defaultValue);
        } else {
            System.out.printf("  %s: ", label);
        }
        System.out.flush();
        String line = scanner.nextLine().trim();
        if (line.isEmpty()) return defaultValue;
        return line;
    }

    private int promptInt(String label, int defaultValue, int min, int max) {
        while (true) {
            System.out.printf("  %s [%d]: ", label, defaultValue);
            System.out.flush();
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) return defaultValue;
            try {
                int value = Integer.parseInt(line);
                if (value < min || value > max) {
                    System.out.printf("    Value must be between %d and %d.%n", min, max);
                    continue;
                }
                return value;
            } catch (NumberFormatException e) {
                System.out.println("    Invalid number.");
            }
        }
    }

    private double promptDouble(String label, double defaultValue, double min, double max) {
        while (true) {
            System.out.printf("  %s [%.1f]: ", label, defaultValue);
            System.out.flush();
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) return defaultValue;
            try {
                double value = Double.parseDouble(line);
                if (value < min || value > max) {
                    System.out.printf("    Value must be between %.1f and %.1f.%n", min, max);
                    continue;
                }
                return value;
            } catch (NumberFormatException e) {
                System.out.println("    Invalid number.");
            }
        }
    }

    private boolean promptYesNo(String label, boolean defaultValue) {
        String hint = defaultValue ? "Y/n" : "y/N";
        System.out.printf("  %s [%s]: ", label, hint);
        System.out.flush();
        String line = scanner.nextLine().trim().toLowerCase();
        if (line.isEmpty()) return defaultValue;
        return line.startsWith("y");
    }

    // -----------------------------------------------------------------------
    // Display helpers
    // -----------------------------------------------------------------------

    private void printHeader() {
        System.out.println();
        System.out.println("===========================================================");
        System.out.println("              KOMPILE CRAWL JOB WIZARD");
        System.out.println("===========================================================");
        System.out.println("  Configure and launch a crawl job step by step.");
        System.out.println("  Press Enter to accept [defaults] shown in brackets.");
        System.out.println();
    }

    private void printSection(String number, String title) {
        System.out.println();
        System.out.println("-----------------------------------------------------------");
        System.out.printf("  STEP %s: %s%n", number, title);
        System.out.println("-----------------------------------------------------------");
    }

    private static String formatTokenCount(int tokens) {
        if (tokens >= 1_000_000) {
            return String.format("%.1fM", tokens / 1_000_000.0);
        } else if (tokens >= 1_000) {
            return String.format("%dK", tokens / 1_000);
        }
        return String.valueOf(tokens);
    }

    private static String formatNumber(int n) {
        if (n >= 1_000_000) {
            return String.format("%.1fM", n / 1_000_000.0);
        } else if (n >= 1_000) {
            return String.format("%dK", n / 1_000);
        }
        return String.valueOf(n);
    }

    // -----------------------------------------------------------------------
    // Source type detection (mirrors CrawlCommand.StartCmd logic)
    // -----------------------------------------------------------------------

    private String detectSourceType(String source) {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return "WEB_CRAWL";
        }
        if (source.startsWith("www.")) {
            return "WEB_CRAWL";
        }
        String lower = source.toLowerCase();
        for (String ext : List.of(".xls", ".xlsx", ".xlsm", ".xlsb", ".ods", ".csv", ".tsv")) {
            if (lower.endsWith(ext)) return "FILE";
        }
        java.nio.file.Path path = java.nio.file.Paths.get(source);
        if (java.nio.file.Files.isDirectory(path)) return "DIRECTORY";
        if (java.nio.file.Files.isRegularFile(path)) return "FILE";
        return "DIRECTORY";
    }

    private String labelForSource(String source, int index) {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            try {
                java.net.URI uri = java.net.URI.create(source);
                return uri.getHost();
            } catch (Exception e) {
                // fall through
            }
        }
        java.nio.file.Path path = java.nio.file.Paths.get(source);
        String name = path.getFileName() != null ? path.getFileName().toString() : source;
        if (name.length() > 30) name = name.substring(0, 30);
        return name;
    }

    private int findContextWindow(String providerId, String modelId) {
        if (providerId == null || modelId == null) return 0;
        for (ProviderInfo p : providers) {
            if (p.id.equals(providerId)) {
                for (ModelInfo m : p.models) {
                    if (m.id.equals(modelId)) {
                        return m.contextWindow;
                    }
                }
            }
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Data classes for discovered server state
    // -----------------------------------------------------------------------

    private static class ProviderInfo {
        String id;
        String name;
        boolean available;
        int maxTokens;
        List<ModelInfo> models = new ArrayList<>();
    }

    private static class ModelInfo {
        String id;
        String name;
        String description;
        int contextWindow;
        boolean supportsTools;
    }

    private static class PresetInfo {
        String id;
        String name;
        String description;
        int nodeTypeCount;
        int relationshipTypeCount;
    }
}
