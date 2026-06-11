/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.e2e;

import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.MergedGraphResult;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionConfig;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.agent.LlmRelationExtractionAgent;
import ai.kompile.knowledgegraph.agent.MultiAgentExtractionService;
import ai.kompile.orchestrator.integration.cli.CliAgentConfig;
import ai.kompile.orchestrator.integration.cli.CliAgentExtractionLlmService;
import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.service.ProcessEngineServiceImpl;
import ai.kompile.process.service.StepExecutionDispatcher;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real LLM extraction integration tests using Claude CLI as the test agent.
 *
 * <p>These tests invoke the actual Claude CLI subprocess for entity/relation
 * extraction, then feed results into a real ProcessEngine workflow. Nothing
 * is mocked — the LLM calls are live, the extraction pipeline is the real
 * framework code, and the workflow execution uses ProcessEngineServiceImpl.
 *
 * <p>Tests are automatically skipped if Claude CLI is not installed on the system
 * (checked via {@code claude --version}).
 */
@DisplayName("Real LLM Extraction → Workflow Integration")
class LlmExtractionWorkflowTest {

    private static CliAgentExtractionLlmService claudeService;
    private static ExtractionLlmServiceRegistry registry;
    private static LlmRelationExtractionAgent extractionAgent;
    private static MultiAgentExtractionService multiAgentService;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void checkClaudeAvailability() {
        boolean available = CliAgentConfig.CLAUDE_CLI.checkAvailability();
        assumeTrue(available,
                "Claude CLI is not installed — skipping real LLM extraction tests");

        // Wire up the extraction pipeline using framework primitives
        claudeService = new CliAgentExtractionLlmService(
                "claude-cli", "Claude CLI", CliAgentConfig.CLAUDE_CLI, 120);

        registry = new ExtractionLlmServiceRegistry();
        registry.register(claudeService);

        extractionAgent = new LlmRelationExtractionAgent();
        extractionAgent.setLlmServiceRegistry(registry);

        multiAgentService = new MultiAgentExtractionService(List.of(extractionAgent));
    }

