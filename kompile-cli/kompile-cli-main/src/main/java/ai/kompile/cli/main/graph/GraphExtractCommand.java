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
package ai.kompile.cli.main.graph;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.app.AppClientMixin;
import ai.kompile.cli.main.app.OutputFormatter;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code kompile graph extract} — runs multi-agent graph extraction against a
 * running kompile-app. Accepts direct text, a file path, or a fact-sheet ID as
 * the input source and POSTs to the appropriate REST endpoint.
 *
 * <p>Examples:
 * <pre>
 *   # Extract from inline text with two agents, persist results
 *   kompile graph extract --text "Alice works at Acme Corp." \
 *       --agents llm-active,pattern-ner --persist
 *
 *   # Extract from a local file, keep results in memory only
 *   kompile graph extract --file notes.txt --min-confidence 0.7
 *
 *   # Extract from all chunks belonging to a fact sheet
 *   kompile graph extract --fact-sheet-id 42 --strategy HIGHEST_CONFIDENCE
 * </pre>
 */
@CommandLine.Command(
        name = "extract",
        description = "Run multi-agent graph extraction from text, a file, or a fact-sheet",
        mixinStandardHelpOptions = true
)
public class GraphExtractCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    // -------------------------------------------------------------------------
    // Input source options (at most one should be used at a time)
    // -------------------------------------------------------------------------

    @CommandLine.Option(names = {"--text", "-t"},
            description = "Direct text input to extract entities and relationships from")
    private String text;

    @CommandLine.Option(names = {"--file"},
            description = "Path to a file whose content is used as extraction input")
    private Path file;

    @CommandLine.Option(names = {"--fact-sheet-id", "-f"},
            description = "Fact-sheet ID; the server fetches the associated chunks automatically")
    private Long factSheetId;

    // -------------------------------------------------------------------------
    // Extraction configuration
    // -------------------------------------------------------------------------

    @CommandLine.Option(names = {"--agents", "-a"},
            description = "Comma-separated agent IDs to use (default: all available agents)",
            split = ",")
    private List<String> agents;

    @CommandLine.Option(names = {"--strategy", "-s"},
            defaultValue = "UNION",
            description = "Merge strategy: UNION, INTERSECTION, HIGHEST_CONFIDENCE, FIRST_WINS (default: UNION)")
    private String strategy;

    @CommandLine.Option(names = {"--persist"},
            description = "Persist extracted results to the knowledge graph (default: false)")
    private boolean persist;

    @CommandLine.Option(names = {"--min-confidence"},
            defaultValue = "0.5",
            description = "Minimum confidence threshold for included results (default: 0.5)")
    private double minConfidence;

    @CommandLine.Option(names = {"--entity-types"},
            defaultValue = "PERSON,ORGANIZATION,LOCATION,CONCEPT,EVENT",
            description = "Comma-separated entity types to extract (default: PERSON,ORGANIZATION,LOCATION,CONCEPT,EVENT)",
            split = ",")
    private List<String> entityTypes;

    @CommandLine.Option(names = {"--relationship-types"},
            description = "Comma-separated relationship types (e.g., WORKS_AT,LOCATED_IN)",
            split = ",")
    private List<String> relationshipTypes;

    // -------------------------------------------------------------------------
    // Local extraction options (CLI-side LLM, no kompile-app needed)
    // -------------------------------------------------------------------------

    @CommandLine.Option(names = {"--local"},
            description = "Run extraction locally using CLI LLM provider (auto-fallback if server unreachable)")
    private boolean local;

    @CommandLine.Option(names = {"--auto-start"},
            description = "Auto-start a local model server if no LLM provider is available (implies --local)")
    private boolean autoStart;

    @CommandLine.Option(names = {"--llm-provider"},
            description = "LLM provider for local extraction (openai, anthropic, local, etc.)")
    private String llmProvider;

    @CommandLine.Option(names = {"--llm-model"},
            description = "LLM model for local extraction (e.g., gpt-4o, claude-sonnet-4)")
    private String llmModel;

    @CommandLine.Option(names = {"--llm-api-key"},
            description = "API key for local extraction LLM provider")
    private String llmApiKey;

    @CommandLine.Option(names = {"--custom-prompt"},
            description = "Custom extraction prompt to prepend to the schema instructions")
    private String customPrompt;

    // -------------------------------------------------------------------------
    // Command implementation
    // -------------------------------------------------------------------------

    private static final ObjectMapper LOCAL_MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public Integer call() {
        // --auto-start implies --local
        if (autoStart) local = true;

        if (local || llmProvider != null) {
            return runLocalExtraction();
        }

        // Try server-side extraction, with optional fallback to local
        KompileHttpClient client = app.requireClient();
        if (client == null) {
            System.err.println("No kompile-app server available. Attempting local extraction...");
            return runLocalExtraction();
        }

        try {
            String inputText = resolveInputText();
            Map<String, Object> body = buildRequestBody(inputText);
            String endpoint = persist
                    ? "/api/graph/multi-agent/extract-and-persist"
                    : "/api/graph/multi-agent/extract";

            String response = client.postString(endpoint, body);

            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
                return 0;
            }

            renderResult(client.getObjectMapper().readTree(response));
            return 0;

        } catch (java.net.ConnectException e) {
            System.err.println("Server unreachable. Falling back to local extraction...");
            return runLocalExtraction();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int runLocalExtraction() {
        try (CliExtractionLlmClient llm = CliExtractionLlmClient.resolve(
                llmProvider, llmModel, llmApiKey, autoStart)) {
            if (llm == null) {
                System.err.println("Error: No LLM provider available for local extraction.");
                System.err.println();
                System.err.println("Configure one of:");
                System.err.println("  kompile chat setup                    (interactive setup)");
                System.err.println("  export OPENAI_API_KEY=sk-...          (env var)");
                System.err.println("  export ANTHROPIC_API_KEY=sk-ant-...   (env var)");
                System.err.println("  kompile graph extract --llm-provider local    (use running local server)");
                System.err.println("  kompile graph extract --auto-start    (download & run a local model)");
                return 1;
            }

            System.err.println("Using LLM: " + llm.getResolvedFrom());

            String inputText = resolveInputText();
            if (inputText == null || inputText.isBlank()) {
                System.err.println("Error: No input text provided. Use --text, --file, or --fact-sheet-id.");
                return 1;
            }

            CliGraphExtractor extractor = new CliGraphExtractor(llm);
            extractor.setEntityTypes(entityTypes);
            extractor.setRelationshipTypes(relationshipTypes);
            extractor.setMinConfidence(minConfidence);
            extractor.setCustomPrompt(customPrompt);

            CliGraphExtractor.ExtractionResult result = extractor.extract(inputText);

            if (result.hasError()) {
                System.err.println("Warning: " + result.parseError);
                if (result.rawResponse != null) {
                    System.err.println("Raw LLM response:");
                    System.err.println(result.rawResponse);
                }
            }

            // If --persist and server is available, push results to server
            if (persist) {
                KompileHttpClient client = app.requireClient();
                if (client != null) {
                    try {
                        String serverResponse = extractor.extractAndPersist(inputText, client);
                        System.err.println("Results persisted to knowledge graph.");
                    } catch (Exception e) {
                        System.err.println("Warning: Could not persist to server: " + e.getMessage());
                        System.err.println("Extraction results shown below (not persisted).");
                    }
                } else {
                    System.err.println("Warning: No server available for persistence. Showing results only.");
                }
            }

            if (app.isJsonOutput()) {
                OutputFormatter.printJson(LOCAL_MAPPER.writeValueAsString(result));
                return 0;
            }

            renderLocalResult(result);
            return 0;

        } catch (Exception e) {
            System.err.println("Error during local extraction: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Resolves the text to extract from, or null when the server is expected to
     * fetch the content itself (fact-sheet-only mode).
     */
    private String resolveInputText() throws Exception {
        if (text != null && !text.isBlank()) {
            if (file != null || factSheetId != null) {
                System.err.println("Warning: --text takes precedence over --file and --fact-sheet-id");
            }
            return text;
        }
        if (file != null) {
            if (!Files.exists(file)) {
                throw new IllegalArgumentException("File not found: " + file);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        // fact-sheet-only mode: no local text; server resolves chunks
        return null;
    }

    /**
     * Builds the JSON request body for the extraction endpoint.
     */
    private Map<String, Object> buildRequestBody(String inputText) {
        Map<String, Object> body = new LinkedHashMap<>();

        // Wrap local text as a single-element chunk list
        if (inputText != null) {
            List<Map<String, Object>> chunks = new ArrayList<>();
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("text", inputText);
            chunks.add(chunk);
            body.put("chunkTexts", chunks);
        }

        if (factSheetId != null) {
            body.put("factSheetId", factSheetId);
        }

        if (agents != null && !agents.isEmpty()) {
            body.put("agentIds", agents);
        }

        body.put("mergeStrategy", strategy);

        // Nest extraction config
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("minConfidence", minConfidence);
        config.put("entityTypes", entityTypes);
        body.put("config", config);

        return body;
    }

    /**
     * Renders a human-readable summary of the extraction response.
     */
    private void renderResult(JsonNode result) {
        System.out.println("Graph Extraction Results");
        System.out.println("------------------------");

        // Top-level counts
        int entityCount = countFrom(result, "entities");
        int relationshipCount = countFrom(result, "relationships");
        OutputFormatter.printKv("Entities extracted", entityCount);
        OutputFormatter.printKv("Relationships extracted", relationshipCount);

        // Merge strategy echoed back
        String usedStrategy = result.path("mergeStrategy").asText(strategy);
        OutputFormatter.printKv("Merge strategy", usedStrategy);

        if (persist) {
            OutputFormatter.printKv("Persisted", result.path("persisted").asBoolean(false) ? "yes" : "no");
        }

        // Per-agent contributions
        JsonNode agentResults = result.path("agentResults");
        if (agentResults.isObject() && agentResults.size() > 0) {
            System.out.println();
            System.out.println("Per-agent contributions:");
            agentResults.fields().forEachRemaining(entry -> {
                JsonNode agentData = entry.getValue();
                int agentEntities = countFrom(agentData, "entities");
                int agentRelationships = countFrom(agentData, "relationships");
                double agentConf = agentData.path("averageConfidence").asDouble(-1);
                String confStr = agentConf >= 0
                        ? String.format(" (avg confidence: %.2f)", agentConf)
                        : "";
                OutputFormatter.printKv(entry.getKey(),
                        agentEntities + " entities, " + agentRelationships + " relationships" + confStr);
            });
        }

        // Top entities (up to 10) for quick inspection
        JsonNode entities = result.path("entities");
        if (entities.isArray() && !entities.isEmpty()) {
            System.out.println();
            System.out.println("Top entities (up to 10):");
            int shown = Math.min(entities.size(), 10);
            for (int i = 0; i < shown; i++) {
                JsonNode entity = entities.get(i);
                String name = entity.path("name").asText(entity.path("title").asText("-"));
                String type = entity.path("type").asText(entity.path("entityType").asText("?"));
                double conf = entity.path("confidence").asDouble(-1);
                String confStr = conf >= 0 ? String.format(" [%.0f%%]", conf * 100) : "";
                System.out.printf("  [%s] %s%s%n", type, name, confStr);
            }
            if (entities.size() > 10) {
                System.out.println("  ... " + (entities.size() - 10) + " more (use --json for full output)");
            }
        }
    }

    /**
     * Renders a human-readable summary of a local extraction result.
     */
    private void renderLocalResult(CliGraphExtractor.ExtractionResult result) {
        System.out.println("Graph Extraction Results (local)");
        System.out.println("--------------------------------");
        OutputFormatter.printKv("Entities extracted", result.entityCount());
        OutputFormatter.printKv("Relationships extracted", result.relationCount());
        OutputFormatter.printKv("Mode", "CLI-side LLM extraction");

        if (result.entityCount() > 0) {
            System.out.println();
            System.out.println("Entities:");
            int shown = Math.min(result.entities.size(), 20);
            for (int i = 0; i < shown; i++) {
                CliGraphExtractor.ExtractedEntity e = result.entities.get(i);
                String confStr = e.confidence != null ? String.format(" [%.0f%%]", e.confidence * 100) : "";
                System.out.printf("  [%s] %s%s%n", e.type, e.name, confStr);
                if (e.description != null && !e.description.isBlank()) {
                    System.out.println("    " + e.description);
                }
            }
            if (result.entities.size() > 20) {
                System.out.println("  ... " + (result.entities.size() - 20) + " more (use --json for full output)");
            }
        }

        if (result.relationCount() > 0) {
            System.out.println();
            System.out.println("Relationships:");
            int shown = Math.min(result.relations.size(), 20);
            for (int i = 0; i < shown; i++) {
                CliGraphExtractor.ExtractedRelation r = result.relations.get(i);
                String confStr = r.confidence != null ? String.format(" [%.0f%%]", r.confidence * 100) : "";
                System.out.printf("  %s -[%s]-> %s%s%n", r.source, r.type, r.target, confStr);
                if (r.description != null && !r.description.isBlank()) {
                    System.out.println("    " + r.description);
                }
            }
            if (result.relations.size() > 20) {
                System.out.println("  ... " + (result.relations.size() - 20) + " more (use --json for full output)");
            }
        }
    }

    /**
     * Returns the size of a named array field, or 0 when absent / not an array.
     */
    private static int countFrom(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isArray() ? child.size() : child.asInt(0);
    }
}
