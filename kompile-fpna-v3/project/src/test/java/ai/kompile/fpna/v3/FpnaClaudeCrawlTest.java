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
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO.ExtractedGraph;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO.ExtractedEntity;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO.ExtractedRelationship;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;
import ai.kompile.llm.cli.CliAgentLanguageModelImpl;
import ai.kompile.llm.cli.CliAgentLlmConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the actual crawl graph extraction pipeline against the FP&A documents
 * using Claude Code subprocess management via kompile's {@link CliAgentLanguageModelImpl}.
 *
 * <p>Pipeline mirrors what {@code MatrixGraphConstructor} does on the backend:</p>
 * <ol>
 *   <li>Load source HTML documents from FP&A workflow artifacts</li>
 *   <li>Build extraction prompts using the fpna-cpg-channel-v1 schema</li>
 *   <li>Call Claude via {@link CliAgentLanguageModelImpl} subprocess management</li>
 *   <li>Parse JSON extraction results (same format as MatrixGraphConstructor)</li>
 *   <li>Run {@link EntityResolutionService} for cross-document deduplication</li>
 *   <li>Write crawl results to HTML report</li>
 * </ol>
 *
 * Requires: {@code claude} CLI installed and available on PATH.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FpnaClaudeCrawlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path ARTIFACTS_DIR = Paths.get(
            System.getProperty("user.home"),
            "Documents/GitHub/kompile/FP&A workflow artifacts 2026-05");

    private static final Path OUTPUT_DIR = Paths.get(
            System.getProperty("user.home"),
            "Documents/GitHub/kompile/kompile-fpna-v3/project/src/test/resources");

    // Schema types from fpna-cpg-channel-v1
    private static final Set<String> SCHEMA_NODE_TYPES = Set.of(
            "CHANNEL_TAXONOMY", "SKU_MASTER", "CURRENCY_REGISTRY", "CHART_OF_ACCOUNTS",
            "FX_FORWARD_CURVE", "REGIONAL_FORECAST", "FORECAST_ADJUSTMENT", "BANK_TRANSACTION",
            "VARIANCE_TRIAGE", "DATA_QUALITY_FLAG", "CONTROL_ASSERTION", "CLOSE_STEP",
            "PIPELINE_COVERAGE", "CASH_CONVERSION_CYCLE", "FREE_CASH_FLOW_MARGIN",
            "ROIC_DECOMPOSITION", "APPROVAL_ROLE", "PERSON", "TABLE", "SPREADSHEET", "EMAIL_MESSAGE"
    );

    private static final Set<String> SCHEMA_REL_TYPES = Set.of(
            "CONTAINS", "PART_OF", "VALIDATES", "SOURCE_OF", "ATTACHMENT_OF", "VERSION_OF",
            "APPROVED_BY", "SUBMITTED_BY", "FEEDS_INTO", "DERIVES_FROM", "REFERENCES_TAXONOMY",
            "APPLIES_ADJUSTMENT", "SENT_BY", "SENT_TO", "HAS_ATTACHMENT", "ESCALATED_TO", "TRIGGERS"
    );

    // Shared state across ordered test methods
    private static CliAgentLanguageModelImpl cliAgentLlm;
    private static final Map<String, ExtractedGraph> PER_DOC_RESULTS = new LinkedHashMap<>();
    private static final List<GraphExtractionSchema.ExtractedEntity> ALL_RESOLVED_ENTITIES = new ArrayList<>();
    private static final List<GraphExtractionSchema.ExtractedRelation> ALL_RESOLVED_RELATIONS = new ArrayList<>();

    private EntityResolutionService resolutionService;

    @BeforeAll
    static void initCliAgent() {
        // Wire up kompile's CLI agent LLM infrastructure
        CliAgentLlmConfigService configService = new CliAgentLlmConfigService();
        configService.initialize();

        // Override config to use claude with appropriate settings for graph extraction
        CliAgentLlmConfigService.CliLlmConfig config = configService.getConfig();
        config.enabled = true;
        config.command = "claude";
        config.skipPermissions = true;
        config.timeoutSeconds = 600; // graph extraction can be slow
        configService.updateConfig(config);

        cliAgentLlm = new CliAgentLanguageModelImpl(configService);

        // Skip all tests if claude is not installed
        Assumptions.assumeTrue(cliAgentLlm.isAvailable(),
                "Claude CLI not available — skipping crawl tests");
    }

    @BeforeEach
    void setUp() {
        resolutionService = new EntityResolutionService();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 1: EXTRACT — Call Claude for each document group
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("Phase 1: Extract entities/relationships from email documents via Claude")
    void phase1_extractEmailDocuments() throws Exception {
        List<String> emailFiles = List.of(
                "06a_email_AMER.html",
                "06b_email_EMEA.html",
                "06c_email_APAC.html"
        );

        String prompt = buildExtractionPrompt(emailFiles);
        String response = cliAgentLlm.generateResponse(prompt, List.of());
        assertNotNull(response, "Claude should return a non-null response");
        assertFalse(response.startsWith("Error:"), "Should not be an error: " + response.substring(0, Math.min(200, response.length())));

        List<ExtractedGraph> results = parseExtractionResponse(response, emailFiles.size());
        assertNotNull(results, "Should parse extraction results from response");
        assertEquals(emailFiles.size(), results.size(), "Should have one result per document");

        for (int i = 0; i < emailFiles.size(); i++) {
            PER_DOC_RESULTS.put(emailFiles.get(i), results.get(i));
            ExtractedGraph graph = results.get(i);
            System.out.printf("[%s] Extracted %d entities, %d relationships%n",
                    emailFiles.get(i),
                    graph.getEntities() != null ? graph.getEntities().size() : 0,
                    graph.getRelationships() != null ? graph.getRelationships().size() : 0);
        }

        // Validate AMER email extraction
        ExtractedGraph amerResult = results.get(0);
        assertNotNull(amerResult.getEntities(), "AMER should have entities");
        assertFalse(amerResult.getEntities().isEmpty(), "AMER entities should not be empty");
        assertTrue(amerResult.getEntities().stream()
                        .anyMatch(e -> "PERSON".equals(e.getNodeLabel())),
                "AMER should extract at least one PERSON");
    }

    @Test
    @Order(2)
    @DisplayName("Phase 1b: Extract entities/relationships from process documents via Claude")
    void phase1b_extractProcessDocuments() throws Exception {
        List<String> processFiles = List.of(
                "02_process_map.html",
                "08_KAA_methodology.html",
                "09_process_map_KAA.html",
                "10_semantic_layer_DRAFT.html"
        );

        String prompt = buildExtractionPrompt(processFiles);
        String response = cliAgentLlm.generateResponse(prompt, List.of());
        assertNotNull(response, "Claude should return a non-null response");
        assertFalse(response.startsWith("Error:"), "Should not be an error: " + response.substring(0, Math.min(200, response.length())));

        List<ExtractedGraph> results = parseExtractionResponse(response, processFiles.size());
        assertNotNull(results, "Should parse extraction results");
        assertEquals(processFiles.size(), results.size(), "Should have one result per document");

        for (int i = 0; i < processFiles.size(); i++) {
            PER_DOC_RESULTS.put(processFiles.get(i), results.get(i));
            ExtractedGraph graph = results.get(i);
            System.out.printf("[%s] Extracted %d entities, %d relationships%n",
                    processFiles.get(i),
                    graph.getEntities() != null ? graph.getEntities().size() : 0,
                    graph.getRelationships() != null ? graph.getRelationships().size() : 0);
        }

        ExtractedGraph processResult = results.get(0);
        assertNotNull(processResult.getEntities(), "Process map should have entities");
    }

    @Test
    @Order(3)
    @DisplayName("Phase 1c: Extract from inbox document via Claude")
    void phase1c_extractInboxDocument() throws Exception {
        List<String> inboxFiles = List.of("06_inbox.html");

        String prompt = buildExtractionPrompt(inboxFiles);
        String response = cliAgentLlm.generateResponse(prompt, List.of());
        assertNotNull(response, "Claude should return a non-null response");
        assertFalse(response.startsWith("Error:"), "Should not be an error");

        List<ExtractedGraph> results = parseExtractionResponse(response, inboxFiles.size());
        assertNotNull(results, "Should parse extraction results");

        PER_DOC_RESULTS.put("06_inbox.html", results.get(0));
        ExtractedGraph graph = results.get(0);
        System.out.printf("[06_inbox.html] Extracted %d entities, %d relationships%n",
                graph.getEntities() != null ? graph.getEntities().size() : 0,
                graph.getRelationships() != null ? graph.getRelationships().size() : 0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 2: RESOLVE — Cross-document entity resolution
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("Phase 2: Cross-document entity resolution merges duplicate persons")
    void phase2_entityResolution() {
        Assumptions.assumeFalse(PER_DOC_RESULTS.isEmpty(), "Need Phase 1 results");

        List<GraphExtractionSchema.ExtractionResult> perDocExtractions = new ArrayList<>();
        for (Map.Entry<String, ExtractedGraph> entry : PER_DOC_RESULTS.entrySet()) {
            perDocExtractions.add(convertToExtractionResult(entry.getKey(), entry.getValue()));
        }

        GraphExtractionSchema.ExtractionResult resolved = resolutionService.resolve(perDocExtractions);

        ALL_RESOLVED_ENTITIES.clear();
        ALL_RESOLVED_ENTITIES.addAll(resolved.entities());
        ALL_RESOLVED_RELATIONS.clear();
        ALL_RESOLVED_RELATIONS.addAll(resolved.relations());

        int preResolutionCount = perDocExtractions.stream().mapToInt(r -> r.entities().size()).sum();
        System.out.printf("Resolution: %d entities → %d (merged %d), %d relationships%n",
                preResolutionCount, resolved.entities().size(),
                preResolutionCount - resolved.entities().size(),
                resolved.relations().size());

        // Mei Chen appears in all 3 email documents → should merge substantially
        long meiCount = resolved.entities().stream()
                .filter(e -> e.name().toLowerCase().contains("mei chen"))
                .count();
        assertTrue(meiCount <= 2,
                "Mei Chen should be substantially merged across documents, got " + meiCount);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 3: VALIDATE — Extraction quality assertions
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("Phase 3a: Extracted entity types conform to fpna-cpg-channel-v1 schema")
    void phase3a_entityTypesConformToSchema() {
        Assumptions.assumeFalse(ALL_RESOLVED_ENTITIES.isEmpty(), "Need Phase 2 results");

        long conforming = ALL_RESOLVED_ENTITIES.stream()
                .filter(e -> SCHEMA_NODE_TYPES.contains(e.type()))
                .count();
        double rate = (double) conforming / ALL_RESOLVED_ENTITIES.size();

        Set<String> nonSchema = ALL_RESOLVED_ENTITIES.stream()
                .map(GraphExtractionSchema.ExtractedEntity::type)
                .filter(t -> !SCHEMA_NODE_TYPES.contains(t))
                .collect(Collectors.toSet());
        if (!nonSchema.isEmpty()) {
            System.out.println("Non-schema entity types (would be filtered by backend): " + nonSchema);
        }

        assertTrue(rate >= 0.5,
                String.format("At least 50%% of entities should conform to schema, got %.1f%%", rate * 100));
    }

    @Test
    @Order(6)
    @DisplayName("Phase 3b: Extracted relationship types conform to schema")
    void phase3b_relationshipTypesConformToSchema() {
        Assumptions.assumeFalse(ALL_RESOLVED_RELATIONS.isEmpty(), "Need Phase 2 results");

        long conforming = ALL_RESOLVED_RELATIONS.stream()
                .filter(r -> SCHEMA_REL_TYPES.contains(r.type()))
                .count();
        double rate = (double) conforming / ALL_RESOLVED_RELATIONS.size();
        assertTrue(rate >= 0.5,
                String.format("At least 50%% of relationships should conform to schema, got %.1f%%", rate * 100));
    }

    @Test
    @Order(7)
    @DisplayName("Phase 3c: Key persons extracted across documents")
    void phase3c_keyPersonsExtracted() {
        Assumptions.assumeFalse(ALL_RESOLVED_ENTITIES.isEmpty(), "Need Phase 2 results");

        Set<String> personNames = ALL_RESOLVED_ENTITIES.stream()
                .filter(e -> "PERSON".equals(e.type()))
                .map(e -> e.name().toLowerCase())
                .collect(Collectors.toSet());

        assertTrue(personNames.stream().anyMatch(n -> n.contains("chen")),
                "Should extract a Chen (Sarah or Mei)");
    }

    @Test
    @Order(8)
    @DisplayName("Phase 3d: Email relationships exist")
    void phase3d_emailRelationshipsExist() {
        Assumptions.assumeFalse(ALL_RESOLVED_RELATIONS.isEmpty(), "Need Phase 2 results");

        Set<String> relTypes = ALL_RESOLVED_RELATIONS.stream()
                .map(GraphExtractionSchema.ExtractedRelation::type)
                .collect(Collectors.toSet());

        assertTrue(relTypes.contains("SENT_BY") || relTypes.contains("SENT_TO")
                        || relTypes.contains("HAS_ATTACHMENT"),
                "Should have email-related relationships (SENT_BY, SENT_TO, or HAS_ATTACHMENT)");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 4: REPORT — Write HTML crawl report
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(9)
    @DisplayName("Phase 4: Write crawl results HTML report")
    void phase4_writeHtmlReport() throws Exception {
        Assumptions.assumeFalse(PER_DOC_RESULTS.isEmpty(), "Need Phase 1 results");

        String html = buildCrawlReportHtml();
        Path outputPath = OUTPUT_DIR.resolve("fpna-claude-crawl-results.html");
        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(outputPath, html);

        assertTrue(Files.exists(outputPath), "Report file should exist");
        assertTrue(Files.size(outputPath) > 1000, "Report should have substantial content");
        System.out.println("Crawl report written to: " + outputPath);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PROMPT — mirrors MatrixGraphConstructor.createMultiDocExtractionPrompt()
    // ═══════════════════════════════════════════════════════════════════════

    private String buildExtractionPrompt(List<String> fileNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Extract entities and relationships from each of the numbered documents below.\n");
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
        sb.append("Example for 2 documents: [{\"entities\": [{\"id\": \"e1\", \"title\": \"John\", \"label\": \"PERSON\", ");
        sb.append("\"description\": \"A person\"}], ");
        sb.append("\"relationships\": [{\"source\": \"e1\", \"target\": \"e2\", \"type\": \"WORKS_AT\", ");
        sb.append("\"description\": \"John works at Acme\"}]}, ");
        sb.append("{\"entities\": [...], \"relationships\": [...]}]\n");
        sb.append("Return ONLY the JSON array, no markdown formatting or explanation.");
        return sb.toString();
    }

    private String buildSchemaDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("The entities must conform to the following node types:\n");
        Map<String, String> nodes = new LinkedHashMap<>();
        nodes.put("PERSON", "A person mentioned in the document");
        nodes.put("EMAIL_MESSAGE", "An email or message");
        nodes.put("SPREADSHEET", "A spreadsheet file (.xlsx, .csv, etc.)");
        nodes.put("TABLE", "A data table within a spreadsheet or document");
        nodes.put("REGIONAL_FORECAST", "A regional financial forecast");
        nodes.put("SKU_MASTER", "A product SKU identifier");
        nodes.put("CHANNEL_TAXONOMY", "A sales channel classification (DTC, Wholesale, Marketplace)");
        nodes.put("CURRENCY_REGISTRY", "A currency (GBP, EUR, JPY, USD)");
        nodes.put("CHART_OF_ACCOUNTS", "An accounting chart of accounts");
        nodes.put("FX_FORWARD_CURVE", "Foreign exchange forward rate curve");
        nodes.put("FORECAST_ADJUSTMENT", "An adjustment to a forecast");
        nodes.put("BANK_TRANSACTION", "A banking transaction");
        nodes.put("VARIANCE_TRIAGE", "A variance triage pattern (SKU misentry, channel mismatch, etc.)");
        nodes.put("DATA_QUALITY_FLAG", "A data quality issue flag");
        nodes.put("CONTROL_ASSERTION", "An internal control (SOX, data quality, etc.)");
        nodes.put("CLOSE_STEP", "A step in the monthly close cycle");
        nodes.put("PIPELINE_COVERAGE", "Sales pipeline coverage metrics");
        nodes.put("CASH_CONVERSION_CYCLE", "Cash conversion cycle metrics");
        nodes.put("FREE_CASH_FLOW_MARGIN", "Free cash flow margin metrics");
        nodes.put("ROIC_DECOMPOSITION", "Return on invested capital decomposition");
        nodes.put("APPROVAL_ROLE", "An approval role in a business process");
        for (var e : nodes.entrySet()) {
            sb.append("- Label: ").append(e.getKey()).append(", Description: ").append(e.getValue()).append("\n");
        }

        sb.append("\nThe relationships must conform to the following types:\n");
        Map<String, String> rels = new LinkedHashMap<>();
        rels.put("CONTAINS", "Parent entity contains child entity");
        rels.put("PART_OF", "Entity is part of a larger entity");
        rels.put("VALIDATES", "A control validates a step or assertion");
        rels.put("SOURCE_OF", "Entity is the data source for another");
        rels.put("ATTACHMENT_OF", "File is an attachment of another entity");
        rels.put("VERSION_OF", "New version of a previous document");
        rels.put("APPROVED_BY", "Entity approved by a person or role");
        rels.put("SUBMITTED_BY", "Entity submitted by a person");
        rels.put("FEEDS_INTO", "Output of one step feeds into the next");
        rels.put("DERIVES_FROM", "Entity derived from another source");
        rels.put("REFERENCES_TAXONOMY", "Entity references a taxonomy classification");
        rels.put("APPLIES_ADJUSTMENT", "Adjustment applied to a forecast/value");
        rels.put("SENT_BY", "Message sent by a person");
        rels.put("SENT_TO", "Message sent to a person");
        rels.put("HAS_ATTACHMENT", "Message has a file attachment");
        rels.put("ESCALATED_TO", "Issue escalated to a person or role");
        rels.put("TRIGGERS", "Event or flag triggers an action");
        for (var e : rels.entrySet()) {
            sb.append("- Type: ").append(e.getKey()).append(", Description: ").append(e.getValue()).append("\n");
        }

        sb.append("\nFor each entity, provide:\n");
        sb.append("- \"id\": a unique identifier (e.g., \"e1\", \"e2\")\n");
        sb.append("- \"title\": the primary name\n");
        sb.append("- \"label\": the node label from the schema\n");
        sb.append("- \"description\": a short description\n");
        sb.append("- \"metadata\": additional properties as key-value pairs\n");
        sb.append("\nFor each relationship, provide:\n");
        sb.append("- \"source\": the source entity id\n");
        sb.append("- \"target\": the target entity id\n");
        sb.append("- \"type\": the relationship type from the schema\n");
        sb.append("- \"description\": how they are related\n");
        sb.append("- \"weight\": optional strength (0.0 to 1.0)\n");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RESPONSE PARSING — mirrors MatrixGraphConstructor.parseMultiDocResponse()
    // ═══════════════════════════════════════════════════════════════════════

    private List<ExtractedGraph> parseExtractionResponse(String response, int expectedCount) {
        if (response == null || response.isBlank()) return null;
        String json = response.trim();

        // Strip markdown code fences if present
        if (json.startsWith("```")) {
            int nl = json.indexOf('\n');
            if (nl > 0) json = json.substring(nl + 1);
            if (json.endsWith("```")) json = json.substring(0, json.length() - 3).trim();
        }

        int arrayStart = json.indexOf('[');
        int objStart = json.indexOf('{');

        // Array parse (expected format)
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

        // Single-object fallback
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
    // HTML REPORT
    // ═══════════════════════════════════════════════════════════════════════

    private String buildCrawlReportHtml() {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html>\n<html><head><meta charset='UTF-8'>\n");
        h.append("<title>FP&amp;A Claude Crawl Results</title>\n<style>\n");
        h.append("body{font-family:'Segoe UI',sans-serif;margin:20px;background:#f5f5f5}\n");
        h.append("h1{color:#1a237e}h2{color:#283593;border-bottom:2px solid #5c6bc0;padding-bottom:4px}\n");
        h.append("h3{color:#3949ab}.section{background:#fff;border-radius:8px;padding:16px;margin:12px 0;box-shadow:0 1px 3px rgba(0,0,0,.12)}\n");
        h.append(".entity{margin:4px 0;padding:6px 10px;border-left:3px solid #5c6bc0;background:#e8eaf6;border-radius:0 4px 4px 0}\n");
        h.append(".rel{margin:4px 0;padding:6px 10px;border-left:3px solid #66bb6a;background:#e8f5e9;border-radius:0 4px 4px 0}\n");
        h.append(".stats{display:flex;gap:12px;flex-wrap:wrap}.stat{background:#e3f2fd;padding:8px 14px;border-radius:6px;font-weight:bold}\n");
        h.append(".badge{display:inline-block;padding:2px 8px;border-radius:4px;font-size:.8em;font-weight:bold;color:#fff;margin-right:6px}\n");
        h.append(".PERSON{background:#1565c0}.EMAIL_MESSAGE{background:#6a1b9a}.SPREADSHEET{background:#2e7d32}\n");
        h.append(".CLOSE_STEP{background:#e65100}.CONTROL_ASSERTION{background:#c62828}.VARIANCE_TRIAGE{background:#ad1457}\n");
        h.append(".REGIONAL_FORECAST{background:#00838f}.CHANNEL_TAXONOMY{background:#558b2f}.APPROVAL_ROLE{background:#ff6f00}\n");
        h.append(".other{background:#546e7a}\n");
        h.append("table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:6px 10px;border:1px solid #e0e0e0}th{background:#e8eaf6}\n");
        h.append("</style></head><body>\n");

        h.append("<h1>FP&amp;A CPG Channel — Claude Crawl Results</h1>\n");
        h.append("<p>LLM: <b>cli-agent/claude</b> | Schema: <b>fpna-cpg-channel-v1</b> | Docs: <b>")
                .append(PER_DOC_RESULTS.size()).append("</b></p>\n");

        int totalE = PER_DOC_RESULTS.values().stream()
                .mapToInt(g -> g.getEntities() != null ? g.getEntities().size() : 0).sum();
        int totalR = PER_DOC_RESULTS.values().stream()
                .mapToInt(g -> g.getRelationships() != null ? g.getRelationships().size() : 0).sum();

        h.append("<div class='stats'>\n");
        h.append("<div class='stat'>Pre-resolution entities: ").append(totalE).append("</div>\n");
        h.append("<div class='stat'>Pre-resolution relationships: ").append(totalR).append("</div>\n");
        h.append("<div class='stat'>Post-resolution entities: ").append(ALL_RESOLVED_ENTITIES.size()).append("</div>\n");
        h.append("<div class='stat'>Post-resolution relationships: ").append(ALL_RESOLVED_RELATIONS.size()).append("</div>\n");
        h.append("</div>\n");

        // Per-document
        h.append("<h2>Per-Document Extraction</h2>\n");
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
                    if (e.getDescription() != null) h.append(" — ").append(esc(e.getDescription()));
                    h.append("</div>\n");
                }
            }
            if (g.getRelationships() != null) {
                h.append("<h4>Relationships (").append(g.getRelationships().size()).append(")</h4>\n");
                for (ExtractedRelationship r : g.getRelationships()) {
                    h.append("<div class='rel'><b>").append(esc(r.getSource())).append("</b> —[")
                            .append(r.getRelationshipType()).append("]→ <b>")
                            .append(esc(r.getTarget())).append("</b>");
                    if (r.getDescription() != null) h.append(" <i>").append(esc(r.getDescription())).append("</i>");
                    h.append("</div>\n");
                }
            }
            h.append("</div>\n");
        }

        // Resolved entities
        h.append("<h2>Resolved Entities (Post Entity Resolution)</h2>\n<div class='section'>\n");
        Map<String, List<GraphExtractionSchema.ExtractedEntity>> byType = ALL_RESOLVED_ENTITIES.stream()
                .collect(Collectors.groupingBy(GraphExtractionSchema.ExtractedEntity::type));
        h.append("<table><tr><th>Type</th><th>Count</th></tr>\n");
        byType.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<GraphExtractionSchema.ExtractedEntity>> e) -> e.getValue().size()).reversed())
                .forEach(e -> h.append("<tr><td>").append(e.getKey()).append("</td><td>").append(e.getValue().size()).append("</td></tr>\n"));
        h.append("</table>\n");
        for (var te : byType.entrySet()) {
            h.append("<h4>").append(te.getKey()).append(" (").append(te.getValue().size()).append(")</h4>\n");
            for (var e : te.getValue()) {
                String cls = SCHEMA_NODE_TYPES.contains(e.type()) ? e.type() : "other";
                h.append("<div class='entity'><span class='badge ").append(cls).append("'>").append(e.type()).append("</span>");
                h.append("<b>").append(esc(e.name())).append("</b>");
                if (e.description() != null && !e.description().isEmpty()) h.append(" — ").append(esc(e.description()));
                if (e.aliases() != null && !e.aliases().isEmpty()) h.append(" <i>(aliases: ").append(String.join(", ", e.aliases())).append(")</i>");
                h.append("</div>\n");
            }
        }
        h.append("</div>\n");

        // Resolved relationships
        h.append("<h2>Resolved Relationships (").append(ALL_RESOLVED_RELATIONS.size()).append(")</h2>\n<div class='section'>\n");
        Map<String, Long> rc = ALL_RESOLVED_RELATIONS.stream()
                .collect(Collectors.groupingBy(GraphExtractionSchema.ExtractedRelation::type, Collectors.counting()));
        h.append("<table><tr><th>Type</th><th>Count</th><th>Schema?</th></tr>\n");
        rc.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> h.append("<tr><td>").append(e.getKey()).append("</td><td>").append(e.getValue())
                        .append("</td><td>").append(SCHEMA_REL_TYPES.contains(e.getKey()) ? "Y" : "N").append("</td></tr>\n"));
        h.append("</table>\n");
        for (var r : ALL_RESOLVED_RELATIONS) {
            h.append("<div class='rel'><b>").append(esc(r.source())).append("</b> —[").append(r.type())
                    .append("]→ <b>").append(esc(r.target())).append("</b>");
            if (r.description() != null && !r.description().isEmpty()) h.append(" <i>").append(esc(r.description())).append("</i>");
            h.append("</div>\n");
        }
        h.append("</div>\n</body></html>");
        return h.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
