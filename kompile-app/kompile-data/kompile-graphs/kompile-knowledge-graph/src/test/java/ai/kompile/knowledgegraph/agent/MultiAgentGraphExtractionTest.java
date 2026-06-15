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
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.AgentContribution;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.GraphMergeStrategy;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.MergedGraphResult;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionConfig;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionResult;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.AgentMetrics;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for multi-agent graph extraction using different agent types
 * (structural, pattern-based, LLM-simulated) on Excel-style and PDF-style document
 * chunks. Exercises {@link DefaultMultiAgentGraphBuilder} with all four merge
 * strategies and verifies per-agent contribution tracking.
 */
class MultiAgentGraphExtractionTest {

    private DefaultMultiAgentGraphBuilder builder;

    // --- Reusable document chunks simulating Excel and PDF content ---

    private List<RetrievedDoc> excelChunks;
    private List<RetrievedDoc> pdfChunks;
    private List<RetrievedDoc> mixedChunks;

    @BeforeEach
    void setUp() {
        builder = new DefaultMultiAgentGraphBuilder();

        excelChunks = List.of(
                new RetrievedDoc("excel-1",
                        "Sheet: Revenue | Cell B2: Q1 Revenue = 50000 | Cell B3: Q2 Revenue = 62000 | "
                                + "Cell B4: Total Revenue = SUM(B2:B3) = 112000",
                        Map.of("source", "financials.xlsx", "content_type", "table",
                                "source_filename", "financials.xlsx", "loader", "ExcelLoader")),
                new RetrievedDoc("excel-2",
                        "Sheet: Expenses | Cell B2: Salaries = 35000 | Cell B3: Marketing = 12000 | "
                                + "Cell B4: Total Expenses = SUM(B2:B3) = 47000 | "
                                + "Cell B5: Net Profit = Revenue!B4 - B4 = 65000",
                        Map.of("source", "financials.xlsx", "content_type", "formula_graph",
                                "source_filename", "financials.xlsx", "loader", "ExcelLoader"))
        );

        pdfChunks = List.of(
                new RetrievedDoc("pdf-1",
                        "Acme Corporation Annual Report 2024. The company was founded by John Smith "
                                + "in San Francisco. CEO Jane Doe led the expansion into European markets, "
                                + "establishing offices in London and Berlin.",
                        Map.of("source", "annual_report.pdf", "content_type", "text",
                                "source_filename", "annual_report.pdf", "loader", "PdfExtendedLoader",
                                "pageNumber", 1)),
                new RetrievedDoc("pdf-2",
                        "The partnership between Acme Corporation and TechPartners Inc. resulted in "
                                + "a joint venture called AcmeTech Solutions, headquartered in New York. "
                                + "Dr. Robert Chen serves as the CTO of AcmeTech Solutions.",
                        Map.of("source", "annual_report.pdf", "content_type", "text",
                                "source_filename", "annual_report.pdf", "loader", "PdfExtendedLoader",
                                "pageNumber", 2)),
                new RetrievedDoc("pdf-3",
                        "Revenue grew 23% year-over-year, driven by the European expansion. "
                                + "The London office contributed 15M in revenue while Berlin added 8M. "
                                + "Total global revenue reached 112M for fiscal year 2024.",
                        Map.of("source", "annual_report.pdf", "content_type", "text",
                                "source_filename", "annual_report.pdf", "loader", "PdfExtendedLoader",
                                "pageNumber", 3))
        );

        mixedChunks = new ArrayList<>();
        mixedChunks.addAll(excelChunks);
        mixedChunks.addAll(pdfChunks);
    }

    // =====================================================================
    // Mock Agent Implementations
    // =====================================================================

    /**
     * Simulates a structural agent that extracts spreadsheet structure:
     * cells, formulas, sheets, and their dependencies.
     */
    static class StructuralExcelAgent implements RelationExtractionAgent {
        @Override
        public String getId() { return "structural-excel"; }

        @Override
        public String getDescription() { return "Extracts spreadsheet structure and formula dependencies"; }

        @Override
        public Set<String> supportedContentTypes() { return Set.of("table", "formula_graph"); }

