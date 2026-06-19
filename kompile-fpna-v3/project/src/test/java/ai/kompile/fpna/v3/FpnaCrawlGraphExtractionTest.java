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
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import ai.kompile.core.graphrag.model.schema.*;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that simulate what a crawl generates for the FP&A CPG Channel scenario.
 * Validates:
 * - Schema preset loading and structure (fpna-cpg-channel-v1)
 * - Entity extraction results conform to schema node types
 * - Relationship extraction results conform to schema relationship types
 * - Business process patterns are captured correctly
 * - Cross-document entity resolution produces correct unified graph
 * - Email → Spreadsheet → Table containment chains
 * - Version assertion and variance triage patterns
 */
class FpnaCrawlGraphExtractionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private EntityResolutionService resolutionService;

    // ── Expected FP&A CPG Channel v1 schema types ──
    private static final Set<String> EXPECTED_NODE_TYPES = Set.of(
            "CHANNEL_TAXONOMY", "SKU_MASTER", "CURRENCY_REGISTRY", "CHART_OF_ACCOUNTS",
            "FX_FORWARD_CURVE", "REGIONAL_FORECAST", "FORECAST_ADJUSTMENT", "BANK_TRANSACTION",
            "VARIANCE_TRIAGE", "DATA_QUALITY_FLAG", "CONTROL_ASSERTION", "CLOSE_STEP",
            "PIPELINE_COVERAGE", "CASH_CONVERSION_CYCLE", "FREE_CASH_FLOW_MARGIN",
            "ROIC_DECOMPOSITION", "APPROVAL_ROLE", "PERSON", "TABLE", "SPREADSHEET", "EMAIL_MESSAGE"
    );

    private static final Set<String> EXPECTED_RELATIONSHIP_TYPES = Set.of(
            "CONTAINS", "PART_OF", "VALIDATES", "SOURCE_OF", "ATTACHMENT_OF", "VERSION_OF",
            "APPROVED_BY", "SUBMITTED_BY", "FEEDS_INTO", "DERIVES_FROM", "REFERENCES_TAXONOMY",
            "APPLIES_ADJUSTMENT", "SENT_BY", "SENT_TO", "HAS_ATTACHMENT", "ESCALATED_TO", "TRIGGERS"
    );

    @BeforeEach
    void setUp() {
        resolutionService = new EntityResolutionService();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SCHEMA PRESET VALIDATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Schema preset fpna-cpg-channel-v1.json loads and has correct structure")
    void schemaPresetLoadsCorrectly() throws Exception {
        JsonNode schema = loadSchemaPreset();

        assertNotNull(schema, "Schema preset should load from classpath");
        assertEquals("fpna-cpg-channel-v1", schema.get("id").asText());
        assertEquals(1, schema.get("version").asInt());
        assertTrue(schema.has("nodeTypes"), "Schema must have nodeTypes");
        assertTrue(schema.has("relationshipTypes"), "Schema must have relationshipTypes");
        assertTrue(schema.has("patterns"), "Schema must have patterns");
    }

    @Test
    @DisplayName("Schema has exactly 21 node types")
    void schemaHas21NodeTypes() throws Exception {
        JsonNode schema = loadSchemaPreset();
        JsonNode nodeTypes = schema.get("nodeTypes");

        assertEquals(21, nodeTypes.size(),
                "fpna-cpg-channel-v1 should define exactly 21 node types");

        Set<String> labels = new HashSet<>();
        for (JsonNode nt : nodeTypes) {
            labels.add(nt.get("label").asText());
        }

        assertEquals(EXPECTED_NODE_TYPES, labels,
                "Node type labels should match expected set");
    }

    @Test
    @DisplayName("Schema has exactly 17 relationship types")
    void schemaHas17RelationshipTypes() throws Exception {
        JsonNode schema = loadSchemaPreset();
        JsonNode relTypes = schema.get("relationshipTypes");

        assertEquals(17, relTypes.size(),
                "fpna-cpg-channel-v1 should define exactly 17 relationship types");

        Set<String> types = new HashSet<>();
        for (JsonNode rt : relTypes) {
            types.add(rt.get("type").asText());
        }

        assertEquals(EXPECTED_RELATIONSHIP_TYPES, types,
                "Relationship types should match expected set");
    }

    @Test
    @DisplayName("Schema has 17 declared graph patterns")
    void schemaHas17Patterns() throws Exception {
        JsonNode schema = loadSchemaPreset();
        JsonNode patterns = schema.get("patterns");

        assertEquals(17, patterns.size(),
                "fpna-cpg-channel-v1 should declare exactly 17 traversal patterns");
    }

    @Test
    @DisplayName("All node types have description and properties")
    void allNodeTypesHaveDescriptionAndProperties() throws Exception {
        JsonNode schema = loadSchemaPreset();
        for (JsonNode nt : schema.get("nodeTypes")) {
            String label = nt.get("label").asText();
            assertTrue(nt.has("description") && !nt.get("description").asText().isBlank(),
                    label + " must have a non-empty description");
            assertTrue(nt.has("properties"),
                    label + " must have a properties array");
        }
    }

    @Test
    @DisplayName("PERSON node type has required properties: full_name, email, role, department")
    void personNodeTypeHasRequiredProperties() throws Exception {
        JsonNode schema = loadSchemaPreset();
        JsonNode personNode = findNodeType(schema, "PERSON");

        assertNotNull(personNode, "PERSON node type must exist");

        Set<String> propNames = new HashSet<>();
        for (JsonNode prop : personNode.get("properties")) {
            propNames.add(prop.get("name").asText());
        }

        assertTrue(propNames.contains("full_name"), "PERSON must have 'full_name' property");
        assertTrue(propNames.contains("email"), "PERSON must have 'email' property");
        assertTrue(propNames.contains("role"), "PERSON must have 'role' property");
    }

    @Test
    @DisplayName("EMAIL_MESSAGE node type has required properties: subject, from, to, date")
    void emailMessageNodeTypeHasRequiredProperties() throws Exception {
        JsonNode schema = loadSchemaPreset();
        JsonNode emailNode = findNodeType(schema, "EMAIL_MESSAGE");

        assertNotNull(emailNode, "EMAIL_MESSAGE node type must exist");

        Set<String> propNames = new HashSet<>();
        for (JsonNode prop : emailNode.get("properties")) {
            propNames.add(prop.get("name").asText());
        }

        assertTrue(propNames.contains("subject"), "EMAIL_MESSAGE must have 'subject'");
        assertTrue(propNames.contains("from"), "EMAIL_MESSAGE must have 'from'");
        assertTrue(propNames.contains("to"), "EMAIL_MESSAGE must have 'to'");
        assertTrue(propNames.contains("date"), "EMAIL_MESSAGE must have 'date'");
    }

    @Test
    @DisplayName("GraphSchema can be constructed from preset JSON")
    void graphSchemaConstructedFromPreset() throws Exception {
        JsonNode schema = loadSchemaPreset();

        List<NodeType> nodeTypes = new ArrayList<>();
        for (JsonNode nt : schema.get("nodeTypes")) {
            List<PropertyType> props = new ArrayList<>();
            for (JsonNode p : nt.get("properties")) {
                props.add(new PropertyType(p.get("name").asText(), p.get("type").asText()));
            }
            nodeTypes.add(new NodeType(nt.get("label").asText(),
                    nt.get("description").asText(), props));
        }

        List<RelationshipType> relTypes = new ArrayList<>();
        for (JsonNode rt : schema.get("relationshipTypes")) {
            List<PropertyType> props = new ArrayList<>();
            if (rt.has("properties")) {
                for (JsonNode p : rt.get("properties")) {
                    props.add(new PropertyType(p.get("name").asText(), p.get("type").asText()));
                }
            }
            relTypes.add(new RelationshipType(rt.get("type").asText(),
                    rt.get("description").asText(), props));
        }

        List<String> patterns = new ArrayList<>();
        for (JsonNode p : schema.get("patterns")) {
            patterns.add(p.asText());
        }

        GraphSchema graphSchema = new GraphSchema(nodeTypes, relTypes, patterns);

        assertEquals(21, graphSchema.getAllNodeLabels().size());
        assertEquals(17, graphSchema.getAllRelationshipTypes().size());
        assertTrue(graphSchema.getAllNodeLabels().contains("PERSON"));
        assertTrue(graphSchema.getAllRelationshipTypes().contains("SENT_BY"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SIMULATED CRAWL EXTRACTION: AMER EMAIL
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AMER email crawl produces expected entity types")
    void amerEmailCrawlEntities() {
        ExtractionResult amerExtraction = buildAmerEmailExtraction();

        Set<String> entityTypes = amerExtraction.entities().stream()
                .map(ExtractedEntity::type)
                .collect(Collectors.toSet());

        assertTrue(entityTypes.contains("PERSON"), "Should extract PERSON entities");
        assertTrue(entityTypes.contains("EMAIL_MESSAGE"), "Should extract EMAIL_MESSAGE");
        assertTrue(entityTypes.contains("SPREADSHEET"), "Should extract SPREADSHEET");
        assertTrue(entityTypes.contains("REGIONAL_FORECAST"), "Should extract REGIONAL_FORECAST");
        assertTrue(entityTypes.contains("SKU_MASTER"), "Should extract SKU_MASTER (HYD-110)");
    }

    @Test
    @DisplayName("AMER email crawl produces email → person SENT_BY/SENT_TO relationships")
    void amerEmailCrawlRelationships() {
        ExtractionResult amerExtraction = buildAmerEmailExtraction();

        Set<String> relTypes = amerExtraction.relations().stream()
                .map(ExtractedRelation::type)
                .collect(Collectors.toSet());

        assertTrue(relTypes.contains("SENT_BY"), "Should have SENT_BY relation");
        assertTrue(relTypes.contains("SENT_TO"), "Should have SENT_TO relation");
        assertTrue(relTypes.contains("HAS_ATTACHMENT"), "Should have HAS_ATTACHMENT relation");
        assertTrue(relTypes.contains("SUBMITTED_BY"), "Should have SUBMITTED_BY relation");
    }

    @Test
    @DisplayName("AMER email: HAS_ATTACHMENT links email to spreadsheet")
    void amerEmailHasAttachment() {
        ExtractionResult amerExtraction = buildAmerEmailExtraction();

        List<ExtractedRelation> attachments = amerExtraction.relations().stream()
                .filter(r -> r.type().equals("HAS_ATTACHMENT"))
                .collect(Collectors.toList());

        assertEquals(1, attachments.size(), "Should have exactly 1 HAS_ATTACHMENT relation");

        ExtractedRelation attach = attachments.get(0);
        // Source should be the email, target should be the spreadsheet
        ExtractedEntity source = findEntityById(amerExtraction, attach.source());
        ExtractedEntity target = findEntityById(amerExtraction, attach.target());

        assertNotNull(source);
        assertNotNull(target);
        assertEquals("EMAIL_MESSAGE", source.type(), "HAS_ATTACHMENT source should be EMAIL_MESSAGE");
        assertEquals("SPREADSHEET", target.type(), "HAS_ATTACHMENT target should be SPREADSHEET");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SIMULATED CRAWL EXTRACTION: EMEA EMAIL
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EMEA email crawl extracts currency and channel taxonomy entities")
    void emeaEmailCrawlEntities() {
        ExtractionResult emeaExtraction = buildEmeaEmailExtraction();

        Set<String> entityTypes = emeaExtraction.entities().stream()
                .map(ExtractedEntity::type)
                .collect(Collectors.toSet());

        assertTrue(entityTypes.contains("CURRENCY_REGISTRY"), "Should extract CURRENCY_REGISTRY");
        assertTrue(entityTypes.contains("CHANNEL_TAXONOMY"), "Should extract CHANNEL_TAXONOMY");
        assertTrue(entityTypes.contains("PERSON"), "Should extract PERSON entities");
    }

    @Test
    @DisplayName("EMEA email: REFERENCES_TAXONOMY links forecast to channel taxonomy")
    void emeaForecastReferencesChannelTaxonomy() {
        ExtractionResult emeaExtraction = buildEmeaEmailExtraction();

        List<ExtractedRelation> taxonomyRefs = emeaExtraction.relations().stream()
                .filter(r -> r.type().equals("REFERENCES_TAXONOMY"))
                .collect(Collectors.toList());

        assertFalse(taxonomyRefs.isEmpty(),
                "EMEA extraction should have REFERENCES_TAXONOMY relations");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BUSINESS PROCESS: VERSION ASSERTION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Version confusion: v1, v2, v3 spreadsheets linked by VERSION_OF")
    void versionAssertionPattern() {
        ExtractionResult versionExtraction = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("ss1", "AMER_Forecast_Q3_v1.xlsx", "SPREADSHEET",
                                List.of(), "First version", 0.9, Map.of()),
                        new ExtractedEntity("ss2", "AMER_Forecast_Q3_v3_FINAL_v2.xlsx", "SPREADSHEET",
                                List.of(), "Correct final version (named v3 but is v2)", 0.95, Map.of()),
                        new ExtractedEntity("ss3", "AMER_Forecast_Q3_v3.xlsx", "SPREADSHEET",
                                List.of(), "Broken version with copy-paste glitch", 0.9, Map.of()),
                        new ExtractedEntity("dq1", "Stale subtotals", "DATA_QUALITY_FLAG",
                                List.of(), "GRAND TOTAL row is stale", 0.85,
                                Map.of("flag_type", "WARN", "context", "Subtotals not recalculated after row additions"))
                ),
                List.of(
                        new ExtractedRelation("ss2", "ss1", "VERSION_OF",
                                "v2 supersedes v1", 0.9, Map.of("version_label", "v2")),
                        new ExtractedRelation("ss3", "ss1", "VERSION_OF",
                                "v3 supersedes v1 (but has copy-paste glitch)", 0.85,
                                Map.of("version_label", "v3")),
                        new ExtractedRelation("dq1", "ss2", "TRIGGERS",
                                "DQ flag on the correct version's stale totals", 0.8, Map.of())
                ),
                null);

        // Verify version chain
        List<ExtractedRelation> versionRels = versionExtraction.relations().stream()
                .filter(r -> r.type().equals("VERSION_OF"))
                .collect(Collectors.toList());

        assertEquals(2, versionRels.size(), "Should have 2 VERSION_OF relations");

        // Verify DQ flag triggers on a spreadsheet
        List<ExtractedRelation> triggers = versionExtraction.relations().stream()
                .filter(r -> r.type().equals("TRIGGERS"))
                .collect(Collectors.toList());

        assertEquals(1, triggers.size(), "Should have 1 TRIGGERS relation for DQ flag");
        assertEquals("dq1", triggers.get(0).source(), "TRIGGERS source should be the DQ flag");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BUSINESS PROCESS: VARIANCE TRIAGE & ESCALATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Variance triage: channel mismatch always escalates to Mei Chen")
    void varianceTriageChannelMismatchEscalation() {
        ExtractionResult triageExtraction = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("vt1", "Channel Mismatch", "VARIANCE_TRIAGE",
                                List.of(), "DTC vs ecom vs Direct-to-Consumer inconsistency", 0.9,
                                Map.of("pattern_name", "channel_mismatch",
                                        "action", "ESCALATE",
                                        "escalation_rule", "always_escalate_per_chen_override")),
                        new ExtractedEntity("vt2", "SKU Misentry", "VARIANCE_TRIAGE",
                                List.of(), "Incorrect SKU code in regional workbook", 0.9,
                                Map.of("pattern_name", "sku_misentry",
                                        "action", "AUTO_FIX")),
                        new ExtractedEntity("vt3", "Stale FX Rate", "VARIANCE_TRIAGE",
                                List.of(), "FX rate older than 24 hours", 0.9,
                                Map.of("pattern_name", "stale_fx",
                                        "action", "AUTO_FIX")),
                        new ExtractedEntity("ar1", "VP FP&A", "APPROVAL_ROLE",
                                List.of(), "Mei Chen's approval role", 0.95,
                                Map.of("role_name", "VP FP&A", "authority_level", "SENIOR")),
                        new ExtractedEntity("p1", "Mei Chen", "PERSON",
                                List.of(), "VP FP&A — always reviews channel mismatches", 0.95, Map.of())
                ),
                List.of(
                        new ExtractedRelation("vt1", "ar1", "ESCALATED_TO",
                                "Channel mismatch always escalates to VP FP&A", 0.95, Map.of()),
                        new ExtractedRelation("ar1", "p1", "APPROVED_BY",
                                "VP FP&A role held by Mei Chen", 0.9, Map.of())
                ),
                null);

        // Verify the escalation chain: VARIANCE_TRIAGE → APPROVAL_ROLE → PERSON
        List<ExtractedRelation> escalations = triageExtraction.relations().stream()
                .filter(r -> r.type().equals("ESCALATED_TO"))
                .collect(Collectors.toList());

        assertEquals(1, escalations.size(), "Should have 1 ESCALATED_TO relation");
        assertEquals("vt1", escalations.get(0).source(), "Source should be channel mismatch triage");

        // Verify auto-fix vs escalation policy
        ExtractedEntity channelMismatch = findEntityById(triageExtraction, "vt1");
        assertEquals("ESCALATE", channelMismatch.properties().get("action"),
                "Channel mismatch action should be ESCALATE");

        ExtractedEntity skuMisentry = findEntityById(triageExtraction, "vt2");
        assertEquals("AUTO_FIX", skuMisentry.properties().get("action"),
                "SKU misentry action should be AUTO_FIX");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BUSINESS PROCESS: MONTHLY CLOSE CYCLE STEPS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Close cycle: 4 phases with control gates and approvals")
    void closeStepsWithControlsAndApprovals() {
        ExtractionResult closeExtraction = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("cs1", "Trial Balance Extraction", "CLOSE_STEP",
                                List.of(), "Auto-extract TB from NetSuite", 0.95,
                                Map.of("step_id", "1.1", "phase", "Inputs & Intake",
                                        "automation_status", "AUTO")),
                        new ExtractedEntity("cs2", "Version Assertion Gate", "CLOSE_STEP",
                                List.of(), "Human confirms authoritative file version", 1.0,
                                Map.of("step_id", "1.4", "phase", "Inputs & Intake",
                                        "automation_status", "APPROVE")),
                        new ExtractedEntity("cs3", "Channel Taxonomy Canonicalization", "CLOSE_STEP",
                                List.of(), "Normalize channel names", 0.9,
                                Map.of("step_id", "2.3", "phase", "Pre-Processing",
                                        "automation_status", "AUTO")),
                        new ExtractedEntity("cs4", "Trial Balance Tie Control", "CLOSE_STEP",
                                List.of(), "SOX hard gate: debits == credits", 1.0,
                                Map.of("step_id", "4.1", "phase", "Controls & Publish",
                                        "automation_status", "CONTROL_GATE")),
                        new ExtractedEntity("ctrl1", "C-01 TB Tie", "CONTROL_ASSERTION",
                                List.of(), "SOX control: trial balance must tie", 1.0,
                                Map.of("control_id", "C-01", "control_type", "HARD",
                                        "severity", "CRITICAL")),
                        new ExtractedEntity("p1", "J. Park", "PERSON",
                                List.of(), "Version gate approver", 0.9, Map.of()),
                        new ExtractedEntity("p2", "S. Reyes", "PERSON",
                                List.of(), "Version gate approver", 0.9, Map.of())
                ),
                List.of(
                        new ExtractedRelation("ctrl1", "cs4", "VALIDATES",
                                "C-01 validates trial balance tie step", 0.95,
                                Map.of("control_id", "C-01")),
                        new ExtractedRelation("p1", "cs2", "APPROVED_BY",
                                "J. Park approves version assertion", 0.9, Map.of()),
                        new ExtractedRelation("p2", "cs2", "APPROVED_BY",
                                "S. Reyes approves version assertion", 0.9, Map.of()),
                        new ExtractedRelation("cs1", "cs2", "FEEDS_INTO",
                                "TB extraction feeds into version assertion", 0.85, Map.of())
                ),
                null);

        // Verify close steps span all 4 phases
        Set<String> phases = closeExtraction.entities().stream()
                .filter(e -> e.type().equals("CLOSE_STEP"))
                .map(e -> e.properties().get("phase"))
                .collect(Collectors.toSet());

        assertTrue(phases.contains("Inputs & Intake"), "Should have Inputs & Intake phase");
        assertTrue(phases.contains("Pre-Processing"), "Should have Pre-Processing phase");
        assertTrue(phases.contains("Controls & Publish"), "Should have Controls & Publish phase");

        // Verify SOX control is HARD/CRITICAL
        ExtractedEntity soxControl = findEntityById(closeExtraction, "ctrl1");
        assertEquals("HARD", soxControl.properties().get("control_type"));
        assertEquals("CRITICAL", soxControl.properties().get("severity"));

        // Verify VALIDATES relation from control to close step
        assertTrue(closeExtraction.relations().stream()
                        .anyMatch(r -> r.type().equals("VALIDATES") &&
                                r.source().equals("ctrl1") && r.target().equals("cs4")),
                "C-01 should VALIDATE the TB tie close step");

        // Verify version gate has 2 approvers
        long approvalCount = closeExtraction.relations().stream()
                .filter(r -> r.type().equals("APPROVED_BY") && r.target().equals("cs2"))
                .count();
        assertEquals(2, approvalCount, "Version Assertion Gate should have 2 approvers");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FULL CRAWL SIMULATION: CROSS-DOCUMENT UNIFIED GRAPH
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Full crawl: AMER + EMEA extraction → entity resolution → unified graph")
    void fullCrawlUnifiedGraph() {
        ExtractionResult amerExtraction = buildAmerEmailExtraction();
        ExtractionResult emeaExtraction = buildEmeaEmailExtraction();

        ExtractionResult unified = resolutionService.resolve(
                List.of(amerExtraction, emeaExtraction));

        // ── Entity assertions ──
        // Mei Chen appears in both → should merge to 1
        assertEquals(1, countEntitiesByName(unified, "Mei Chen"),
                "Mei Chen should merge to 1 across AMER and EMEA");

        // Both regional forecasts should stay separate
        long forecastCount = unified.entities().stream()
                .filter(e -> e.type().equals("REGIONAL_FORECAST"))
                .count();
        assertEquals(2, forecastCount, "AMER and EMEA forecasts should remain separate");

        // Both emails should stay separate
        long emailCount = unified.entities().stream()
                .filter(e -> e.type().equals("EMAIL_MESSAGE"))
                .count();
        assertEquals(2, emailCount, "AMER and EMEA emails should remain separate");

        // ── Relationship assertions ──
        // All SENT_TO targeting Mei Chen should point to same canonical ID
        String meiCanonicalId = unified.entities().stream()
                .filter(e -> e.name().equals("Mei Chen"))
                .findFirst()
                .map(ExtractedEntity::id)
                .orElseThrow(() -> new AssertionError("Mei Chen not found"));

        List<ExtractedRelation> sentToMei = unified.relations().stream()
                .filter(r -> r.type().equals("SENT_TO") && r.target().equals(meiCanonicalId))
                .collect(Collectors.toList());

        assertEquals(2, sentToMei.size(),
                "Both AMER and EMEA emails should have SENT_TO pointing to canonical Mei Chen");

        // Verify the graph has diverse relationship types from both documents
        Set<String> relTypes = unified.relations().stream()
                .map(ExtractedRelation::type)
                .collect(Collectors.toSet());

        assertTrue(relTypes.contains("SENT_BY"), "Unified graph should have SENT_BY");
        assertTrue(relTypes.contains("SENT_TO"), "Unified graph should have SENT_TO");
        assertTrue(relTypes.contains("HAS_ATTACHMENT"), "Unified graph should have HAS_ATTACHMENT");
        assertTrue(relTypes.contains("SUBMITTED_BY"), "Unified graph should have SUBMITTED_BY");
    }

    @Test
    @DisplayName("Extraction entities all conform to fpna-cpg-channel-v1 node types")
    void extractedEntitiesConformToSchema() {
        ExtractionResult amerExtraction = buildAmerEmailExtraction();
        ExtractionResult emeaExtraction = buildEmeaEmailExtraction();

        for (ExtractedEntity entity : amerExtraction.entities()) {
            assertTrue(EXPECTED_NODE_TYPES.contains(entity.type()),
                    "AMER entity type '" + entity.type() + "' (" + entity.name()
                            + ") not in schema node types");
        }

        for (ExtractedEntity entity : emeaExtraction.entities()) {
            assertTrue(EXPECTED_NODE_TYPES.contains(entity.type()),
                    "EMEA entity type '" + entity.type() + "' (" + entity.name()
                            + ") not in schema node types");
        }
    }

    @Test
    @DisplayName("Extraction relations all conform to fpna-cpg-channel-v1 relationship types")
    void extractedRelationsConformToSchema() {
        ExtractionResult amerExtraction = buildAmerEmailExtraction();
        ExtractionResult emeaExtraction = buildEmeaEmailExtraction();

        for (ExtractedRelation rel : amerExtraction.relations()) {
            assertTrue(EXPECTED_RELATIONSHIP_TYPES.contains(rel.type()),
                    "AMER relation type '" + rel.type() + "' not in schema relationship types");
        }

        for (ExtractedRelation rel : emeaExtraction.relations()) {
            assertTrue(EXPECTED_RELATIONSHIP_TYPES.contains(rel.type()),
                    "EMEA relation type '" + rel.type() + "' not in schema relationship types");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONTAINMENT CHAINS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Containment chain: EMAIL → SPREADSHEET → TABLE")
    void emailSpreadsheetTableContainment() {
        ExtractionResult extraction = ExtractionResult.of(
                List.of(
                        new ExtractedEntity("email1", "AMER forecast email", "EMAIL_MESSAGE",
                                List.of(), "AMER submission", 0.95,
                                Map.of("subject", "AMER forecast Q3 (FINAL v2)")),
                        new ExtractedEntity("ss1", "AMER_Forecast_Q3_v3_FINAL_v2.xlsx", "SPREADSHEET",
                                List.of(), "AMER forecast spreadsheet", 0.95,
                                Map.of("sheet_count", "3")),
                        new ExtractedEntity("tbl1", "Wholesale tab", "TABLE",
                                List.of(), "Wholesale channel forecast data", 0.9,
                                Map.of("row_count", "45", "column_count", "8")),
                        new ExtractedEntity("tbl2", "DTC tab", "TABLE",
                                List.of(), "DTC channel forecast data", 0.9,
                                Map.of("row_count", "32", "column_count", "8"))
                ),
                List.of(
                        new ExtractedRelation("email1", "ss1", "HAS_ATTACHMENT",
                                "Email attaches forecast", 0.95, Map.of()),
                        new ExtractedRelation("ss1", "tbl1", "CONTAINS",
                                "Spreadsheet contains wholesale tab", 0.9, Map.of()),
                        new ExtractedRelation("ss1", "tbl2", "CONTAINS",
                                "Spreadsheet contains DTC tab", 0.9, Map.of())
                ),
                null);

        // Verify containment chain: email → spreadsheet via HAS_ATTACHMENT
        assertTrue(extraction.relations().stream()
                        .anyMatch(r -> r.type().equals("HAS_ATTACHMENT") &&
                                r.source().equals("email1") && r.target().equals("ss1")),
                "Email should HAS_ATTACHMENT to spreadsheet");

        // spreadsheet → tables via CONTAINS
        long containsCount = extraction.relations().stream()
                .filter(r -> r.type().equals("CONTAINS") && r.source().equals("ss1"))
                .count();
        assertEquals(2, containsCount, "Spreadsheet should CONTAIN 2 tables");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EXTRACTION RESULT SCHEMA FORMAT
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ExtractionResult has correct schema version")
    void extractionResultSchemaVersion() {
        ExtractionResult result = ExtractionResult.of(List.of(), List.of(), null);
        assertEquals("kompile-graph-extraction/v1", result.schema());
    }

    @Test
    @DisplayName("ExtractionResult with metadata tracks source document")
    void extractionResultWithMetadata() {
        ExtractionMetadata metadata = ExtractionMetadata.forChunk(
                "chunk-001", "doc-amer-email", "llm-chat/default");

        ExtractionResult result = ExtractionResult.of(
                List.of(entity("e1", "Test", "PERSON", "Test")),
                List.of(),
                metadata);

        assertNotNull(result.metadata());
        assertEquals("chunk-001", result.metadata().sourceChunkId());
        assertEquals("doc-amer-email", result.metadata().sourceDocumentId());
        assertEquals("llm-chat/default", result.metadata().extractionModel());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Build a realistic extraction result simulating what the LLM would produce
     * from the AMER forecast email (06a_email_AMER.html).
     */
    private ExtractionResult buildAmerEmailExtraction() {
        return ExtractionResult.of(
                List.of(
                        new ExtractedEntity("amer-p1", "Sarah Chen", "PERSON",
                                List.of("S. Chen"), "Senior Manager, Sales Ops, Americas", 0.95,
                                Map.of("email", "s.chen@northstar.co", "role", "Senior Manager, Sales Ops")),
                        new ExtractedEntity("amer-p2", "Mei Chen", "PERSON",
                                List.of("M. Chen"), "VP FP&A, forecast consolidation lead", 0.95,
                                Map.of("email", "m.chen@northstar.co")),
                        new ExtractedEntity("amer-p3", "J. Park", "PERSON",
                                List.of(), "Forecast recipient and approver", 0.85,
                                Map.of("email", "j.park@northstar.co")),
                        new ExtractedEntity("amer-email", "AMER forecast Q3 email", "EMAIL_MESSAGE",
                                List.of(), "AMER Q3 forecast submission email", 0.95,
                                Map.of("subject", "AMER forecast Q3 (FINAL v2)",
                                        "from", "s.chen@northstar.co",
                                        "to", "m.chen@northstar.co; j.park@northstar.co",
                                        "date", "2026-05-04")),
                        new ExtractedEntity("amer-ss", "AMER_Forecast_Q3_v3_FINAL_v2.xlsx", "SPREADSHEET",
                                List.of(), "AMER Q3 forecast workbook (correct version)", 0.95, Map.of()),
                        new ExtractedEntity("amer-fc", "Americas Regional Forecast Q3 2026", "REGIONAL_FORECAST",
                                List.of("AMER Q3 Forecast"), "AMER regional forecast for Q3 2026", 0.9,
                                Map.of("region", "AMER", "forecast_cycle", "Q3 2026",
                                        "submitted_by", "Sarah Chen")),
                        new ExtractedEntity("amer-sku", "HYD-110", "SKU_MASTER",
                                List.of("sleep serum"), "New SKU launching July 1", 0.85,
                                Map.of("sku_id", "HYD-110", "lifecycle_status", "LAUNCH",
                                        "canonical_name", "Sleep Serum")),
                        new ExtractedEntity("amer-dq", "Stale subtotals in AMER forecast", "DATA_QUALITY_FLAG",
                                List.of(), "Subtotals and GRAND TOTAL are stale", 0.8,
                                Map.of("flag_type", "WARN"))
                ),
                List.of(
                        new ExtractedRelation("amer-email", "amer-p1", "SENT_BY",
                                "Sarah sent the AMER email", 0.95, Map.of()),
                        new ExtractedRelation("amer-email", "amer-p2", "SENT_TO",
                                "Email sent to Mei Chen", 0.95, Map.of()),
                        new ExtractedRelation("amer-email", "amer-p3", "SENT_TO",
                                "Email sent to J. Park", 0.9, Map.of()),
                        new ExtractedRelation("amer-email", "amer-ss", "HAS_ATTACHMENT",
                                "Forecast file attached", 0.95, Map.of()),
                        new ExtractedRelation("amer-p1", "amer-fc", "SUBMITTED_BY",
                                "Sarah submitted AMER forecast", 0.9, Map.of()),
                        new ExtractedRelation("amer-fc", "amer-sku", "CONTAINS",
                                "Forecast includes HYD-110 new launch", 0.85, Map.of()),
                        new ExtractedRelation("amer-dq", "amer-ss", "TRIGGERS",
                                "DQ flag on stale subtotals", 0.8, Map.of())
                ),
                ExtractionMetadata.forChunk("amer-chunk-1", "06a_email_AMER.html", "llm-chat/default"));
    }

    /**
     * Build a realistic extraction result simulating what the LLM would produce
     * from the EMEA forecast email (06b_email_EMEA.html).
     */
    private ExtractionResult buildEmeaEmailExtraction() {
        return ExtractionResult.of(
                List.of(
                        new ExtractedEntity("emea-p1", "François Vasseur", "PERSON",
                                List.of("F. Vasseur"), "Sales Director, EMEA", 0.95,
                                Map.of("email", "f.vasseur@northstar.eu", "role", "Sales Director")),
                        new ExtractedEntity("emea-p2", "Mei Chen", "PERSON",
                                List.of("M. Chen"), "Consolidation recipient", 0.95,
                                Map.of("email", "m.chen@northstar.co")),
                        new ExtractedEntity("emea-p3", "Kira O'Donnell", "PERSON",
                                List.of("K. O'Donnell"), "UK Retail manager", 0.9,
                                Map.of("role", "UK Retail Manager")),
                        new ExtractedEntity("emea-p4", "Lukas Schmidt", "PERSON",
                                List.of("L. Schmidt"), "Germany/EU North manager", 0.9,
                                Map.of("role", "Germany/EU North Manager")),
                        new ExtractedEntity("emea-p5", "Paolo Greco", "PERSON",
                                List.of("P. Greco"), "Southern Europe manager", 0.9,
                                Map.of("role", "Southern Europe Manager")),
                        new ExtractedEntity("emea-email", "EMEA Q3 forecast email", "EMAIL_MESSAGE",
                                List.of(), "EMEA Q3 2026 forecast submission", 0.95,
                                Map.of("subject", "EMEA Q3 2026 forecast — for consolidation",
                                        "from", "f.vasseur@northstar.eu",
                                        "to", "m.chen@northstar.co",
                                        "date", "2026-05-05")),
                        new ExtractedEntity("emea-ss", "EMEA forecast Jun-Aug 2026.xlsx", "SPREADSHEET",
                                List.of(), "EMEA forecast workbook", 0.95, Map.of()),
                        new ExtractedEntity("emea-fc", "Europe Middle East Africa Regional Forecast Q3 2026", "REGIONAL_FORECAST",
                                List.of("EMEA Q3 Forecast"), "EMEA regional forecast Q3 2026", 0.9,
                                Map.of("region", "EMEA", "forecast_cycle", "Q3 2026",
                                        "submitted_by", "François Vasseur")),
                        new ExtractedEntity("emea-gbp", "GBP", "CURRENCY_REGISTRY",
                                List.of("British Pound"), "UK currency", 0.9,
                                Map.of("currency_code", "GBP")),
                        new ExtractedEntity("emea-eur", "EUR", "CURRENCY_REGISTRY",
                                List.of("Euro"), "Eurozone currency", 0.9,
                                Map.of("currency_code", "EUR")),
                        new ExtractedEntity("emea-ch-dtc", "DTC", "CHANNEL_TAXONOMY",
                                List.of("Direct-to-Consumer"), "EMEA DTC channel", 0.85,
                                Map.of("canonical_name", "DTC")),
                        new ExtractedEntity("emea-ch-mp", "Marketplace", "CHANNEL_TAXONOMY",
                                List.of("Amazon"), "EMEA marketplace = Amazon", 0.85,
                                Map.of("canonical_name", "Marketplace"))
                ),
                List.of(
                        new ExtractedRelation("emea-email", "emea-p1", "SENT_BY",
                                "Vasseur sent EMEA email", 0.95, Map.of()),
                        new ExtractedRelation("emea-email", "emea-p2", "SENT_TO",
                                "Email to Mei Chen", 0.95, Map.of()),
                        new ExtractedRelation("emea-email", "emea-ss", "HAS_ATTACHMENT",
                                "Forecast file attached", 0.95, Map.of()),
                        new ExtractedRelation("emea-p1", "emea-fc", "SUBMITTED_BY",
                                "Vasseur submitted EMEA forecast", 0.9, Map.of()),
                        new ExtractedRelation("emea-fc", "emea-ch-dtc", "REFERENCES_TAXONOMY",
                                "EMEA forecast references DTC channel", 0.85, Map.of()),
                        new ExtractedRelation("emea-fc", "emea-ch-mp", "REFERENCES_TAXONOMY",
                                "EMEA forecast references Marketplace channel", 0.85, Map.of())
                ),
                ExtractionMetadata.forChunk("emea-chunk-1", "06b_email_EMEA.html", "llm-chat/default"));
    }

    private JsonNode loadSchemaPreset() throws Exception {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("schema-presets/fpna-cpg-channel-v1.json")) {
            if (is == null) {
                throw new AssertionError("Schema preset not found on classpath. " +
                        "Ensure kompile-app-main is in test dependencies.");
            }
            return MAPPER.readTree(is);
        }
    }

    private JsonNode findNodeType(JsonNode schema, String label) {
        for (JsonNode nt : schema.get("nodeTypes")) {
            if (label.equals(nt.get("label").asText())) {
                return nt;
            }
        }
        return null;
    }

    private static ExtractedEntity entity(String id, String name, String type, String description) {
        return new ExtractedEntity(id, name, type, List.of(), description, 0.9, Map.of());
    }

    private static ExtractedRelation relation(String source, String target, String type,
                                               String description, double confidence) {
        return new ExtractedRelation(source, target, type, description, confidence, Map.of());
    }

    private ExtractedEntity findEntityById(ExtractionResult result, String id) {
        return result.entities().stream()
                .filter(e -> e.id().equals(id))
                .findFirst().orElse(null);
    }

    private long countEntitiesByName(ExtractionResult result, String name) {
        return result.entities().stream()
                .filter(e -> e.name().equals(name))
                .count();
    }
}
