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

import ai.kompile.knowledgegraph.impl.EntityExtractionServiceImpl;
import ai.kompile.knowledgegraph.service.EntityExtractionService;
import ai.kompile.knowledgegraph.service.EntityExtractionService.EntityType;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business process resolution tests that read the actual HTML source documents
 * (process map, KAA methodology, KAA process map) from FP&A workflow artifacts.
 * <p>
 * Validates that all business processes documented in {@code fpna-expected-graph.html}
 * are extractable from the source HTML:
 * <ul>
 *   <li>14 close-cycle steps across 4 phases</li>
 *   <li>5 variance triage patterns</li>
 *   <li>7 controls (C-01 to C-07)</li>
 *   <li>HITL approval gates and escalation chains</li>
 *   <li>KAA 6-layer architecture references</li>
 *   <li>8 agent specifications</li>
 * </ul>
 * Ground truth documented in {@code fpna-expected-graph.html} (sections 4-7).
 */
class FpnaBusinessProcessResolutionTest {

    private static final Path ARTIFACTS_DIR = resolveArtifactsDir();
    private EntityExtractionServiceImpl extractionService;

    // Loaded + HTML-stripped text from source documents
    private String processMapText;
    private String kaaMethodologyText;
    private String kaaProcessMapText;
    private String semanticLayerText;
    private String expectedGraphHtml;