        @Override
        public ExtractionResult extract(List<RetrievedDoc> chunks, ExtractionConfig config) {
            long start = System.currentTimeMillis();
            Graph graph = new Graph();
            List<Entity> entities = new ArrayList<>();
            List<Relationship> relationships = new ArrayList<>();

            for (RetrievedDoc chunk : chunks) {
                String text = chunk.getText();

                // Extract sheet entities
                if (text.contains("Sheet:")) {
                    String[] parts = text.split("Sheet: ");
                    for (int i = 1; i < parts.length; i++) {
                        String sheetName = parts[i].split("[|]")[0].trim();
                        Entity sheet = new Entity();
                        sheet.setId("sheet-" + sheetName.toLowerCase().replaceAll("\\s+", "_"));
                        sheet.setTitle(sheetName);
                        sheet.setType("SHEET");
                        sheet.setDescription("Spreadsheet sheet: " + sheetName);
                        sheet.setConfidence(0.95);
                        entities.add(sheet);
                    }
                }

                // Extract formula cells as FORMULA_CELL entities
                if (text.contains("SUM") || text.contains("=")) {
                    Entity formulaEntity = new Entity();
                    formulaEntity.setId("formula-" + chunk.getId());
                    formulaEntity.setTitle("Formula in " + chunk.getId());
                    formulaEntity.setType("FORMULA_CELL");
                    formulaEntity.setDescription("Contains computed formula references");
                    formulaEntity.setConfidence(0.9);
                    entities.add(formulaEntity);
                }

                // Extract cross-sheet dependency
                if (text.contains("Revenue!")) {
                    Relationship crossSheet = new Relationship();
                    crossSheet.setSource("sheet-expenses");
                    crossSheet.setTarget("sheet-revenue");
                    crossSheet.setType("CROSS_SHEET_DEPENDS_ON");
                    crossSheet.setDescription("Expenses sheet references Revenue sheet");
                    crossSheet.setConfidence(0.95);
                    relationships.add(crossSheet);
                }
            }

            graph.setEntities(entities);
            graph.setRelationships(relationships);

            long elapsed = System.currentTimeMillis() - start;
            return new ExtractionResult(graph, new AgentMetrics(
                    getId(), elapsed, entities.size(), relationships.size(),
                    chunks.size(), null, Map.of("agentType", "structural")));
        }
    }

    /**
     * Simulates a pattern-based NER agent that extracts named entities
     * using simple string matching (PERSON, ORGANIZATION, LOCATION).
     */
    static class PatternNerAgent implements RelationExtractionAgent {
        @Override
        public String getId() { return "pattern-ner"; }

        @Override
        public String getDescription() { return "Pattern-based named entity recognition"; }

        @Override
        public Set<String> supportedContentTypes() { return Set.of(); } // handles any

        @Override
        public ExtractionResult extract(List<RetrievedDoc> chunks, ExtractionConfig config) {
            long start = System.currentTimeMillis();
            Graph graph = new Graph();
            List<Entity> entities = new ArrayList<>();
            List<Relationship> relationships = new ArrayList<>();

            // Known entity patterns
            Map<String, String> knownPersons = Map.of(
                    "John Smith", "person-john-smith",
                    "Jane Doe", "person-jane-doe",
                    "Robert Chen", "person-robert-chen"
            );
            Map<String, String> knownOrgs = Map.of(
                    "Acme Corporation", "org-acme",
                    "TechPartners Inc", "org-techpartners",
                    "AcmeTech Solutions", "org-acmetech"
            );
            Map<String, String> knownLocations = Map.of(
                    "San Francisco", "loc-san-francisco",
                    "London", "loc-london",
                    "Berlin", "loc-berlin",
                    "New York", "loc-new-york"
            );

            Set<String> seenEntities = new HashSet<>();

            for (RetrievedDoc chunk : chunks) {
                String text = chunk.getText();

                for (Map.Entry<String, String> entry : knownPersons.entrySet()) {
                    if (text.contains(entry.getKey()) && seenEntities.add(entry.getValue())) {
                        Entity e = new Entity();
                        e.setId(entry.getValue());
                        e.setTitle(entry.getKey());
                        e.setType("PERSON");
                        e.setDescription("Person extracted via pattern matching");
                        e.setConfidence(0.8);
                        entities.add(e);
                    }
                }

                for (Map.Entry<String, String> entry : knownOrgs.entrySet()) {
                    if (text.contains(entry.getKey()) && seenEntities.add(entry.getValue())) {
                        Entity e = new Entity();
                        e.setId(entry.getValue());
                        e.setTitle(entry.getKey());
                        e.setType("ORGANIZATION");
                        e.setDescription("Organization extracted via pattern matching");
                        e.setConfidence(0.85);
                        entities.add(e);
                    }
                }

                for (Map.Entry<String, String> entry : knownLocations.entrySet()) {
                    if (text.contains(entry.getKey()) && seenEntities.add(entry.getValue())) {
                        Entity e = new Entity();
                        e.setId(entry.getValue());
                        e.setTitle(entry.getKey());
                        e.setType("LOCATION");
                        e.setDescription("Location extracted via pattern matching");
                        e.setConfidence(0.75);
                        entities.add(e);
                    }
                }

                // Extract LOCATED_IN relationships
                if (text.contains("San Francisco") && text.contains("Acme Corporation")) {
                    Relationship r = new Relationship();
                    r.setSource("org-acme");
                    r.setTarget("loc-san-francisco");
                    r.setType("LOCATED_IN");
                    r.setConfidence(0.8);
                    relationships.add(r);
                }
                if (text.contains("New York") && text.contains("AcmeTech Solutions")) {
                    Relationship r = new Relationship();
                    r.setSource("org-acmetech");
                    r.setTarget("loc-new-york");
                    r.setType("HEADQUARTERED_IN");
                    r.setConfidence(0.85);
                    relationships.add(r);
                }
            }

            graph.setEntities(entities);
            graph.setRelationships(relationships);

            long elapsed = System.currentTimeMillis() - start;
            return new ExtractionResult(graph, new AgentMetrics(
                    getId(), elapsed, entities.size(), relationships.size(),
                    chunks.size(), null, Map.of("agentType", "pattern")));
        }
    }