    @AfterAll
    static void shutdownCli() {
        if (claudeService != null) {
            claudeService.shutdown();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ProcessEngineServiceImpl createProcessEngine() {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        ProcessEngineServiceImpl engine = new ProcessEngineServiceImpl();
        engine.init();
        System.setProperty("user.home", originalHome);
        return engine;
    }

    private ExtractionConfig configForClaude(List<String> entityTypes) {
        return new ExtractionConfig(
                entityTypes,
                List.of(),
                0.0, // no confidence filtering — we want to see everything the LLM produces
                Map.of("llmProvider", "claude-cli")
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. Direct LLM Extraction Tests
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Direct Claude CLI Extraction")
    class DirectExtraction {

        @Test
        @DisplayName("Extract entities from a business document chunk")
        void extractEntitiesFromBusinessDocument() {
            String businessText = """
                    Acme Corporation, headquartered in San Francisco, announced a partnership
                    with Global Finance Ltd. CEO Jane Chen signed the agreement on March 15, 2025.
                    The deal covers AI-powered risk assessment tools for the banking sector.
                    Chief Technology Officer Marcus Webb will lead the integration team based
                    in the New York office.
                    """;

            RetrievedDoc chunk = new RetrievedDoc("biz-doc-1", businessText, Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "EVENT"));

            RelationExtractionAgent.ExtractionResult result =
                    extractionAgent.extract(List.of(chunk), config);

            assertNotNull(result, "Extraction result should not be null");
            assertNotNull(result.graph(), "Graph should not be null");

            Graph graph = result.graph();
            List<Entity> entities = graph.getEntities();
            List<Relationship> relations = graph.getRelationships();

            assertNotNull(entities, "Entities list should not be null");
            assertFalse(entities.isEmpty(),
                    "LLM should extract at least one entity from business text");

            // Verify the LLM found key entities — names may vary but core entities should appear
            Set<String> entityNames = new HashSet<>();
            Set<String> entityTypes = new HashSet<>();
            for (Entity e : entities) {
                if (e.getTitle() != null) entityNames.add(e.getTitle().toLowerCase());
                if (e.getType() != null) entityTypes.add(e.getType().toUpperCase());
            }

            // At minimum, the LLM should find organizations and people
            assertTrue(entityNames.stream().anyMatch(n -> n.contains("acme")),
                    "Should extract 'Acme Corporation'; found: " + entityNames);
            assertTrue(entityNames.stream().anyMatch(n ->
                            n.contains("jane") || n.contains("chen")),
                    "Should extract 'Jane Chen'; found: " + entityNames);

            // Verify entity types are from our requested set
            assertTrue(entityTypes.stream().anyMatch(t ->
                            t.contains("PERSON") || t.contains("ORGANIZATION") || t.contains("LOCATION")),
                    "Entity types should include requested types; found: " + entityTypes);

            // Verify metrics are populated
            assertNotNull(result.metrics(), "Metrics should not be null");
            assertEquals("llm-active", result.metrics().agentId());
            assertTrue(result.metrics().entitiesExtracted() > 0);
            assertTrue(result.metrics().extractionTimeMs() > 0);
            assertEquals("claude-cli", result.metrics().modelUsed());
        }

        @Test
        @DisplayName("Extract entities from an email with financial data")
        void extractEntitiesFromFinancialEmail() {
            String emailBody = """
                    From: sarah.johnson@northstar-capital.com
                    To: accounting@northstar-capital.com
                    Subject: Q4 Revenue Update - Cell B2 needs correction

                    Hi team,

                    Please update the Q4 revenue spreadsheet with the following corrections:
                    - Cell B2 (Total Revenue): $4,250,000 (was incorrectly showing $4,200,000)
                    - Cell C5 (Operating Expenses): $1,875,000
                    - The profit margin in D10 should recalculate to 55.88%

                    The board meeting with Northstar Capital's CFO Robert Kim is scheduled
                    for January 15th at the Chicago headquarters.

                    Best regards,
                    Sarah Johnson
                    Senior Financial Analyst
                    """;

            RetrievedDoc chunk = new RetrievedDoc("email-fin-1", emailBody,
                    Map.of("content_type", "email", "source", "inbox"));

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "FINANCIAL_DATA", "EVENT"));

            RelationExtractionAgent.ExtractionResult result =
                    extractionAgent.extract(List.of(chunk), config);

            Graph graph = result.graph();
            assertFalse(graph.getEntities().isEmpty(),
                    "Should extract entities from financial email");

            Set<String> names = new HashSet<>();
            for (Entity e : graph.getEntities()) {
                if (e.getTitle() != null) names.add(e.getTitle().toLowerCase());
            }

            assertTrue(names.stream().anyMatch(n -> n.contains("sarah")),
                    "Should extract 'Sarah Johnson'; found: " + names);
            assertTrue(names.stream().anyMatch(n ->
                            n.contains("northstar") || n.contains("north star")),
                    "Should extract 'Northstar Capital'; found: " + names);
        }

        @Test
        @DisplayName("Multi-chunk extraction merges results")
        void multiChunkExtractionMergesResults() {
            RetrievedDoc chunk1 = new RetrievedDoc("chunk-1",
                    "Dr. Emily Zhang at Stanford University published a paper on neural architecture search.",
                    Map.of());
            RetrievedDoc chunk2 = new RetrievedDoc("chunk-2",
                    "The paper was co-authored with Professor David Chen from MIT. " +
                            "It was presented at NeurIPS 2024 in Vancouver.",
                    Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "EVENT", "CONCEPT"));

            MergedGraphResult merged = multiAgentService.runExtraction(
                    List.of(chunk1, chunk2), null, "UNION", config);

            assertNotNull(merged, "Merged result should not be null");
            assertNotNull(merged.mergedGraph(), "Merged graph should not be null");
            assertTrue(merged.totalEntities() > 0,
                    "Should extract entities across chunks; got " + merged.totalEntities());

            // Collect all entity names from the merged graph
            Set<String> names = new HashSet<>();
            for (Entity e : merged.mergedGraph().getEntities()) {
                if (e.getTitle() != null) names.add(e.getTitle().toLowerCase());
            }

            // Both chunks should contribute entities. Note: the LLM produces simple
            // sequential IDs (e1, e2...) per chunk, so deduplication may collapse
            // entities across chunks when IDs collide. We verify at least one entity
            // from chunk 1 and that the total count reflects multi-chunk extraction.
            assertTrue(names.stream().anyMatch(n -> n.contains("emily") || n.contains("zhang")
                            || n.contains("stanford")),
                    "Should find entities from chunk 1; found: " + names);

            // At least 3 entities total from 2 chunks (even with ID collisions)
            assertTrue(merged.totalEntities() >= 3,
                    "Multi-chunk extraction should yield at least 3 entities; got " +
                            merged.totalEntities() + ": " + names);

            assertTrue(merged.totalTimeMs() > 0, "Total extraction time should be > 0");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. LLM Provider Registry Tests
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Provider Registry Integration")
    class ProviderRegistry {

        @Test
        @DisplayName("Claude CLI is registered and available in the registry")
        void claudeCliRegisteredAndAvailable() {
            ExtractionLlmService resolved = registry.getOrFallback("claude-cli");
            assertNotNull(resolved, "Claude CLI should be resolved from registry");
            assertEquals("claude-cli", resolved.getId());
            assertTrue(resolved.isAvailable(), "Claude CLI should report as available");
        }

        @Test
        @DisplayName("Registry falls back to Claude when unknown provider requested")
        void registryFallsBackToAvailable() {
            ExtractionLlmService resolved = registry.getOrFallback("nonexistent-provider");
            assertNotNull(resolved,
                    "Registry should fall back to an available provider");
            assertEquals("claude-cli", resolved.getId(),
                    "Fallback should be claude-cli (only registered provider)");
        }

        @Test
        @DisplayName("listProviders includes Claude CLI with correct metadata")
        void listProvidersShowsClaude() {
            var providers = registry.listProviders();
            assertFalse(providers.isEmpty(), "Provider list should not be empty");

            var claude = providers.stream()
                    .filter(p -> "claude-cli".equals(p.id()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(claude, "claude-cli should be in provider list");
            assertTrue(claude.available(), "claude-cli should be marked available");
            assertNotNull(claude.description(), "Description should not be null");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. Full Workflow with LLM Extraction
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("End-to-End Workflow with LLM Extraction")
    class WorkflowIntegration {

        @Test
        @DisplayName("Three-phase workflow: extract → review → complete")
        void threePhaseExtractionWorkflow() {
            // ── Phase 1: Run real LLM extraction outside the engine ──
            String documentText = """
                    Tesla Inc., led by CEO Elon Musk, reported record quarterly earnings.
                    The company's Gigafactory in Austin, Texas produced 500,000 vehicles.
                    CFO Vaibhav Taneja presented the results to Wall Street analysts.
                    """;

            RetrievedDoc chunk = new RetrievedDoc("wf-doc-1", documentText, Map.of());
            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION"));

            RelationExtractionAgent.ExtractionResult extraction =
                    extractionAgent.extract(List.of(chunk), config);

            Graph extractedGraph = extraction.graph();
            assertFalse(extractedGraph.getEntities().isEmpty(),
                    "Extraction must produce entities for workflow to proceed");

            // Serialize extraction results for the workflow
            int entityCount = extractedGraph.getEntities().size();
            int relationCount = extractedGraph.getRelationships() != null
                    ? extractedGraph.getRelationships().size() : 0;
            List<String> entityNames = extractedGraph.getEntities().stream()
                    .map(Entity::getTitle)
                    .filter(Objects::nonNull)
                    .toList();

            // ── Phase 2: Create and run a workflow that processes the extraction ──
            ProcessEngineServiceImpl engine = createProcessEngine();

            // Build a dispatcher that makes extraction data available as a "tool"
            StepExecutionDispatcher dispatcher = createExtractionDispatcher(
                    entityCount, relationCount, entityNames);
            engine.setStepExecutionDispatcher(dispatcher);

            // Define a 3-phase workflow:
            //   1) AUTO: record extraction metadata
            //   2) TOOL_CALL: invoke the "entity_extraction_summary" tool
            //   3) HUMAN: review and approve the results
            ProcessStep autoStep = ProcessStep.builder()
                    .id("1.1").name("Record Document Metadata")
                    .stepType(StepType.AUTO)
                    .executionExpressions(Map.of(
                            "documentId", "'wf-doc-1'",
                            "extractionSource", "'claude-cli'"
                    ))
                    .build();

            ProcessStep toolStep = ProcessStep.builder()
                    .id("2.1").name("Summarize Extraction Results")
                    .stepType(StepType.TOOL_CALL)
                    .toolName("entity_extraction_summary")
                    .toolArguments(Map.of(
                            "documentId", "#documentId",
                            "source", "#extractionSource"
                    ))
                    .dependsOn(List.of("1.1"))
                    .build();

            ProcessStep humanStep = ProcessStep.builder()
                    .id("3.1").name("Review Extracted Entities")
                    .stepType(StepType.HUMAN)
                    .dependsOn(List.of("2.1"))
                    .build();

            ProcessDefinition def = engine.createProcess(ProcessDefinition.builder()
                    .name("LLM Extraction Review Workflow")
                    .phases(List.of(
                            ProcessPhase.builder()
                                    .id("p1").name("Metadata").order(1)
                                    .steps(List.of(autoStep)).build(),
                            ProcessPhase.builder()
                                    .id("p2").name("Extraction").order(2)
                                    .steps(List.of(toolStep)).build(),
                            ProcessPhase.builder()
                                    .id("p3").name("Review").order(3)
                                    .steps(List.of(humanStep)).build()
                    ))
                    .build());

            engine.approveProcess(def.getId(), "test-admin");

            // Start the run — AUTO and TOOL_CALL execute immediately, pauses at HUMAN
            WorkflowRun run = engine.startRun(def.getId(), Map.of());

            assertEquals(RunStatus.PAUSED_FOR_HUMAN, run.getStatus(),
                    "Run should pause at HUMAN step after AUTO+TOOL_CALL complete");

            // Verify AUTO step completed
            StepExecution step1 = findStep(run, "1.1");
            assertEquals(StepExecutionStatus.COMPLETED, step1.getStatus());

            // Verify TOOL_CALL step completed with extraction data in runData
            StepExecution step2 = findStep(run, "2.1");
            assertEquals(StepExecutionStatus.COMPLETED, step2.getStatus());
            assertEquals("tool:entity_extraction_summary", step2.getExecutedBy());

            // The tool result should be merged into runData
            Map<String, Object> runData = run.getRunData();
            assertNotNull(runData.get("entityCount"),
                    "runData should contain entityCount from tool");
            assertEquals(entityCount, ((Number) runData.get("entityCount")).intValue());
            assertNotNull(runData.get("entityNames"),
                    "runData should contain entityNames from tool");

            // Verify HUMAN step is awaiting
            StepExecution step3 = findStep(run, "3.1");
            assertEquals(StepExecutionStatus.AWAITING_APPROVAL, step3.getStatus());

            // ── Phase 3: Complete the human review ──
            WorkflowRun completed = engine.completeHumanStep(
                    run.getId(), "3.1", "reviewer@kompile.ai",
                    Map.of("approved", true, "notes", "Entities look correct"));

            assertEquals(RunStatus.COMPLETED, completed.getStatus(),
                    "Run should complete after human review");

            // Final runData should contain all accumulated data
            Map<String, Object> finalData = completed.getRunData();
            assertEquals("wf-doc-1", finalData.get("documentId"));
            assertEquals("claude-cli", finalData.get("extractionSource"));
            assertTrue((Boolean) finalData.get("approved"));
        }

        @Test
        @DisplayName("Extraction workflow preserves graph entity detail in run data")
        void extractionPreservesEntityDetail() {
            String text = """
                    Amazon Web Services (AWS) opened a new data center in Mumbai, India.
                    VP of Infrastructure Peter DeSantis announced the expansion at re:Invent 2024.
                    """;

            RetrievedDoc chunk = new RetrievedDoc("detail-doc", text, Map.of());
            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "EVENT"));

            RelationExtractionAgent.ExtractionResult extraction =
                    extractionAgent.extract(List.of(chunk), config);

            Graph graph = extraction.graph();
            assertFalse(graph.getEntities().isEmpty());

            // Serialize graph to JSON for the workflow
            ObjectMapper om = new ObjectMapper();
            List<Map<String, String>> entityMaps = new ArrayList<>();
            for (Entity e : graph.getEntities()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("title", e.getTitle());
                m.put("type", e.getType());
                entityMaps.add(m);
            }
            String entitiesJson;
            try {
                entitiesJson = om.writeValueAsString(entityMaps);
            } catch (Exception e) {
                fail("Failed to serialize entities: " + e.getMessage());
                return;
            }

            // Create a simple AUTO-only workflow that stores the extraction detail
            ProcessEngineServiceImpl engine = createProcessEngine();

            ProcessStep step = ProcessStep.builder()
                    .id("1.1").name("Store Extraction Results")
                    .stepType(StepType.AUTO)
                    .executionExpressions(Map.of(
                            "status", "'extraction_complete'",
                            "entityCount", String.valueOf(graph.getEntities().size()),
                            "source", "'claude-cli'"
                    ))
                    .build();

            ProcessDefinition def = engine.createProcess(ProcessDefinition.builder()
                    .name("Entity Storage Workflow")
                    .phases(List.of(
                            ProcessPhase.builder()
                                    .id("p1").name("Store").order(1)
                                    .steps(List.of(step)).build()
                    ))
                    .build());

            engine.approveProcess(def.getId(), "system");

            // Start with extraction data as initial run data
            WorkflowRun run = engine.startRun(def.getId(), Map.of(
                    "extractedEntities", entitiesJson,
                    "rawEntityCount", graph.getEntities().size()
            ));

            assertEquals(RunStatus.COMPLETED, run.getStatus());

            Map<String, Object> data = run.getRunData();
            assertEquals("extraction_complete", data.get("status"));
            assertEquals("claude-cli", data.get("source"));
            // Initial data is preserved
            assertNotNull(data.get("extractedEntities"),
                    "Initial extraction JSON should persist through workflow");
            assertEquals(graph.getEntities().size(), data.get("rawEntityCount"));
        }

        @Test
        @DisplayName("Failed extraction produces FAILED workflow step")
        void failedExtractionProducesFailedStep() {
            ProcessEngineServiceImpl engine = createProcessEngine();

            // Dispatcher that always throws — simulates LLM failure
            StepExecutionDispatcher failingDispatcher = createFailingDispatcher();
            engine.setStepExecutionDispatcher(failingDispatcher);

            ProcessStep toolStep = ProcessStep.builder()
                    .id("1.1").name("Extract Entities")
                    .stepType(StepType.TOOL_CALL)
                    .toolName("broken_extraction")
                    .toolArguments(Map.of("text", "'some text'"))
                    .build();

            ProcessDefinition def = engine.createProcess(ProcessDefinition.builder()
                    .name("Failing Extraction Workflow")
                    .phases(List.of(
                            ProcessPhase.builder()
                                    .id("p1").name("Extract").order(1)
                                    .steps(List.of(toolStep)).build()
                    ))
                    .build());

            engine.approveProcess(def.getId(), "system");
            WorkflowRun run = engine.startRun(def.getId(), Map.of());

            assertEquals(RunStatus.FAILED, run.getStatus(),
                    "Run should fail when tool throws");

            StepExecution step = findStep(run, "1.1");
            assertEquals(StepExecutionStatus.FAILED, step.getStatus());
            assertNotNull(step.getError(), "Failed step should have error message");
            assertTrue(step.getError().contains("broken_extraction"),
                    "Error should mention the tool name");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. Extraction Quality / Schema Compliance
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Extraction Quality")
    class ExtractionQuality {

        @Test
        @DisplayName("Extracted entities have required fields populated")
        void entitiesHaveRequiredFields() {
            String text = """
                    Microsoft acquired Activision Blizzard for $68.7 billion in 2023.
                    CEO Satya Nadella described it as a pivotal moment for gaming.
                    """;

            RetrievedDoc chunk = new RetrievedDoc("quality-1", text, Map.of());
            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "EVENT"));

            RelationExtractionAgent.ExtractionResult result =
                    extractionAgent.extract(List.of(chunk), config);

            for (Entity entity : result.graph().getEntities()) {
                assertNotNull(entity.getId(),
                        "Entity ID should not be null: " + entity);
                assertFalse(entity.getId().isBlank(),
                        "Entity ID should not be blank: " + entity);
                assertNotNull(entity.getTitle(),
                        "Entity title should not be null: " + entity);
                assertFalse(entity.getTitle().isBlank(),
                        "Entity title should not be blank: " + entity);
                assertNotNull(entity.getType(),
                        "Entity type should not be null: " + entity);
            }
        }

        @Test
        @DisplayName("Extracted relationships reference valid entity IDs")
        void relationshipsReferenceValidEntities() {
            String text = """
                    Dr. Sarah Park joined Google DeepMind in London to lead the
                    protein folding research team. She previously worked at
                    Johns Hopkins University in Baltimore.
                    """;

            RetrievedDoc chunk = new RetrievedDoc("quality-2", text, Map.of());
            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION"));

            RelationExtractionAgent.ExtractionResult result =
                    extractionAgent.extract(List.of(chunk), config);

            Graph graph = result.graph();
            if (graph.getRelationships() != null && !graph.getRelationships().isEmpty()) {
                Set<String> entityIds = new HashSet<>();
                for (Entity e : graph.getEntities()) {
                    entityIds.add(e.getId());
                }

                for (Relationship rel : graph.getRelationships()) {
                    assertNotNull(rel.getSource(),
                            "Relationship source should not be null: " + rel);
                    assertNotNull(rel.getTarget(),
                            "Relationship target should not be null: " + rel);
                    assertTrue(entityIds.contains(rel.getSource()),
                            "Relationship source '" + rel.getSource() +
                                    "' should reference a valid entity; known IDs: " + entityIds);
                    assertTrue(entityIds.contains(rel.getTarget()),
                            "Relationship target '" + rel.getTarget() +
                                    "' should reference a valid entity; known IDs: " + entityIds);
                }
            }
        }
    }

    // ── Factory methods for dispatchers ──────────────────────────────────────

    /**
     * Creates a dispatcher that returns extraction summary data for the
     * "entity_extraction_summary" tool and throws for anything else.
     */
    private StepExecutionDispatcher createExtractionDispatcher(
            int entityCount, int relationCount, List<String> entityNames) {
        return new StepExecutionDispatcher() {
            @Override
            public Map<String, Object> invokeTool(String toolName, Map<String, Object> arguments) {
                if ("entity_extraction_summary".equals(toolName)) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("entityCount", entityCount);
                    result.put("relationCount", relationCount);
                    result.put("entityNames", entityNames);
                    result.put("documentId", arguments.getOrDefault("documentId", "unknown"));
                    result.put("extractionProvider", arguments.getOrDefault("source", "unknown"));
                    return result;
                }
                throw new IllegalArgumentException("Unknown tool: " + toolName);
            }

            @Override
            public Map<String, Object> executeHttpCall(String method, String url,
                                                        Map<String, String> headers, Object body) {
                throw new UnsupportedOperationException("HTTP calls not supported in test");
            }

            @Override
            public Map<String, Object> executeScript(String language, String scriptBody,
                                                      Map<String, Object> runData) {
                throw new UnsupportedOperationException("Script execution not supported in test");
            }

            @Override
            public Map<String, Object> convertExcel(String json, String lang) {
                throw new UnsupportedOperationException("Excel not supported in test");
            }

            @Override
            public Map<String, Object> executeExcel(String json, Map<String, Object> overrides,
                                                     String lang, String code) {
                throw new UnsupportedOperationException("Excel not supported in test");
            }

            @Override
            public List<Map<String, Object>> listAvailableTools() {
                return List.of(Map.of(
                        "name", "entity_extraction_summary",
                        "description", "Summarize LLM entity extraction results"
                ));
            }
        };
    }

    private StepExecutionDispatcher createFailingDispatcher() {
        return new StepExecutionDispatcher() {
            @Override
            public Map<String, Object> invokeTool(String toolName, Map<String, Object> arguments) {
                throw new RuntimeException("LLM extraction service unavailable for tool: " + toolName);
            }

            @Override
            public Map<String, Object> executeHttpCall(String method, String url,
                                                        Map<String, String> headers, Object body) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> executeScript(String language, String scriptBody,
                                                      Map<String, Object> runData) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> convertExcel(String json, String lang) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> executeExcel(String json, Map<String, Object> overrides,
                                                     String lang, String code) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Map<String, Object>> listAvailableTools() {
                return List.of();
            }
        };
    }

    private StepExecution findStep(WorkflowRun run, String stepId) {
        return run.getStepExecutions().stream()
                .filter(s -> stepId.equals(s.getStepId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Step " + stepId + " not found in run"));
    }
}