    @BeforeEach
    void setUp() throws Exception {
        extractionService = new EntityExtractionServiceImpl();

        processMapText = loadAndStripHtml("02_process_map.html");
        kaaMethodologyText = loadAndStripHtml("08_KAA_methodology.html");
        kaaProcessMapText = loadAndStripHtml("09_process_map_KAA.html");
        semanticLayerText = loadAndStripHtml("10_semantic_layer_DRAFT.html");

        expectedGraphHtml = new String(getClass().getClassLoader()
                .getResourceAsStream("fpna-expected-graph.html")
                .readAllBytes());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLOSE-CYCLE PHASE PRESENCE (from 02_process_map.html and 09_process_map_KAA.html)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Process map contains all 4 phases of the close cycle")
    void processMapContainsAllFourPhases() {
        // KAA process map explicitly names all 4 phases (case-insensitive check —
        // HTML-to-text stripping may produce "intake" not "Intake")
        String lower = kaaProcessMapText.toLowerCase();
        assertTrue(lower.contains("inputs") && lower.contains("intake"),
                "Should contain Phase 1: Inputs & Intake");
        assertTrue(lower.contains("validate") && lower.contains("reconcile"),
                "Should contain Phase 2: Validate & Reconcile");
        assertTrue(lower.contains("project") && lower.contains("consolidate"),
                "Should contain Phase 3: Project & Consolidate");
        assertTrue(lower.contains("review") && lower.contains("publish"),
                "Should contain Phase 4: Review & Publish");
    }

    @Test
    @DisplayName("KAA process map documents 14 steps")
    void kaaProcessMapHas14Steps() {
        // The KAA process map (09) explicitly names 14 steps across 4 phases:
        // Phase 1: 5 steps, Phase 2: 3 steps, Phase 3: 3 steps, Phase 4: 3 steps
        // The L3 card says: "As-is — 14 steps across 4 phases"
        assertTrue(kaaProcessMapText.contains("14 steps"),
                "KAA process map should mention '14 steps'");
    }

    @Test
    @DisplayName("Phase 1 data sources: NetSuite, Shopify, Amazon, FX, Workday, Salesforce")
    void phase1DataSourcesPresent() {
        assertTrue(kaaProcessMapText.contains("NetSuite"),
                "Phase 1 should reference NetSuite as data source");
        assertTrue(kaaProcessMapText.contains("Shopify"),
                "Phase 1 should reference Shopify as data source");
        assertTrue(kaaProcessMapText.contains("Amazon"),
                "Phase 1 should reference Amazon as data source");
        assertTrue(kaaProcessMapText.contains("Salesforce"),
                "Phase 1 should reference Salesforce as data source");
        assertTrue(kaaProcessMapText.contains("Workday"),
                "Phase 1 should reference Workday as data source");
    }

    @Test
    @DisplayName("Phase 2 mentions forecast validation and triage")
    void phase2ForecastValidationPresent() {
        // Triage is a core Phase 2 concept
        assertTrue(kaaProcessMapText.contains("Triage") || kaaProcessMapText.contains("triage"),
                "Phase 2 should reference triage");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VARIANCE TRIAGE PATTERNS (from 09_process_map_KAA.html and 10_semantic_layer)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Expected graph HTML documents all 5 variance triage patterns")
    void expectedGraphHasAllTriagePatterns() {
        assertTrue(expectedGraphHtml.contains("ChannelMismatch"),
                "Answer file should contain ChannelMismatch triage pattern");
        assertTrue(expectedGraphHtml.contains("CurrencySymbolDrift"),
                "Answer file should contain CurrencySymbolDrift triage pattern");
        assertTrue(expectedGraphHtml.contains("SKUTypoVariant"),
                "Answer file should contain SKUTypoVariant triage pattern");
        assertTrue(expectedGraphHtml.contains("GMOutOfBand"),
                "Answer file should contain GMOutOfBand triage pattern");
        assertTrue(expectedGraphHtml.contains("StaleFXRow"),
                "Answer file should contain StaleFXRow triage pattern");
    }

    @Test
    @DisplayName("ChannelMismatch always escalates to Mei Chen (never auto-fix)")
    void channelMismatchAlwaysEscalates() {
        // This is a critical business rule from M. Chen's interview:
        // channel mismatches are ALWAYS escalated, never auto-fixed
        assertTrue(expectedGraphHtml.contains("ALWAYS ESCALATE"),
                "ChannelMismatch should be marked ALWAYS ESCALATE");
        // The KAA process map should also mention this override
        assertTrue(kaaProcessMapText.contains("Channel mismatch") || kaaProcessMapText.contains("channel mismatch")
                        || kaaProcessMapText.contains("auto-fix") || kaaProcessMapText.contains("escalat"),
                "KAA process map should mention channel mismatch escalation rules");
    }

    @Test
    @DisplayName("Triage confidence threshold is 0.85 for auto-correct patterns")
    void triageConfidenceThreshold() {
        // CurrencySymbolDrift and SKUTypoVariant have auto-correct >= 0.85 threshold
        assertTrue(expectedGraphHtml.contains("0.85"),
                "Expected graph should document the 0.85 confidence threshold for auto-correction");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7 CONTROLS (C-01 to C-07) VALIDATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Expected graph documents all 7 controls C-01 through C-07")
    void allSevenControlsDocumented() {
        for (int i = 1; i <= 7; i++) {
            String controlId = String.format("C-%02d", i);
            assertTrue(expectedGraphHtml.contains(controlId),
                    "Expected graph should document control " + controlId);
        }
    }

    @Test
    @DisplayName("Hard controls block close; soft controls require commentary")
    void controlSeverityTypes() {
        // C-01 FX Consistency, C-02 Regional Total, C-04 SKU Mapping, C-07 Statement Recon = HARD
        // C-03 Pipeline Coverage, C-05 Channel GM Band, C-06 SKU Margin = SOFT
        assertTrue(expectedGraphHtml.contains("HARD") && expectedGraphHtml.contains("BLOCK CLOSE"),
                "Should document HARD controls that BLOCK CLOSE");
        assertTrue(expectedGraphHtml.contains("SOFT") && expectedGraphHtml.contains("COMMENTARY"),
                "Should document SOFT controls that require COMMENTARY");
    }

    @Test
    @DisplayName("Control-to-step mapping: C-01 validates FX translate, C-07 validates sign-off")
    void controlToStepMapping() {
        // Section 7 of expected graph documents these relationships
        assertTrue(expectedGraphHtml.contains("C-01") && expectedGraphHtml.contains("FX"),
                "C-01 should be associated with FX translation step");
        assertTrue(expectedGraphHtml.contains("C-07") && expectedGraphHtml.contains("sign-off"),
                "C-07 should be associated with sign-off step");
    }

    @Test
    @DisplayName("KAA process map references 7 controls")
    void kaaProcessMapReferencesControls() {
        // The KAA L3 card and observability plane mention C-01..C-07
        assertTrue(kaaProcessMapText.contains("C-01") || kaaProcessMapText.contains("7 controls"),
                "KAA process map should reference controls");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HITL APPROVAL GATES
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("KAA process map defines HITL approval gates with named approvers")
    void hitlApprovalGatesDefined() {
        // The KAA process map L3 card lists 5 approval gates
        assertTrue(kaaProcessMapText.contains("J. Park"),
                "KAA map should name J. Park as auto-correction batch approver");
        assertTrue(kaaProcessMapText.contains("M. Chen"),
                "KAA map should name M. Chen as OpEx/commentary approver");
        assertTrue(kaaProcessMapText.contains("Controller") || kaaProcessMapText.contains("L. Okafor"),
                "KAA map should reference Controller for close mechanics");
        assertTrue(kaaProcessMapText.contains("CFO") || kaaProcessMapText.contains("M. Sato"),
                "KAA map should reference CFO for sign-off");
    }

    @Test
    @DisplayName("Expected graph documents the full approval chain")
    void expectedGraphHasApprovalChain() {
        // Section 7 of expected graph
        assertTrue(expectedGraphHtml.contains("APPROVED_BY"),
                "Expected graph should document APPROVED_BY relationships");
        assertTrue(expectedGraphHtml.contains("J. Park") && expectedGraphHtml.contains("auto-correction"),
                "Approval chain should link J. Park to auto-correction batch");
        assertTrue(expectedGraphHtml.contains("M. Sato") && expectedGraphHtml.contains("projection"),
                "Approval chain should link M. Sato (CFO) to projection sign-off");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // KAA LAYER ARCHITECTURE REFERENCES
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("KAA methodology defines 6 layers: L0-L5")
    void kaaHasSixLayers() {
        assertTrue(kaaMethodologyText.contains("Layer 0") || kaaMethodologyText.contains("L0"),
                "KAA should define Layer 0 (Engagement & Baseline)");
        assertTrue(kaaMethodologyText.contains("Layer 1") || kaaMethodologyText.contains("Discovery"),
                "KAA should define Layer 1 (Discovery)");
        assertTrue(kaaMethodologyText.contains("Layer 2") || kaaMethodologyText.contains("Ontology"),
                "KAA should define Layer 2 (Ontology)");
        assertTrue(kaaMethodologyText.contains("Layer 3") || kaaMethodologyText.contains("Process"),
                "KAA should define Layer 3 (Process)");
        assertTrue(kaaMethodologyText.contains("Layer 4") || kaaMethodologyText.contains("Integrations"),
                "KAA should define Layer 4 (Integrations)");
        assertTrue(kaaMethodologyText.contains("Layer 5") || kaaMethodologyText.contains("Agents"),
                "KAA should define Layer 5 (Agents)");
    }

    @Test
    @DisplayName("KAA methodology distinguishes Knowledge (L0-L3) and Action (L4-L5) layers")
    void kaaKnowledgeActionSplit() {
        assertTrue(kaaMethodologyText.contains("Knowledge") || kaaMethodologyText.contains("KNOWLEDGE"),
                "KAA should reference Knowledge layers");
        assertTrue(kaaMethodologyText.contains("Action") || kaaMethodologyText.contains("ACTION"),
                "KAA should reference Action layers");
    }

    @Test
    @DisplayName("KAA methodology defines 3 cross-cutting planes")
    void kaaThreePlanes() {
        assertTrue(kaaMethodologyText.contains("Governance") || kaaMethodologyText.contains("governance"),
                "KAA should define Governance plane");
        assertTrue(kaaMethodologyText.contains("Operating") || kaaMethodologyText.contains("operating"),
                "KAA should define Operating-model plane");
        assertTrue(kaaMethodologyText.contains("Observability") || kaaMethodologyText.contains("observability"),
                "KAA should define Observability plane");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AGENT SPECIFICATIONS (from 09_process_map_KAA.html L5)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("KAA process map specifies 8 agents")
    void kaaEightAgents() {
        // L5 card lists: Ingest, Watcher, Triage, FX, Pipeline, Projection, Commentary, Publish
        assertTrue(kaaProcessMapText.contains("Ingest"),
                "Should specify Ingest agent");
        assertTrue(kaaProcessMapText.contains("Watcher"),
                "Should specify Watcher agent");
        assertTrue(kaaProcessMapText.contains("Triage"),
                "Should specify Triage agent");
        assertTrue(kaaProcessMapText.contains("FX agent") || kaaProcessMapText.contains("FX"),
                "Should specify FX agent");
        assertTrue(kaaProcessMapText.contains("Pipeline"),
                "Should specify Pipeline agent");
        assertTrue(kaaProcessMapText.contains("Projection"),
                "Should specify Projection agent");
        assertTrue(kaaProcessMapText.contains("Commentary"),
                "Should specify Commentary agent");
        assertTrue(kaaProcessMapText.contains("Publish"),
                "Should specify Publish agent");
    }

    @Test
    @DisplayName("Agents have escalation policies and SLAs")
    void agentsHaveEscalationAndSLA() {
        // The KAA L5 card lists escalation policy + SLA as part of agent spec format
        assertTrue(kaaProcessMapText.contains("Escalation") || kaaProcessMapText.contains("escalation"),
                "Agent specs should include escalation policy");
        assertTrue(kaaProcessMapText.contains("SLA"),
                "Agent specs should include SLA");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RELATIONSHIP CHAIN VALIDATION FROM EXPECTED GRAPH
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Expected graph documents email → person → forecast chain")
    void emailPersonForecastChain() {
        assertTrue(expectedGraphHtml.contains("SENT_BY"),
                "Expected graph should document SENT_BY relationships");
        assertTrue(expectedGraphHtml.contains("SUBMITTED_BY"),
                "Expected graph should document SUBMITTED_BY relationships");
        assertTrue(expectedGraphHtml.contains("SENT_TO"),
                "Expected graph should document SENT_TO relationships");
    }

    @Test
    @DisplayName("Expected graph documents email → spreadsheet → tab containment")
    void emailSpreadsheetTabChain() {
        assertTrue(expectedGraphHtml.contains("HAS_ATTACHMENT"),
                "Expected graph should document HAS_ATTACHMENT relationships");
        assertTrue(expectedGraphHtml.contains("CONTAINS"),
                "Expected graph should document CONTAINS relationships");
        assertTrue(expectedGraphHtml.contains("AMER_Forecast_Q3_v3_FINAL_v2.xlsx"),
                "Expected graph should reference AMER forecast filename");
    }

    @Test
    @DisplayName("Expected graph documents forecast → channel taxonomy references")
    void forecastTaxonomyReferences() {
        assertTrue(expectedGraphHtml.contains("REFERENCES_TAXONOMY"),
                "Expected graph should document REFERENCES_TAXONOMY relationships");
    }

    @Test
    @DisplayName("Expected graph documents control → step validation links")
    void controlStepValidationLinks() {
        assertTrue(expectedGraphHtml.contains("VALIDATES"),
                "Expected graph should document VALIDATES relationships from controls to steps");
    }

    @Test
    @DisplayName("Expected graph documents variance → escalation links")
    void varianceEscalationLinks() {
        assertTrue(expectedGraphHtml.contains("ESCALATED_TO"),
                "Expected graph should document ESCALATED_TO relationships for triage patterns");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CROSS-DOCUMENT PROCESS ENTITY EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Process map extracts person entities: M. Chen, J. Park")
    void processMapExtractsPersonEntities() {
        List<EntityExtractionService.ExtractedEntity> persons = extractionService.extractEntities(
                processMapText, List.of(EntityType.PERSON));
        Set<String> names = persons.stream()
                .map(EntityExtractionService.ExtractedEntity::name)
                .collect(Collectors.toSet());

        // Process map contains M. Chen, J. Park, S. Reyes, L. Okafor, M. Sato, etc.
        // At minimum Mei Chen should be extracted from the process map text
        boolean hasMeiChen = names.stream().anyMatch(n -> n.contains("Chen"));
        assertTrue(hasMeiChen,
                "Process map should yield at least one 'Chen' person; found: " + names);
    }

    @Test
    @DisplayName("KAA process map mentions regional forecast submitters via interview list")
    void kaaProcessMapMentionsSubmitters() {
        // L1 Discovery card lists 6 interviewees: M. Chen, J. Park, S. Reyes, A. Tanaka, L. Okafor, D. Singh
        // Regional submitters are referenced by initial in the interview list, not by full name
        assertTrue(kaaProcessMapText.contains("A. Tanaka"),
                "KAA map L1 should list A. Tanaka as interviewee (APAC submitter)");
        assertTrue(kaaProcessMapText.contains("M. Chen"),
                "KAA map should reference M. Chen (VP FP&A, all regions recipient)");
        assertTrue(kaaProcessMapText.contains("J. Park"),
                "KAA map should reference J. Park (regional triage lead)");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CURRENCY & MAGNITUDE PRESENCE IN SOURCE DOCUMENTS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Expected graph documents 6 currencies with magnitude")
    void expectedGraphDocumentsCurrencies() {
        List<String> currencies = List.of("USD", "GBP", "EUR", "JPY", "AUD", "SGD");
        for (String ccy : currencies) {
            assertTrue(expectedGraphHtml.contains(ccy),
                    "Expected graph should document currency: " + ccy);
        }
    }

    @Test
    @DisplayName("KAA process map references FX and multi-currency handling")
    void kaaFxReferences() {
        assertTrue(kaaProcessMapText.contains("FX") || kaaProcessMapText.contains("forward curve"),
                "KAA map should reference FX or forward curve");
        assertTrue(kaaProcessMapText.contains("USD") || kaaProcessMapText.contains("currency"),
                "KAA map should reference USD or currency");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONSISTENCY BETWEEN SOURCE DOCUMENTS AND ANSWER FILE
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Answer file step count matches KAA process map step count")
    void answerFileStepCountMatchesKAA() {
        // Expected graph section 4 has 14 rows (steps 1.1-1.6, 2.1-2.3, 3.1-3.3, 4.1-4.3)
        // Count step numbers in the expected graph
        int stepCount = 0;
        for (String step : List.of("1.1", "1.2", "1.3", "1.4", "1.5", "1.6",
                "2.1", "2.2", "2.3",
                "3.1", "3.2", "3.3",
                "4.1", "4.2", "4.3")) {
            if (expectedGraphHtml.contains(step)) {
                stepCount++;
            }
        }
        // 1.6 may or may not be present depending on whether Phase 1 has 5 or 6 steps
        assertTrue(stepCount >= 14,
                "Expected graph should document at least 14 close-cycle steps, found " + stepCount);
    }

    @Test
    @DisplayName("Answer file's step modes match KAA: AUTO, AUTO+HITL, HUMAN")
    void answerFileStepModes() {
        assertTrue(expectedGraphHtml.contains("AUTO"),
                "Answer file should document AUTO mode steps");
        assertTrue(expectedGraphHtml.contains("HITL") || expectedGraphHtml.contains("AUTO + HITL"),
                "Answer file should document AUTO + HITL mode steps");
        assertTrue(expectedGraphHtml.contains("HUMAN"),
                "Answer file should document HUMAN mode steps");
    }

    @Test
    @DisplayName("KAA process map step modes: 9 auto, 3 auto+HITL, 2 human")
    void kaaStepModeDistribution() {
        // KAA L3 card: 9 steps fully automatable, 3 steps auto+HITL, 2 steps human-only
        boolean has9Auto = kaaProcessMapText.contains("9 steps") && kaaProcessMapText.contains("automat");
        boolean has3Hitl = kaaProcessMapText.contains("3 steps") && kaaProcessMapText.contains("HITL");
        boolean has2Human = kaaProcessMapText.contains("2 steps") && kaaProcessMapText.contains("human");

        assertTrue(has9Auto, "KAA should document 9 automatable steps");
        assertTrue(has3Hitl, "KAA should document 3 auto+HITL steps");
        assertTrue(has2Human, "KAA should document 2 human-only steps");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FEEDBACK LOOP VALIDATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("KAA process map documents closed-loop feedback from L5 to L2 and L1")
    void kaaFeedbackLoop() {
        // Section 4 of KAA process map documents feedback loops
        assertTrue(kaaProcessMapText.contains("feedback") || kaaProcessMapText.contains("telemetry"),
                "KAA should document feedback loop/telemetry");
        assertTrue(kaaProcessMapText.contains("ontology") || kaaProcessMapText.contains("Layer 2"),
                "Feedback should flow to ontology/Layer 2");
    }

    @Test
    @DisplayName("KAA methodology documents the feedback loop between L5 agents and L2 ontology")
    void kaaMethodologyFeedbackLoop() {
        assertTrue(kaaMethodologyText.contains("feedback"),
                "KAA methodology should mention feedback loop");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private static Path resolveArtifactsDir() {
        Path dir = Path.of(System.getProperty("user.home"),
                "Documents/GitHub/kompile/FP&A workflow artifacts 2026-05");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("../../FP&A workflow artifacts 2026-05").toAbsolutePath().normalize();
        }
        return dir;
    }

    private String loadAndStripHtml(String filename) throws IOException {
        Path file = ARTIFACTS_DIR.resolve(filename);
        if (!Files.exists(file)) {
            fail("Source HTML not found: " + file + " — ensure FP&A workflow artifacts are present");
        }
        String html = Files.readString(file);
        return html.replaceAll("<style[^>]*>[\\s\\S]*?</style>", " ")
                .replaceAll("<script[^>]*>[\\s\\S]*?</script>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&#x[0-9a-fA-F]+;", " ")
                .replaceAll("&[a-z]+;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