    /**
     * Simulates an LLM-based agent that extracts rich semantic relationships.
     * Returns pre-built graphs as if an LLM had produced them.
     */
    static class SimulatedLlmAgent implements RelationExtractionAgent {
        @Override
        public String getId() { return "llm-simulated"; }

        @Override
        public String getDescription() { return "Simulated LLM-based relation extraction"; }

        @Override
        public Set<String> supportedContentTypes() { return Set.of(); }

        @Override
        public ExtractionResult extract(List<RetrievedDoc> chunks, ExtractionConfig config) {
            long start = System.currentTimeMillis();
            Graph graph = new Graph();
            List<Entity> entities = new ArrayList<>();
            List<Relationship> relationships = new ArrayList<>();

            boolean hasAcme = chunks.stream().anyMatch(c -> c.getText().contains("Acme"));
            boolean hasFinancials = chunks.stream().anyMatch(c -> c.getText().contains("Revenue"));

            if (hasAcme) {
                // LLM extracts same entities as pattern agent but with different confidence
                Entity acme = new Entity();
                acme.setId("org-acme");
                acme.setTitle("Acme Corporation");
                acme.setType("ORGANIZATION");
                acme.setDescription("A global corporation with European expansion");
                acme.setConfidence(0.92);
                entities.add(acme);

                Entity jane = new Entity();
                jane.setId("person-jane-doe");
                jane.setTitle("Jane Doe");
                jane.setType("PERSON");
                jane.setDescription("CEO of Acme Corporation, led European expansion");
                jane.setConfidence(0.88);
                entities.add(jane);

                Entity john = new Entity();
                john.setId("person-john-smith");
                john.setTitle("John Smith");
                john.setType("PERSON");
                john.setDescription("Founder of Acme Corporation");
                john.setConfidence(0.9);
                entities.add(john);

                // LLM finds richer relationships
                Relationship founded = new Relationship();
                founded.setSource("person-john-smith");
                founded.setTarget("org-acme");
                founded.setType("FOUNDED");
                founded.setDescription("John Smith founded Acme Corporation");
                founded.setConfidence(0.9);
                relationships.add(founded);

                Relationship ceoOf = new Relationship();
                ceoOf.setSource("person-jane-doe");
                ceoOf.setTarget("org-acme");
                ceoOf.setType("CEO_OF");
                ceoOf.setDescription("Jane Doe is CEO of Acme Corporation");
                ceoOf.setConfidence(0.92);
                relationships.add(ceoOf);

                // Also finds the LOCATED_IN that pattern agent found
                Relationship located = new Relationship();
                located.setSource("org-acme");
                located.setTarget("loc-san-francisco");
                located.setType("LOCATED_IN");
                located.setDescription("Acme Corporation is located in San Francisco");
                located.setConfidence(0.88);
                relationships.add(located);

                // San Francisco entity (overlaps with pattern agent)
                Entity sf = new Entity();
                sf.setId("loc-san-francisco");
                sf.setTitle("San Francisco");
                sf.setType("LOCATION");
                sf.setDescription("City in California, location of Acme HQ");
                sf.setConfidence(0.85);
                entities.add(sf);
            }

            if (hasFinancials) {
                // LLM extracts financial concepts
                Entity revenue = new Entity();
                revenue.setId("concept-revenue");
                revenue.setTitle("Revenue");
                revenue.setType("CONCEPT");
                revenue.setDescription("Total revenue metric, 112M for FY2024");
                revenue.setConfidence(0.85);
                entities.add(revenue);

                Entity growth = new Entity();
                growth.setId("concept-growth");
                growth.setTitle("23% YoY Growth");
                growth.setType("EVENT");
                growth.setDescription("Revenue grew 23% year-over-year");
                growth.setConfidence(0.82);
                entities.add(growth);
            }

            graph.setEntities(entities);
            graph.setRelationships(relationships);

            long elapsed = System.currentTimeMillis() - start;
            return new ExtractionResult(graph, new AgentMetrics(
                    getId(), elapsed, entities.size(), relationships.size(),
                    chunks.size(), "simulated-gpt-4", Map.of("agentType", "llm")));
        }
    }

