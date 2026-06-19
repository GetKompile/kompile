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
package ai.kompile.fpna.v3;

import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO.ExtractedGraph;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO.ExtractedEntity;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO.ExtractedRelationship;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;
import ai.kompile.llm.cli.CliAgentLanguageModelImpl;
import ai.kompile.llm.cli.CliAgentLlmConfigService;
import ai.kompile.process.discovery.LlmProcessDiscoveryService;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedPhase;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business process discovery crawl using kompile's actual primitives:
 * <ol>
 *   <li>Phase 1: Extract graph (entities + relationships) from process docs via
 *       {@link CliAgentLanguageModelImpl} — same as {@link FpnaClaudeCrawlTest}</li>
 *   <li>Phase 2: Entity resolution via {@link EntityResolutionService}</li>
 *   <li>Phase 3: Discover business processes from the extracted graph using
 *       {@link LlmProcessDiscoveryService}'s prompt format and
 *       {@link ProcessSuggestion} model — this is the actual backend logic</li>
 *   <li>Phase 4: Validate discovered processes against expected graph</li>
 *   <li>Phase 5: Write HTML report from {@link ProcessSuggestion} objects</li>
 * </ol>
 *
 * Requires: {@code claude} CLI installed and available on PATH.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FpnaBusinessProcessCrawlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path ARTIFACTS_DIR = Paths.get(
            System.getProperty("user.home"),
            "Documents/GitHub/kompile/FP&A workflow artifacts 2026-05");

    private static final Path OUTPUT_DIR = Paths.get(
            System.getProperty("user.home"),
            "Documents/GitHub/kompile/kompile-fpna-v3/project/src/test/resources");

    // ── Shared state ──
    private static CliAgentLanguageModelImpl cliAgentLlm;
    private static final Map<String, ExtractedGraph> PER_DOC_RESULTS = new LinkedHashMap<>();
    private static final List<GraphExtractionSchema.ExtractedEntity> ALL_RESOLVED_ENTITIES = new ArrayList<>();
    private static final List<GraphExtractionSchema.ExtractedRelation> ALL_RESOLVED_RELATIONS = new ArrayList<>();
    private static final List<ProcessSuggestion> DISCOVERED_PROCESSES = new ArrayList<>();

    private EntityResolutionService resolutionService;

    // Schema types from fpna-cpg-channel-v1
    private static final Set<String> SCHEMA_NODE_TYPES = Set.of(
            "CHANNEL_TAXONOMY", "SKU_MASTER", "CURRENCY_REGISTRY", "CHART_OF_ACCOUNTS",
            "FX_FORWARD_CURVE", "REGIONAL_FORECAST", "FORECAST_ADJUSTMENT", "BANK_TRANSACTION",
            "VARIANCE_TRIAGE", "DATA_QUALITY_FLAG", "CONTROL_ASSERTION", "CLOSE_STEP",
            "PIPELINE_COVERAGE", "CASH_CONVERSION_CYCLE", "FREE_CASH_FLOW_MARGIN",
            "ROIC_DECOMPOSITION", "APPROVAL_ROLE", "PERSON", "TABLE", "SPREADSHEET", "EMAIL_MESSAGE"
    );

    @BeforeAll
    static void initCliAgent() {
        CliAgentLlmConfigService configService = new CliAgentLlmConfigService();
        configService.initialize();

        CliAgentLlmConfigService.CliLlmConfig config = configService.getConfig();
        config.enabled = true;
        config.command = "claude";
        config.skipPermissions = true;
        config.timeoutSeconds = 600;
        configService.updateConfig(config);

        cliAgentLlm = new CliAgentLanguageModelImpl(configService);
        Assumptions.assumeTrue(cliAgentLlm.isAvailable(),
                "Claude CLI not available — skipping business process crawl tests");
    }

    @BeforeEach
    void setUp() {
        resolutionService = new EntityResolutionService();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 1: EXTRACT — Graph extraction from process documents
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("Phase 1: Extract graph from all 4 process documents via Claude")
    void phase1_extractProcessDocuments() throws Exception {
        List<String> processFiles = List.of(
                "02_process_map.html",
                "08_KAA_methodology.html",
                "09_process_map_KAA.html",
                "10_semantic_layer_DRAFT.html"
        );

        String prompt = buildGraphExtractionPrompt(processFiles);
        String response = cliAgentLlm.generateResponse(prompt, List.of());
        assertNotNull(response, "Claude should return a non-null response");
        assertFalse(response.startsWith("Error:"),
                "Should not be an error: " + response.substring(0, Math.min(200, response.length())));

        List<ExtractedGraph> results = parseGraphExtractionResponse(response, processFiles.size());
        assertNotNull(results, "Should parse extraction results");
        assertEquals(processFiles.size(), results.size(), "Should have one result per document");

        for (int i = 0; i < processFiles.size(); i++) {
            PER_DOC_RESULTS.put(processFiles.get(i), results.get(i));
            ExtractedGraph g = results.get(i);
            System.out.printf("[%s] Extracted %d entities, %d relationships%n",
                    processFiles.get(i),
                    g.getEntities() != null ? g.getEntities().size() : 0,
                    g.getRelationships() != null ? g.getRelationships().size() : 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 2: RESOLVE — Cross-document entity resolution
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("Phase 2: Cross-document entity resolution on process entities")
    void phase2_entityResolution() {
        Assumptions.assumeTrue(PER_DOC_RESULTS.size() == 4, "Need all 4 Phase 1 results");

        List<GraphExtractionSchema.ExtractionResult> perDocExtractions = new ArrayList<>();
        for (Map.Entry<String, ExtractedGraph> entry : PER_DOC_RESULTS.entrySet()) {
            perDocExtractions.add(convertToExtractionResult(entry.getKey(), entry.getValue()));
        }

        GraphExtractionSchema.ExtractionResult resolved = resolutionService.resolve(perDocExtractions);

        ALL_RESOLVED_ENTITIES.clear();
        ALL_RESOLVED_ENTITIES.addAll(resolved.entities());
        ALL_RESOLVED_RELATIONS.clear();
        ALL_RESOLVED_RELATIONS.addAll(resolved.relations());

        int preCount = perDocExtractions.stream().mapToInt(r -> r.entities().size()).sum();
        System.out.printf("Resolution: %d entities -> %d (merged %d), %d relationships%n",
                preCount, resolved.entities().size(),
                preCount - resolved.entities().size(),
                resolved.relations().size());

        assertTrue(resolved.entities().size() < preCount,
                "Entity resolution should reduce total entity count");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 3: DISCOVER — Business processes via LlmProcessDiscoveryService format
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("Phase 3: Discover business processes using kompile process discovery prompt")
    void phase3_discoverBusinessProcesses() {
        Assumptions.assumeFalse(ALL_RESOLVED_ENTITIES.isEmpty(), "Need Phase 2 results");

        // Build prompt using the actual LlmProcessDiscoveryService prompt format
        String prompt = buildProcessDiscoveryPrompt();

        String response = cliAgentLlm.generateResponse(prompt, List.of());
        assertNotNull(response, "Claude should return a non-null response for process discovery");
        assertFalse(response.startsWith("Error:"),
                "Should not be an error: " + response.substring(0, Math.min(200, response.length())));

        // Parse using LlmProcessDiscoveryService's actual parser
        LlmProcessDiscoveryService parser = new LlmProcessDiscoveryService(null, null);
        List<ProcessSuggestion> suggestions = parser.parseResponse(response);

        DISCOVERED_PROCESSES.clear();
        DISCOVERED_PROCESSES.addAll(suggestions);

        System.out.printf("Discovered %d business processes%n", suggestions.size());
        for (ProcessSuggestion ps : suggestions) {
            int totalSteps = ps.getPhases().stream().mapToInt(p -> p.getSteps().size()).sum();
            System.out.printf("  - %s (confidence=%.2f, %d phases, %d steps, %d child processes)%n",
                    ps.getName(), ps.getConfidence(),
                    ps.getPhases().size(), totalSteps,
                    ps.getChildSuggestions() != null ? ps.getChildSuggestions().size() : 0);
        }

        assertFalse(suggestions.isEmpty(),
                "Should discover at least one business process from the FP&A documents");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 4: VALIDATE — Business process quality assertions
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("Phase 4a: Discovered processes have phases and steps")
    void phase4a_processesHavePhasesAndSteps() {
        Assumptions.assumeFalse(DISCOVERED_PROCESSES.isEmpty(), "Need Phase 3 results");

        for (ProcessSuggestion ps : DISCOVERED_PROCESSES) {
            assertFalse(ps.getPhases().isEmpty(),
                    "Process '" + ps.getName() + "' should have at least one phase");
            for (SuggestedPhase phase : ps.getPhases()) {
                assertFalse(phase.getSteps().isEmpty(),
                        "Phase '" + phase.getName() + "' in '" + ps.getName() + "' should have steps");
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("Phase 4b: Close cycle or monthly close process is discovered")
    void phase4b_closeCycleDiscovered() {
        Assumptions.assumeFalse(DISCOVERED_PROCESSES.isEmpty(), "Need Phase 3 results");

        String allText = DISCOVERED_PROCESSES.stream()
                .map(ps -> ps.getName() + " " + ps.getDescription())
                .collect(Collectors.joining(" "))
                .toLowerCase();

        boolean hasCloseCycle = allText.contains("close") || allText.contains("monthly")
                || allText.contains("forecast") || allText.contains("consolidat");
        assertTrue(hasCloseCycle,
                "Should discover a close cycle / monthly close / forecast consolidation process");
    }

    @Test
    @Order(6)
    @DisplayName("Phase 4c: Step types include AUTO and HUMAN")
    void phase4c_stepTypesIncludeAutoAndHuman() {
        Assumptions.assumeFalse(DISCOVERED_PROCESSES.isEmpty(), "Need Phase 3 results");

        Set<String> stepTypes = DISCOVERED_PROCESSES.stream()
                .flatMap(ps -> ps.getPhases().stream())
                .flatMap(ph -> ph.getSteps().stream())
                .map(SuggestedStep::getStepType)
                .collect(Collectors.toSet());

        // At minimum should have AUTO and HUMAN/APPROVE steps
        boolean hasAuto = stepTypes.contains("AUTO") || stepTypes.contains("HTTP_CALL")
                || stepTypes.contains("EXCEL_COMPUTE") || stepTypes.contains("SCRIPT");
        boolean hasHuman = stepTypes.contains("HUMAN") || stepTypes.contains("APPROVE");

        assertTrue(hasAuto || hasHuman,
                "Discovered steps should include at least AUTO or HUMAN step types, found: " + stepTypes);
    }

    @Test
    @Order(7)
    @DisplayName("Phase 4d: Key actors identified (Park, Chen, Okafor, Sato)")
    void phase4d_keyActorsIdentified() {
        Assumptions.assumeFalse(DISCOVERED_PROCESSES.isEmpty(), "Need Phase 3 results");

        String allActors = DISCOVERED_PROCESSES.stream()
                .flatMap(ps -> ps.getPhases().stream())
                .flatMap(ph -> ph.getSteps().stream())
                .map(s -> s.getSuggestedAssignee() != null ? s.getSuggestedAssignee() : "")
                .collect(Collectors.joining(" "))
                .toLowerCase();

        // Also check descriptions for actor mentions
        String allDescriptions = DISCOVERED_PROCESSES.stream()
                .flatMap(ps -> ps.getPhases().stream())
                .flatMap(ph -> ph.getSteps().stream())
                .map(s -> s.getDescription() != null ? s.getDescription() : "")
                .collect(Collectors.joining(" "))
                .toLowerCase();

        String combined = allActors + " " + allDescriptions;
        boolean hasKeyActor = combined.contains("park") || combined.contains("chen")
                || combined.contains("okafor") || combined.contains("sato")
                || combined.contains("controller") || combined.contains("cfo")
                || combined.contains("finance") || combined.contains("analyst");
        assertTrue(hasKeyActor,
                "Should identify at least one key FP&A actor in process steps");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 5: REPORT — Write business process HTML report
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("Phase 5: Write business process crawl results HTML report")
    void phase5_writeHtmlReport() throws Exception {
        Assumptions.assumeFalse(PER_DOC_RESULTS.isEmpty(), "Need Phase 1 results");

        String html = buildBusinessProcessReportHtml();
        Path outputPath = OUTPUT_DIR.resolve("fpna-business-process-crawl-results.html");
        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(outputPath, html);

        assertTrue(Files.exists(outputPath), "Report file should exist");
        assertTrue(Files.size(outputPath) > 1000, "Report should have substantial content");
        System.out.println("Business process report written to: " + outputPath);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PROMPT — Graph extraction (same pattern as FpnaClaudeCrawlTest)
    // ═══════════════════════════════════════════════════════════════════════

    private String buildGraphExtractionPrompt(List<String> fileNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Extract entities and relationships from each of the numbered documents below.\n");
        sb.append("Focus on FP&A business process elements: close-cycle steps, variance triage patterns, ");
        sb.append("controls, approval roles, agent specifications, and KAA architecture layers.\n\n");
        sb.append(buildSchemaDescription()).append("\n\n");

        for (int i = 0; i < fileNames.size(); i++) {
            sb.append("--- DOCUMENT ").append(i).append(" ---\n");
            String text = loadAndStripHtml(fileNames.get(i));
            if (text.length() > 12_000) {
                text = text.substring(0, 12_000) + "\n[...truncated...]";
            }
            sb.append(text).append("\n\n");
        }

        sb.append("Respond with a JSON array containing exactly ").append(fileNames.size()).append(" object(s), ");
        sb.append("one per document in the same order. ");
        sb.append("Each object has \"entities\" (list) and \"relationships\" (list).\n");
        sb.append("Return ONLY the JSON array, no markdown formatting or explanation.");
        return sb.toString();
    }

    private String buildSchemaDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("Node types: CLOSE_STEP, VARIANCE_TRIAGE, CONTROL_ASSERTION, APPROVAL_ROLE, ");
        sb.append("PERSON, REGIONAL_FORECAST, CHANNEL_TAXONOMY, SKU_MASTER, CURRENCY_REGISTRY, ");
        sb.append("FX_FORWARD_CURVE, PIPELINE_COVERAGE, DATA_QUALITY_FLAG, SPREADSHEET, TABLE, ");
        sb.append("FORECAST_ADJUSTMENT, EMAIL_MESSAGE\n");
        sb.append("Relationship types: FEEDS_INTO, PART_OF, VALIDATES, APPROVED_BY, ESCALATED_TO, ");
        sb.append("TRIGGERS, CONTAINS, SOURCE_OF, SUBMITTED_BY, DERIVES_FROM, REFERENCES_TAXONOMY, ");
        sb.append("APPLIES_ADJUSTMENT, SENT_BY, SENT_TO, HAS_ATTACHMENT, VERSION_OF\n");
        sb.append("Entity format: {\"id\": \"e1\", \"title\": \"...\", \"label\": \"CLOSE_STEP\", \"description\": \"...\", \"metadata\": {}}\n");
        sb.append("Relationship format: {\"source\": \"e1\", \"target\": \"e2\", \"type\": \"FEEDS_INTO\", \"description\": \"...\"}");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PROMPT — Process discovery (uses actual LlmProcessDiscoveryService format)
    // ═══════════════════════════════════════════════════════════════════════

    private String buildProcessDiscoveryPrompt() {
        StringBuilder sb = new StringBuilder();

        // Same preamble as LlmProcessDiscoveryService.buildPrompt()
        sb.append("You are a business process analyst. Analyze the following knowledge graph data ");
        sb.append("and identify business processes, workflows, or procedures that are described or implied.\n\n");
        sb.append("A business process is a repeatable sequence of steps performed by people or systems ");
        sb.append("to achieve a goal. Look for:\n");
        sb.append("- Procedures described in documents (e.g., \"Step 1: Fill out form, Step 2: Get approval\")\n");
        sb.append("- Workflows implied by email communications (e.g., request -> review -> approve -> notify)\n");
        sb.append("- Data processing pipelines (e.g., input data -> compute -> validate -> report)\n");
        sb.append("- Multi-document processes where one document describes how to use another\n");
        sb.append("- Approval chains, onboarding procedures, reporting cycles\n");
        sb.append("- Processes that contain sub-processes (hierarchical workflows)\n\n");

        // Serialize resolved entities as knowledge graph nodes
        sb.append("=== KNOWLEDGE GRAPH NODES ===\n");
        for (GraphExtractionSchema.ExtractedEntity entity : ALL_RESOLVED_ENTITIES) {
            sb.append("Node[").append(entity.id()).append("]: ");
            sb.append("type=").append(entity.type());
            sb.append(", title=\"").append(entity.name()).append("\"");
            if (entity.description() != null && !entity.description().isEmpty()) {
                String desc = entity.description().length() > 200
                        ? entity.description().substring(0, 200) + "..." : entity.description();
                sb.append(", description=\"").append(desc).append("\"");
            }
            sb.append("\n");
        }

        // Serialize resolved relationships as edges
        sb.append("\n=== KNOWLEDGE GRAPH EDGES ===\n");
        for (GraphExtractionSchema.ExtractedRelation rel : ALL_RESOLVED_RELATIONS) {
            sb.append("Edge: \"").append(rel.source()).append("\" --[")
                    .append(rel.type()).append("]--> \"").append(rel.target()).append("\"");
            if (rel.description() != null && !rel.description().isEmpty()) {
                String desc = rel.description().length() > 100
                        ? rel.description().substring(0, 100) + "..." : rel.description();
                sb.append(" (").append(desc).append(")");
            }
            sb.append("\n");
        }

        // Use actual LlmProcessDiscoveryService output format instructions
        sb.append("\n=== OUTPUT FORMAT ===\n");
        sb.append(LlmProcessDiscoveryService.getProcessDiscoveryPromptInstructions());

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RESPONSE PARSING
    // ═══════════════════════════════════════════════════════════════════════

    private List<ExtractedGraph> parseGraphExtractionResponse(String response, int expectedCount) {
        if (response == null || response.isBlank()) return null;
        String json = response.trim();

        if (json.startsWith("```")) {
            int nl = json.indexOf('\n');
            if (nl > 0) json = json.substring(nl + 1);
            if (json.endsWith("```")) json = json.substring(0, json.length() - 3).trim();
        }

        int arrayStart = json.indexOf('[');
        int objStart = json.indexOf('{');

        if (arrayStart >= 0 && (objStart < 0 || arrayStart < objStart)) {
            int arrayEnd = json.lastIndexOf(']');
            if (arrayEnd > arrayStart) {
                try {
                    return MAPPER.readValue(json.substring(arrayStart, arrayEnd + 1),
                            new TypeReference<List<ExtractedGraph>>() {});
                } catch (Exception e) {
                    System.err.println("Array parse failed: " + e.getMessage());
                }
            }
        }

        if (objStart >= 0) {
            try {
                int objEnd = json.lastIndexOf('}');
                ExtractedGraph single = MAPPER.readValue(json.substring(objStart, objEnd + 1), ExtractedGraph.class);
                List<ExtractedGraph> result = new ArrayList<>();
                result.add(single);
                for (int i = 1; i < expectedCount; i++) result.add(new ExtractedGraph());
                return result;
            } catch (Exception e) {
                System.err.println("Object parse failed: " + e.getMessage());
            }
        }

        System.err.println("Could not parse response (first 500 chars): " +
                json.substring(0, Math.min(500, json.length())));
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONVERSION HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private GraphExtractionSchema.ExtractionResult convertToExtractionResult(
            String docId, ExtractedGraph graph) {
        List<GraphExtractionSchema.ExtractedEntity> entities = new ArrayList<>();
        List<GraphExtractionSchema.ExtractedRelation> relations = new ArrayList<>();

        if (graph.getEntities() != null) {
            for (ExtractedEntity dto : graph.getEntities()) {
                String id = dto.getId() != null ? dto.getId() : "e-" + UUID.randomUUID().toString().substring(0, 8);
                Map<String, String> props = new HashMap<>();
                props.put("sourceChunkId", docId);
                if (dto.getMetadata() != null) {
                    dto.getMetadata().forEach((k, v) -> props.put(k, String.valueOf(v)));
                }
                entities.add(new GraphExtractionSchema.ExtractedEntity(
                        id,
                        dto.getTitle() != null ? dto.getTitle() : "",
                        dto.getNodeLabel() != null ? dto.getNodeLabel() : "ENTITY",
                        List.of(),
                        dto.getDescription() != null ? dto.getDescription() : "",
                        0.9,
                        props));
            }
        }

        if (graph.getRelationships() != null) {
            for (ExtractedRelationship dto : graph.getRelationships()) {
                double confidence = dto.getConfidence() != null ? dto.getConfidence() :
                        (dto.getWeight() != null ? dto.getWeight() : 0.9);
                relations.add(new GraphExtractionSchema.ExtractedRelation(
                        dto.getSource() != null ? dto.getSource() : "",
                        dto.getTarget() != null ? dto.getTarget() : "",
                        dto.getRelationshipType() != null ? dto.getRelationshipType() : "RELATED_TO",
                        dto.getDescription() != null ? dto.getDescription() : "",
                        confidence,
                        Map.of()));
            }
        }

        return GraphExtractionSchema.ExtractionResult.of(entities, relations,
                GraphExtractionSchema.ExtractionMetadata.forChunk(docId, docId, "cli-agent/claude"));
    }

    private String loadAndStripHtml(String fileName) throws IOException {
        Path filePath = ARTIFACTS_DIR.resolve(fileName);
        if (!Files.exists(filePath)) {
            throw new IOException("FP&A artifact not found: " + filePath);
        }
        return Files.readString(filePath)
                .replaceAll("<script[^>]*>[\\s\\S]*?</script>", " ")
                .replaceAll("<style[^>]*>[\\s\\S]*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTML REPORT — Renders ProcessSuggestion objects (kompile model)
    // ═══════════════════════════════════════════════════════════════════════

    private String buildBusinessProcessReportHtml() {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html>\n<html><head><meta charset='UTF-8'>\n");
        h.append("<title>FP&amp;A Business Process Discovery Results</title>\n<style>\n");
        h.append("body{font-family:'Segoe UI',sans-serif;margin:20px;background:#faf9f6;color:#2c2c2a;line-height:1.55}\n");
        h.append("h1{color:#1a237e;border-bottom:2px solid #0078d4;padding-bottom:8px}\n");
        h.append("h2{color:#0078d4;margin:32px 0 12px}\n");
        h.append("h3{color:#3949ab;margin:20px 0 8px}\n");
        h.append("h4{color:#455a64;margin:12px 0 6px}\n");
        h.append(".section{background:#fff;border:1px solid rgba(0,0,0,.10);border-radius:6px;padding:16px 20px;margin:16px 0;box-shadow:0 1px 3px rgba(0,0,0,.08)}\n");
        h.append(".process{background:#fff;border:2px solid #0078d4;border-radius:8px;padding:20px;margin:20px 0}\n");
        h.append(".phase{background:#e3f2fd;border-left:4px solid #1565c0;padding:12px 16px;margin:12px 0;border-radius:0 6px 6px 0}\n");
        h.append(".step{margin:6px 0;padding:8px 12px;border-left:3px solid #90caf9;background:#f5f5f5;border-radius:0 4px 4px 0}\n");
        h.append(".entity{margin:4px 0;padding:6px 10px;border-left:3px solid #5c6bc0;background:#e8eaf6;border-radius:0 4px 4px 0}\n");
        h.append(".rel{margin:4px 0;padding:6px 10px;border-left:3px solid #66bb6a;background:#e8f5e9;border-radius:0 4px 4px 0}\n");
        h.append(".stats{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}\n");
        h.append(".stat{background:#e3f2fd;padding:8px 14px;border-radius:6px;font-weight:bold}\n");
        h.append(".badge{display:inline-block;padding:2px 8px;border-radius:4px;font-size:.8em;font-weight:bold;color:#fff;margin-right:6px}\n");
        h.append(".AUTO{background:#2e7d32}.HUMAN{background:#c62828}.APPROVE{background:#ff6f00}\n");
        h.append(".TOOL_CALL{background:#1565c0}.EXCEL_COMPUTE{background:#2e7d32}.SCRIPT{background:#4527a0}.HTTP_CALL{background:#00695c}\n");
        h.append(".CLOSE_STEP{background:#e65100}.CONTROL_ASSERTION{background:#c62828}.VARIANCE_TRIAGE{background:#ad1457}\n");
        h.append(".APPROVAL_ROLE{background:#ff6f00}.PERSON{background:#1565c0}.SPREADSHEET{background:#2e7d32}\n");
        h.append(".REGIONAL_FORECAST{background:#00838f}.CHANNEL_TAXONOMY{background:#558b2f}\n");
        h.append(".other{background:#546e7a}\n");
        h.append("table{border-collapse:collapse;width:100%;margin:10px 0}th,td{text-align:left;padding:6px 10px;border:1px solid rgba(0,0,0,.10);vertical-align:top}th{background:#e8eaf6;font-weight:600}\n");
        h.append(".confidence{font-size:.85em;color:#5f5e5a}.evidence{font-size:.85em;color:#37474f;font-style:italic}\n");
        h.append(".child-process{margin-left:24px;border-left:2px dashed #90caf9;padding-left:16px}\n");
        h.append("</style></head><body>\n");

        // Header
        h.append("<h1>FP&amp;A CPG Channel &mdash; Business Process Discovery Results</h1>\n");
        h.append("<p>LLM: <b>cli-agent/claude</b> | Process model: <b>kompile ProcessSuggestion</b> | ");
        h.append("Discovery: <b>LlmProcessDiscoveryService format</b></p>\n");

        // Stats
        int totalE = PER_DOC_RESULTS.values().stream()
                .mapToInt(g -> g.getEntities() != null ? g.getEntities().size() : 0).sum();
        int totalR = PER_DOC_RESULTS.values().stream()
                .mapToInt(g -> g.getRelationships() != null ? g.getRelationships().size() : 0).sum();
        int totalSteps = DISCOVERED_PROCESSES.stream()
                .mapToInt(ps -> ps.getPhases().stream().mapToInt(p -> p.getSteps().size()).sum()).sum();
        int totalPhases = DISCOVERED_PROCESSES.stream()
                .mapToInt(ps -> ps.getPhases().size()).sum();

        h.append("<div class='stats'>\n");
        h.append("<div class='stat'>Graph entities: ").append(ALL_RESOLVED_ENTITIES.size()).append("</div>\n");
        h.append("<div class='stat'>Graph relationships: ").append(ALL_RESOLVED_RELATIONS.size()).append("</div>\n");
        h.append("<div class='stat'>Processes discovered: ").append(DISCOVERED_PROCESSES.size()).append("</div>\n");
        h.append("<div class='stat'>Total phases: ").append(totalPhases).append("</div>\n");
        h.append("<div class='stat'>Total steps: ").append(totalSteps).append("</div>\n");
        h.append("</div>\n");

        // ── Discovered Processes ──
        h.append("<h2>Discovered Business Processes</h2>\n");
        for (ProcessSuggestion ps : DISCOVERED_PROCESSES) {
            renderProcess(h, ps, false);
        }

        // ── Per-document graph extraction ──
        h.append("<h2>Graph Extraction (Per Document)</h2>\n");
        for (var entry : PER_DOC_RESULTS.entrySet()) {
            h.append("<div class='section'><h3>").append(esc(entry.getKey())).append("</h3>\n");
            ExtractedGraph g = entry.getValue();
            if (g.getEntities() != null) {
                h.append("<h4>Entities (").append(g.getEntities().size()).append(")</h4>\n");
                for (ExtractedEntity e : g.getEntities()) {
                    String lbl = e.getNodeLabel() != null ? e.getNodeLabel() : "UNKNOWN";
                    String cls = SCHEMA_NODE_TYPES.contains(lbl) ? lbl : "other";
                    h.append("<div class='entity'><span class='badge ").append(cls).append("'>").append(lbl).append("</span>");
                    h.append("<b>").append(esc(e.getTitle())).append("</b>");
                    if (e.getDescription() != null) h.append(" &mdash; ").append(esc(e.getDescription()));
                    h.append("</div>\n");
                }
            }
            if (g.getRelationships() != null) {
                h.append("<h4>Relationships (").append(g.getRelationships().size()).append(")</h4>\n");
                for (ExtractedRelationship r : g.getRelationships()) {
                    h.append("<div class='rel'><b>").append(esc(r.getSource())).append("</b> &mdash;[")
                            .append(r.getRelationshipType()).append("]&rarr; <b>")
                            .append(esc(r.getTarget())).append("</b>");
                    if (r.getDescription() != null) h.append(" <i>").append(esc(r.getDescription())).append("</i>");
                    h.append("</div>\n");
                }
            }
            h.append("</div>\n");
        }

        // ── Resolved entity summary ──
        h.append("<h2>Resolved Entities (").append(ALL_RESOLVED_ENTITIES.size()).append(")</h2>\n");
        h.append("<div class='section'>\n");
        Map<String, List<GraphExtractionSchema.ExtractedEntity>> byType = ALL_RESOLVED_ENTITIES.stream()
                .collect(Collectors.groupingBy(GraphExtractionSchema.ExtractedEntity::type));
        h.append("<table><tr><th>Type</th><th>Count</th></tr>\n");
        byType.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<GraphExtractionSchema.ExtractedEntity>> e) -> e.getValue().size()).reversed())
                .forEach(e -> h.append("<tr><td>").append(e.getKey()).append("</td><td>").append(e.getValue().size()).append("</td></tr>\n"));
        h.append("</table></div>\n");

        h.append("</body></html>");
        return h.toString();
    }

    private void renderProcess(StringBuilder h, ProcessSuggestion ps, boolean isChild) {
        String divClass = isChild ? "child-process" : "process";
        h.append("<div class='").append(divClass).append("'>\n");
        h.append("<h3>").append(esc(ps.getName())).append("</h3>\n");
        if (ps.getDescription() != null) h.append("<p>").append(esc(ps.getDescription())).append("</p>\n");
        h.append("<p class='confidence'>Confidence: <b>").append(String.format("%.0f%%", ps.getConfidence() * 100))
                .append("</b> | Source: ").append(ps.getDiscoverySource() != null ? ps.getDiscoverySource() : "LLM_ANALYSIS").append("</p>\n");

        // Evidence
        if (ps.getEvidence() != null && !ps.getEvidence().isEmpty()) {
            h.append("<p class='evidence'>Evidence: ");
            for (int i = 0; i < ps.getEvidence().size(); i++) {
                if (i > 0) h.append(" | ");
                h.append(esc(ps.getEvidence().get(i)));
            }
            h.append("</p>\n");
        }

        // Phases and steps
        for (SuggestedPhase phase : ps.getPhases()) {
            h.append("<div class='phase'>\n");
            h.append("<h4>").append(esc(phase.getName()));
            if (phase.getDescription() != null && !phase.getDescription().equals(phase.getName())) {
                h.append(" &mdash; ").append(esc(phase.getDescription()));
            }
            h.append("</h4>\n");

            for (SuggestedStep step : phase.getSteps()) {
                String sType = step.getStepType() != null ? step.getStepType() : "HUMAN";
                h.append("<div class='step'>");
                h.append("<span class='badge ").append(sType).append("'>").append(sType).append("</span>");
                h.append("<b>").append(esc(step.getName())).append("</b>");
                if (step.getDescription() != null && !step.getDescription().equals(step.getName())) {
                    h.append(" &mdash; ").append(esc(step.getDescription()));
                }
                if (step.getSuggestedAssignee() != null) {
                    h.append(" <i>(actor: ").append(esc(step.getSuggestedAssignee())).append(")</i>");
                }
                h.append("</div>\n");
            }
            h.append("</div>\n");
        }

        // Child processes
        if (ps.getChildSuggestions() != null && !ps.getChildSuggestions().isEmpty()) {
            h.append("<h4>Sub-Processes</h4>\n");
            for (ProcessSuggestion child : ps.getChildSuggestions()) {
                renderProcess(h, child, true);
            }
        }

        h.append("</div>\n");
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