    /**
     * Agent that always throws to test error resilience.
     */
    static class FailingAgent implements RelationExtractionAgent {
        @Override
        public String getId() { return "failing-agent"; }

        @Override
        public String getDescription() { return "Agent that always fails"; }

        @Override
        public Set<String> supportedContentTypes() { return Set.of(); }

        @Override
        public ExtractionResult extract(List<RetrievedDoc> chunks, ExtractionConfig config) {
            throw new RuntimeException("Simulated agent failure: API timeout");
        }
    }

    // =====================================================================
    // UNION Strategy Tests
    // =====================================================================

    @Test
    void unionMergeIncludesAllEntitiesFromAllAgents() {
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent, llmAgent),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        Graph merged = result.mergedGraph();
        assertNotNull(merged);
        assertNotNull(merged.getEntities());
        assertNotNull(merged.getRelationships());

        // UNION should include all unique entities from both agents
        Set<String> entityIds = new HashSet<>();
        for (Entity e : merged.getEntities()) {
            entityIds.add(e.getId());
        }

        // Both agents extract these (overlap)
        assertTrue(entityIds.contains("org-acme"), "Should contain Acme from both agents");
        assertTrue(entityIds.contains("person-jane-doe"), "Should contain Jane Doe");
        assertTrue(entityIds.contains("person-john-smith"), "Should contain John Smith");
        assertTrue(entityIds.contains("loc-san-francisco"), "Should contain San Francisco");

        // Pattern agent uniquely extracts these
        assertTrue(entityIds.contains("org-techpartners"), "Should contain TechPartners (pattern only)");
        assertTrue(entityIds.contains("org-acmetech"), "Should contain AcmeTech (pattern only)");
        assertTrue(entityIds.contains("loc-london"), "Should contain London (pattern only)");
        assertTrue(entityIds.contains("loc-berlin"), "Should contain Berlin (pattern only)");
        assertTrue(entityIds.contains("loc-new-york"), "Should contain New York (pattern only)");
        assertTrue(entityIds.contains("person-robert-chen"), "Should contain Robert Chen (pattern only)");

        // LLM agent uniquely extracts these
        assertTrue(entityIds.contains("concept-revenue"), "Should contain Revenue concept (LLM only)");
        assertTrue(entityIds.contains("concept-growth"), "Should contain Growth event (LLM only)");

        // Relationships should include both pattern and LLM outputs
        Set<String> relTypes = new HashSet<>();
        for (Relationship r : merged.getRelationships()) {
            relTypes.add(r.getType());
        }
        assertTrue(relTypes.contains("LOCATED_IN"), "Should have LOCATED_IN from both agents");
        assertTrue(relTypes.contains("FOUNDED"), "Should have FOUNDED from LLM");
        assertTrue(relTypes.contains("CEO_OF"), "Should have CEO_OF from LLM");
        assertTrue(relTypes.contains("HEADQUARTERED_IN"), "Should have HEADQUARTERED_IN from pattern");
    }

    @Test
    void unionMergeKeepsHigherConfidenceOnDuplicates() {
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent, llmAgent),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        // Both agents extract "org-acme": pattern=0.85, LLM=0.92
        Entity acme = result.mergedGraph().getEntities().stream()
                .filter(e -> "org-acme".equals(e.getId()))
                .findFirst().orElseThrow();
        assertEquals(0.92, acme.getConfidence(), 0.001,
                "UNION should keep higher confidence (LLM's 0.92 > pattern's 0.85)");

        // Both agents extract "person-john-smith": pattern=0.8, LLM=0.9
        Entity john = result.mergedGraph().getEntities().stream()
                .filter(e -> "person-john-smith".equals(e.getId()))
                .findFirst().orElseThrow();
        assertEquals(0.9, john.getConfidence(), 0.001,
                "UNION should keep higher confidence for John Smith");
    }

    @Test
    void unionMergeWithExcelAndPdfAgents() {
        StructuralExcelAgent excelAgent = new StructuralExcelAgent();
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                mixedChunks,
                List.of(excelAgent, patternAgent, llmAgent),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        // Should have structural entities from Excel + NER entities from PDF + LLM concepts
        Set<String> entityTypes = new HashSet<>();
        for (Entity e : result.mergedGraph().getEntities()) {
            entityTypes.add(e.getType());
        }

        assertTrue(entityTypes.contains("SHEET"), "Should have SHEET entities from structural agent");
        assertTrue(entityTypes.contains("FORMULA_CELL"), "Should have FORMULA_CELL from structural agent");
        assertTrue(entityTypes.contains("PERSON"), "Should have PERSON entities from NER/LLM");
        assertTrue(entityTypes.contains("ORGANIZATION"), "Should have ORGANIZATION from NER/LLM");
        assertTrue(entityTypes.contains("LOCATION"), "Should have LOCATION from NER/LLM");

        // Verify three agents contributed
        assertEquals(3, result.contributions().size());
        assertTrue(result.contributions().containsKey("structural-excel"));
        assertTrue(result.contributions().containsKey("pattern-ner"));
        assertTrue(result.contributions().containsKey("llm-simulated"));
    }

    // =====================================================================
    // INTERSECTION Strategy Tests
    // =====================================================================

    @Test
    void intersectionKeepsOnlyEntitiesFoundByMultipleAgents() {
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent, llmAgent),
                GraphMergeStrategy.INTERSECTION,
                ExtractionConfig.defaults()
        );

        Graph merged = result.mergedGraph();
        Set<String> entityIds = new HashSet<>();
        for (Entity e : merged.getEntities()) {
            entityIds.add(e.getId());
        }

        // Both agents extract these (should survive intersection)
        assertTrue(entityIds.contains("org-acme"), "org-acme found by both agents");
        assertTrue(entityIds.contains("person-jane-doe"), "Jane Doe found by both agents");
        assertTrue(entityIds.contains("person-john-smith"), "John Smith found by both agents");
        assertTrue(entityIds.contains("loc-san-francisco"), "San Francisco found by both agents");

        // Only pattern agent extracts these (should be removed)
        assertFalse(entityIds.contains("org-techpartners"), "TechPartners only in pattern agent");
        assertFalse(entityIds.contains("loc-london"), "London only in pattern agent");
        assertFalse(entityIds.contains("loc-berlin"), "Berlin only in pattern agent");
        assertFalse(entityIds.contains("person-robert-chen"), "Robert Chen only in pattern agent");

        // Only LLM extracts these (should be removed)
        assertFalse(entityIds.contains("concept-revenue"), "Revenue concept only in LLM");
        assertFalse(entityIds.contains("concept-growth"), "Growth event only in LLM");
    }

    @Test
    void intersectionRemovesOrphanedRelationships() {
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent, llmAgent),
                GraphMergeStrategy.INTERSECTION,
                ExtractionConfig.defaults()
        );

        Graph merged = result.mergedGraph();

        // LOCATED_IN (org-acme -> loc-san-francisco) appears in both agents => should survive
        boolean hasLocatedIn = merged.getRelationships().stream()
                .anyMatch(r -> "LOCATED_IN".equals(r.getType())
                        && "org-acme".equals(r.getSource())
                        && "loc-san-francisco".equals(r.getTarget()));
        assertTrue(hasLocatedIn, "LOCATED_IN should survive intersection (both agents + both entities retained)");

        // HEADQUARTERED_IN (org-acmetech -> loc-new-york) only in pattern agent
        // AND org-acmetech is removed in intersection => relationship must be gone
        boolean hasHq = merged.getRelationships().stream()
                .anyMatch(r -> "HEADQUARTERED_IN".equals(r.getType()));
        assertFalse(hasHq, "HEADQUARTERED_IN should be removed (entities not in intersection)");
    }

    // =====================================================================
    // FIRST_WINS Strategy Tests
    // =====================================================================

    @Test
    void firstWinsKeepsFirstAgentVersionOnConflict() {
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        // Pattern agent runs first
        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent, llmAgent),
                GraphMergeStrategy.FIRST_WINS,
                ExtractionConfig.defaults()
        );

        // org-acme: pattern has 0.85, LLM has 0.92 — FIRST_WINS keeps pattern's 0.85
        Entity acme = result.mergedGraph().getEntities().stream()
                .filter(e -> "org-acme".equals(e.getId()))
                .findFirst().orElseThrow();
        assertEquals(0.85, acme.getConfidence(), 0.001,
                "FIRST_WINS should keep pattern agent's confidence (first)");

        // person-john-smith: pattern=0.8 wins over LLM=0.9
        Entity john = result.mergedGraph().getEntities().stream()
                .filter(e -> "person-john-smith".equals(e.getId()))
                .findFirst().orElseThrow();
        assertEquals(0.8, john.getConfidence(), 0.001,
                "FIRST_WINS should keep first agent's value");
    }

    @Test
    void firstWinsStillIncludesUniqueEntitiesFromLaterAgents() {
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent, llmAgent),
                GraphMergeStrategy.FIRST_WINS,
                ExtractionConfig.defaults()
        );

        Set<String> entityIds = new HashSet<>();
        for (Entity e : result.mergedGraph().getEntities()) {
            entityIds.add(e.getId());
        }

        // LLM-only entities should still be present (no conflict)
        assertTrue(entityIds.contains("concept-revenue"), "LLM-only entity should be included");
        assertTrue(entityIds.contains("concept-growth"), "LLM-only entity should be included");

        // Pattern-only entities should be present
        assertTrue(entityIds.contains("org-techpartners"), "Pattern-only entity should be included");
    }

    // =====================================================================
    // HIGHEST_CONFIDENCE Strategy Tests
    // =====================================================================

    @Test
    void highestConfidenceKeepsBetterVersion() {
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent, llmAgent),
                GraphMergeStrategy.HIGHEST_CONFIDENCE,
                ExtractionConfig.defaults()
        );

        // Same as UNION — should keep higher confidence
        Entity acme = result.mergedGraph().getEntities().stream()
                .filter(e -> "org-acme".equals(e.getId()))
                .findFirst().orElseThrow();
        assertEquals(0.92, acme.getConfidence(), 0.001,
                "HIGHEST_CONFIDENCE should keep LLM's higher confidence");
    }

    // =====================================================================
    // Per-Agent Contribution Tracking
    // =====================================================================

    @Test
    void contributionTrackingRecordsCorrectCounts() {
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent, llmAgent),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        // Pattern agent contribution
        AgentContribution patternContrib = result.contributions().get("pattern-ner");
        assertNotNull(patternContrib);
        assertEquals("pattern-ner", patternContrib.agentId());
        assertTrue(patternContrib.entitiesExtracted() > 0, "Pattern agent should extract entities");
        assertTrue(patternContrib.relationsExtracted() > 0, "Pattern agent should extract relations");
        // In UNION, all entities should be retained
        assertEquals(patternContrib.entitiesExtracted(), patternContrib.entitiesRetained(),
                "UNION should retain all pattern agent entities");
        assertTrue(patternContrib.entityTypes().contains("PERSON"));
        assertTrue(patternContrib.entityTypes().contains("ORGANIZATION"));
        assertTrue(patternContrib.entityTypes().contains("LOCATION"));

        // LLM agent contribution
        AgentContribution llmContrib = result.contributions().get("llm-simulated");
        assertNotNull(llmContrib);
        assertTrue(llmContrib.entitiesExtracted() > 0);
        assertTrue(llmContrib.entityTypes().contains("CONCEPT") || llmContrib.entityTypes().contains("EVENT"),
                "LLM should find CONCEPT or EVENT types");
    }

    @Test
    void contributionTrackingInIntersectionShowsRetentionDrop() {
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent, llmAgent),
                GraphMergeStrategy.INTERSECTION,
                ExtractionConfig.defaults()
        );

        AgentContribution patternContrib = result.contributions().get("pattern-ner");
        // In intersection, only entities found by 2+ agents survive
        // Pattern finds ~10 entities, but only ~4 overlap with LLM
        assertTrue(patternContrib.entitiesRetained() < patternContrib.entitiesExtracted(),
                "INTERSECTION should retain fewer entities than extracted for pattern agent");

        AgentContribution llmContrib = result.contributions().get("llm-simulated");
        assertTrue(llmContrib.entitiesRetained() < llmContrib.entitiesExtracted(),
                "INTERSECTION should retain fewer entities for LLM (concept/event are unique)");
    }

    // =====================================================================
    // Error Resilience
    // =====================================================================

    @Test
    void failingAgentDoesNotBreakOtherAgents() {
        PatternNerAgent patternAgent = new PatternNerAgent();
        FailingAgent failingAgent = new FailingAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent, failingAgent, llmAgent),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        // Should still have results from the two working agents
        assertTrue(result.totalEntities() > 0, "Should have entities from working agents");
        assertTrue(result.totalRelations() > 0, "Should have relations from working agents");

        // All three agents should have contributions
        assertEquals(3, result.contributions().size());

        // Failing agent should have zero contributions
        AgentContribution failContrib = result.contributions().get("failing-agent");
        assertNotNull(failContrib);
        assertEquals(0, failContrib.entitiesExtracted());
        assertEquals(0, failContrib.relationsExtracted());
        assertEquals(0, failContrib.entitiesRetained());
        assertEquals(0, failContrib.relationsRetained());
    }

    // =====================================================================
    // Mixed Content (Excel + PDF) Multi-Agent Tests
    // =====================================================================

    @Test
    void threeAgentsOnMixedContentProducesComprehensiveGraph() {
        StructuralExcelAgent excelAgent = new StructuralExcelAgent();
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                mixedChunks,
                List.of(excelAgent, patternAgent, llmAgent),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        // Verify comprehensive entity type coverage
        Set<String> allTypes = new HashSet<>();
        for (Entity e : result.mergedGraph().getEntities()) {
            allTypes.add(e.getType());
        }

        // Structural agent types
        assertTrue(allTypes.contains("SHEET"), "Should have SHEET from structural agent");
        assertTrue(allTypes.contains("FORMULA_CELL"), "Should have FORMULA_CELL from structural agent");

        // NER/LLM types
        assertTrue(allTypes.contains("PERSON"), "Should have PERSON from NER/LLM");
        assertTrue(allTypes.contains("ORGANIZATION"), "Should have ORGANIZATION from NER/LLM");
        assertTrue(allTypes.contains("LOCATION"), "Should have LOCATION from NER/LLM");
        assertTrue(allTypes.contains("CONCEPT"), "Should have CONCEPT from LLM");

        // Verify relationship type coverage
        Set<String> allRelTypes = new HashSet<>();
        for (Relationship r : result.mergedGraph().getRelationships()) {
            allRelTypes.add(r.getType());
        }
        assertTrue(allRelTypes.contains("CROSS_SHEET_DEPENDS_ON"), "Should have structural relationship");
        assertTrue(allRelTypes.contains("FOUNDED"), "Should have semantic relationship");
        assertTrue(allRelTypes.contains("CEO_OF"), "Should have role relationship");

        // Verify metadata
        assertEquals(GraphMergeStrategy.UNION, result.strategy());
        assertTrue(result.totalTimeMs() >= 0);
        assertTrue(result.totalEntities() > 0);
        assertTrue(result.totalRelations() > 0);
    }

    @Test
    void intersectionWithThreeAgentsRequiresTwoPlusVotes() {
        StructuralExcelAgent excelAgent = new StructuralExcelAgent();
        PatternNerAgent patternAgent = new PatternNerAgent();
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                mixedChunks,
                List.of(excelAgent, patternAgent, llmAgent),
                GraphMergeStrategy.INTERSECTION,
                ExtractionConfig.defaults()
        );

        Set<String> entityIds = new HashSet<>();
        for (Entity e : result.mergedGraph().getEntities()) {
            entityIds.add(e.getId());
        }

        // Structural entities (SHEET, FORMULA_CELL) are only in excel agent => removed
        assertFalse(entityIds.contains("sheet-revenue"), "SHEET entities only in one agent");
        assertFalse(entityIds.contains("formula-excel-2"), "FORMULA_CELL only in one agent");

        // Entities in both pattern + LLM should survive
        assertTrue(entityIds.contains("org-acme"), "org-acme in pattern + LLM");
        assertTrue(entityIds.contains("person-jane-doe"), "Jane Doe in pattern + LLM");
    }

    // =====================================================================
    // ExtractionConfig Tests
    // =====================================================================

    @Test
    void extractionConfigDefaultsHaveReasonableValues() {
        ExtractionConfig defaults = ExtractionConfig.defaults();
        assertNotNull(defaults.entityTypes());
        assertFalse(defaults.entityTypes().isEmpty());
        assertTrue(defaults.entityTypes().contains("PERSON"));
        assertTrue(defaults.entityTypes().contains("ORGANIZATION"));
        assertTrue(defaults.entityTypes().contains("LOCATION"));
        assertEquals(0.5, defaults.minConfidence(), 0.001);
        assertNotNull(defaults.options());
        assertTrue(defaults.options().isEmpty());
    }

    @Test
    void customExtractionConfigPassedToAgents() {
        // Create a config that an agent could use to filter
        ExtractionConfig config = new ExtractionConfig(
                List.of("PERSON", "ORGANIZATION"),
                List.of("WORKS_AT", "FOUNDED"),
                0.7,
                Map.of("model", "gpt-4", "temperature", 0.1)
        );

        // The agents in this test don't filter by config, but verify the config
        // reaches them and the builder accepts it
        PatternNerAgent patternAgent = new PatternNerAgent();
        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(patternAgent),
                GraphMergeStrategy.UNION,
                config
        );

        assertNotNull(result);
        assertTrue(result.totalEntities() > 0);
    }

    // =====================================================================
    // Edge Cases
    // =====================================================================

    @Test
    void singleAgentReturnsItsGraphUnchanged() {
        SimulatedLlmAgent llmAgent = new SimulatedLlmAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(llmAgent),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        assertEquals(1, result.contributions().size());
        AgentContribution contrib = result.contributions().get("llm-simulated");
        assertEquals(contrib.entitiesExtracted(), contrib.entitiesRetained(),
                "Single agent: all entities should be retained");
        assertEquals(contrib.relationsExtracted(), contrib.relationsRetained(),
                "Single agent: all relations should be retained");
    }

    @Test
    void emptyChunksProduceEmptyGraph() {
        PatternNerAgent patternAgent = new PatternNerAgent();

        MergedGraphResult result = builder.buildGraph(
                List.of(),
                List.of(patternAgent),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        assertEquals(0, result.totalEntities());
        assertEquals(0, result.totalRelations());
    }

    @Test
    void allAgentsFailStillReturnsValidResult() {
        FailingAgent fail1 = new FailingAgent();

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(fail1),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        assertNotNull(result);
        assertNotNull(result.mergedGraph());
        assertEquals(0, result.totalEntities());
        assertEquals(0, result.totalRelations());
    }

    @Test
    void duplicateRelationshipsDeduplicatedByTripleKey() {
        // Two agents that extract the exact same relationship
        SimulatedLlmAgent agent1 = new SimulatedLlmAgent();
        SimulatedLlmAgent agent2 = new SimulatedLlmAgent() {
            @Override
            public String getId() { return "llm-copy"; }
        };

        MergedGraphResult result = builder.buildGraph(
                pdfChunks,
                List.of(agent1, agent2),
                GraphMergeStrategy.UNION,
                ExtractionConfig.defaults()
        );

        // Count LOCATED_IN relationships — should be deduplicated to 1
        long locatedInCount = result.mergedGraph().getRelationships().stream()
                .filter(r -> "LOCATED_IN".equals(r.getType())
                        && "org-acme".equals(r.getSource())
                        && "loc-san-francisco".equals(r.getTarget()))
                .count();
        assertEquals(1, locatedInCount,
                "Duplicate relationships with same (source|target|type) should be deduplicated");
    }

    @Test
    void mergedResultContainsCorrectStrategy() {
        PatternNerAgent agent = new PatternNerAgent();

        for (GraphMergeStrategy strategy : GraphMergeStrategy.values()) {
            MergedGraphResult result = builder.buildGraph(
                    pdfChunks,
                    List.of(agent),
                    strategy,
                    ExtractionConfig.defaults()
            );
            assertEquals(strategy, result.strategy(),
                    "Result should record the strategy that was used: " + strategy);
        }
    }
}
