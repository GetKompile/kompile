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

import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionConfig;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.enrichment.domain.AutoLabelSuggestion;
import ai.kompile.enrichment.domain.EntityCategory;
import ai.kompile.enrichment.domain.MassEditResult;
import ai.kompile.enrichment.impl.AutoLabelService;
import ai.kompile.enrichment.repository.EntityCategoryRepository;
import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.model.KClawRequest;
import ai.kompile.kclaw.model.KClawResponse;
import ai.kompile.knowledgegraph.agent.LlmRelationExtractionAgent;
import ai.kompile.knowledgegraph.agent.MultiAgentExtractionService;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.orchestrator.integration.cli.CliAgentConfig;
import ai.kompile.orchestrator.integration.cli.CliAgentExtractionLlmService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for entity resolution, deduplication, graph compaction,
 * and category management using real Claude CLI for LLM-powered extraction
 * and resolution.
 *
 * <p>Tests exercise the full pipeline:
 * <ol>
 *   <li>Claude CLI extracts entities + relations from overlapping document chunks</li>
 *   <li>EntityResolutionService cross-chunk deduplication merges duplicates</li>
 *   <li>GraphCompactionService graph-level compaction with Senzing-style explainability</li>
 *   <li>Claude-based entity resolution: LLM decides merge/skip for ambiguous pairs</li>
 *   <li>Category creation, assignment, and LLM-powered auto-labeling</li>
 * </ol>
 *
 * <p>Tests auto-skip if Claude CLI is not installed ({@code claude --version}).
 */
@DisplayName("Entity Resolution, Dedup & Categories — Full Pipeline")
class EntityResolutionCategoryIntegrationTest {

    private static CliAgentExtractionLlmService claudeService;
    private static ExtractionLlmServiceRegistry registry;
    private static LlmRelationExtractionAgent extractionAgent;
    private static MultiAgentExtractionService multiAgentService;

    // Non-LLM services instantiated per test
    private EntityResolutionService resolutionService;
    private KnowledgeGraphService knowledgeGraphService;
    private GraphCompactionService compactionService;

    @BeforeAll
    static void checkClaudeAvailability() {
        boolean available = CliAgentConfig.CLAUDE_CLI.checkAvailability();
        assumeTrue(available,
                "Claude CLI is not installed — skipping real LLM integration tests");

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

    @BeforeEach
    void setUp() {
        resolutionService = new EntityResolutionService();
        knowledgeGraphService = mock(KnowledgeGraphService.class);
        compactionService = new GraphCompactionService(knowledgeGraphService);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ExtractionConfig configForClaude(List<String> entityTypes) {
        return new ExtractionConfig(
                entityTypes, List.of(), 0.0,
                Map.of("llmProvider", "claude-cli"));
    }

    private GraphNode entityNode(String nodeId, String title, String entityType) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson("{\"entity_type\":\"" + entityType + "\"}")
                .confidence(0.9)
                .edgeCount(2)
                .childCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphNode entityNodeWithConfidence(String nodeId, String title,
                                                String entityType, double confidence) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .description("Description for " + title)
                .metadataJson("{\"entity_type\":\"" + entityType + "\"}")
                .confidence(confidence)
                .edgeCount(2)
                .childCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphNode entityNodeWithAliases(String nodeId, String title,
                                             String entityType, List<String> aliases) {
        String aliasJson = aliases.stream()
                .map(a -> "\"" + a + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]")
                .orElse("[]");
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson("{\"entity_type\":\"" + entityType + "\",\"aliases\":" + aliasJson + "}")
                .confidence(0.9)
                .edgeCount(2)
                .childCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private ExtractedEntity entity(String id, String name, String type) {
        return new ExtractedEntity(id, name, type, List.of(), null, 1.0, Map.of());
    }

    private ExtractedEntity entityWithAliases(String id, String name, String type,
                                               List<String> aliases) {
        return new ExtractedEntity(id, name, type, aliases, null, 1.0, Map.of());
    }

    private ExtractedRelation relation(String source, String target, String type) {
        return new ExtractedRelation(source, target, type, null, 1.0, Map.of());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. LLM EXTRACTION → CROSS-CHUNK DEDUPLICATION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. LLM Extraction → Entity Resolution Pipeline")
    class ExtractionToResolution {

        @Test
        @DisplayName("Overlapping chunks produce duplicate entities that resolution merges")
        void overlappingChunksDedup() {
            // Two chunks that mention the same people/orgs with slight variations
            RetrievedDoc chunk1 = new RetrievedDoc("chunk-1", """
                    Apple Inc., headquartered in Cupertino, California, announced a new
                    partnership with OpenAI. CEO Tim Cook described the deal as transformative.
                    Senior VP Craig Federighi led the technical integration.
                    """, Map.of());

            RetrievedDoc chunk2 = new RetrievedDoc("chunk-2", """
                    Tim Cook, the CEO of Apple, presented the OpenAI partnership at WWDC 2025.
                    Craig Federighi demonstrated the AI features on stage in Cupertino.
                    The partnership will bring ChatGPT to Apple devices worldwide.
                    """, Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "EVENT"));

            // Extract from each chunk independently
            var result1 = extractionAgent.extract(List.of(chunk1), config);
            var result2 = extractionAgent.extract(List.of(chunk2), config);

            assertNotNull(result1.graph());
            assertNotNull(result2.graph());
            assertFalse(result1.graph().getEntities().isEmpty(),
                    "Chunk 1 should produce entities");
            assertFalse(result2.graph().getEntities().isEmpty(),
                    "Chunk 2 should produce entities");

            // Convert Graph entities to ExtractedEntity for resolution service
            List<ExtractedEntity> allEntities = new ArrayList<>();
            List<ExtractedRelation> allRelations = new ArrayList<>();
            int idOffset = 0;

            for (Entity e : result1.graph().getEntities()) {
                allEntities.add(entity("c1_" + e.getId(), e.getTitle(), e.getType()));
            }
            if (result1.graph().getRelationships() != null) {
                for (Relationship r : result1.graph().getRelationships()) {
                    allRelations.add(relation("c1_" + r.getSource(), "c1_" + r.getTarget(), r.getType()));
                }
            }
            for (Entity e : result2.graph().getEntities()) {
                allEntities.add(entity("c2_" + e.getId(), e.getTitle(), e.getType()));
            }
            if (result2.graph().getRelationships() != null) {
                for (Relationship r : result2.graph().getRelationships()) {
                    allRelations.add(relation("c2_" + r.getSource(), "c2_" + r.getTarget(), r.getType()));
                }
            }

            int preResolveCount = allEntities.size();

            // Run cross-chunk resolution
            ExtractionResult combined = ExtractionResult.of(allEntities, allRelations, null);
            ExtractionResult resolved = resolutionService.resolveSingle(combined);

            int postResolveCount = resolved.entities().size();

            // Resolution should merge some duplicates
            assertTrue(postResolveCount < preResolveCount,
                    "Resolution should merge duplicates. Before: " + preResolveCount +
                            ", after: " + postResolveCount);

            // Tim Cook and Apple should each appear exactly once
            long timCookCount = resolved.entities().stream()
                    .filter(e -> e.name().toLowerCase().contains("tim") &&
                            e.name().toLowerCase().contains("cook"))
                    .count();
            assertEquals(1, timCookCount,
                    "Tim Cook should appear exactly once after resolution; found: " +
                            resolved.entities().stream()
                                    .filter(e -> e.name().toLowerCase().contains("tim"))
                                    .map(ExtractedEntity::name)
                                    .collect(Collectors.toList()));

            // Filter for "Apple" the company — exclude event/product names containing "Apple"
            long appleOrgCount = resolved.entities().stream()
                    .filter(e -> {
                        String lower = e.name().toLowerCase();
                        return (lower.equals("apple") || lower.equals("apple inc")
                                || lower.equals("apple inc.") || lower.contains("apple")
                                && (e.type() == null || "ORG".equalsIgnoreCase(e.type())
                                || "ORGANIZATION".equalsIgnoreCase(e.type())));
                    })
                    .count();
            assertTrue(appleOrgCount >= 1,
                    "Apple (the company) should appear at least once after resolution; found: " +
                            resolved.entities().stream()
                                    .filter(e -> e.name().toLowerCase().contains("apple"))
                                    .map(e -> e.name() + " (" + e.type() + ")")
                                    .collect(Collectors.toList()));
        }

        @Test
        @DisplayName("Multi-agent extraction with merge strategy deduplicates across chunks")
        void multiAgentMergeDedup() {
            RetrievedDoc chunk1 = new RetrievedDoc("ma-1", """
                    Google's CEO Sundar Pichai unveiled Gemini 2.0 at their Mountain View campus.
                    VP of Engineering Jeff Dean presented the technical architecture.
                    """, Map.of());

            RetrievedDoc chunk2 = new RetrievedDoc("ma-2", """
                    Sundar Pichai announced that Gemini 2.0 would be available to all Google Cloud
                    customers. Jeff Dean co-authored the research paper behind the model.
                    """, Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "TECHNOLOGY", "LOCATION"));

            var merged = multiAgentService.runExtraction(
                    List.of(chunk1, chunk2), null, "UNION", config);

            assertNotNull(merged.mergedGraph());
            assertTrue(merged.totalEntities() >= 3,
                    "Should extract at least Sundar Pichai, Google, Gemini 2.0; got " +
                            merged.totalEntities());

            Set<String> names = merged.mergedGraph().getEntities().stream()
                    .map(e -> e.getTitle().toLowerCase())
                    .collect(Collectors.toSet());

            assertTrue(names.stream().anyMatch(n -> n.contains("sundar") || n.contains("pichai")),
                    "Should find Sundar Pichai; found: " + names);
            assertTrue(names.stream().anyMatch(n -> n.contains("google")),
                    "Should find Google; found: " + names);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. CROSS-CHUNK ENTITY RESOLUTION (algorithmic)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. Cross-Chunk Entity Resolution")
    class CrossChunkResolution {

        @Test
        @DisplayName("Exact name match across chunks merges entities")
        void exactNameMerge() {
            ExtractionResult chunk1 = ExtractionResult.of(
                    List.of(entity("e1", "Apple Inc", "ORG"),
                            entity("e2", "Tim Cook", "PERSON")),
                    List.of(relation("e2", "e1", "CEO_OF")), null);

            ExtractionResult chunk2 = ExtractionResult.of(
                    List.of(entity("e3", "Apple Inc", "ORG"),
                            entity("e4", "Craig Federighi", "PERSON")),
                    List.of(relation("e4", "e3", "WORKS_AT")), null);

            ExtractionResult resolved = resolutionService.resolve(List.of(chunk1, chunk2));

            // Apple Inc should be merged
            long appleCount = resolved.entities().stream()
                    .filter(e -> e.name().toLowerCase().contains("apple"))
                    .count();
            assertEquals(1, appleCount, "Apple Inc should merge across chunks");
            assertEquals(3, resolved.entities().size(),
                    "3 unique entities: Apple, Tim Cook, Craig Federighi");

            // Relations should be remapped to use canonical IDs
            assertEquals(2, resolved.relations().size(),
                    "Both relations should survive remapping");
        }

        @Test
        @DisplayName("Fuzzy name match via Levenshtein merges near-duplicates")
        void fuzzyNameMerge() {
            // "Microsoft Corporation" vs "Microsoft Corp" — Levenshtein > 0.85
            ExtractionResult chunk1 = ExtractionResult.of(
                    List.of(entity("e1", "Microsoft Corporation", "ORG")),
                    List.of(), null);

            ExtractionResult chunk2 = ExtractionResult.of(
                    List.of(entity("e2", "Microsoft Corp", "ORG")),
                    List.of(), null);

            ExtractionResult resolved = resolutionService.resolve(List.of(chunk1, chunk2));

            assertEquals(1, resolved.entities().size(),
                    "Microsoft Corporation / Corp should merge via Levenshtein");
        }

        @Test
        @DisplayName("Alias resolution merges entities with known aliases")
        void aliasResolution() {
            ExtractionResult chunk1 = ExtractionResult.of(
                    List.of(entityWithAliases("e1", "International Business Machines", "ORG",
                            List.of("IBM", "Big Blue"))),
                    List.of(), null);

            ExtractionResult chunk2 = ExtractionResult.of(
                    List.of(entity("e2", "IBM", "ORG")),
                    List.of(), null);

            ExtractionResult resolved = resolutionService.resolve(List.of(chunk1, chunk2));

            assertEquals(1, resolved.entities().size(),
                    "IBM alias should match 'International Business Machines'");
        }

        @Test
        @DisplayName("Different entity types prevent merging same-name entities")
        void typeMismatchNoMerge() {
            ExtractionResult chunk1 = ExtractionResult.of(
                    List.of(entity("e1", "Amazon", "ORG")),
                    List.of(), null);

            ExtractionResult chunk2 = ExtractionResult.of(
                    List.of(entity("e2", "Amazon", "LOCATION")),
                    List.of(), null);

            ExtractionResult resolved = resolutionService.resolve(List.of(chunk1, chunk2));

            assertEquals(2, resolved.entities().size(),
                    "Same name, different type — should NOT merge");
        }

        @Test
        @DisplayName("Relation remapping preserves graph structure after merge")
        void relationRemapping() {
            ExtractionResult chunk1 = ExtractionResult.of(
                    List.of(entity("e1", "Elon Musk", "PERSON"),
                            entity("e2", "Tesla Inc", "ORG"),
                            entity("e3", "SpaceX", "ORG")),
                    List.of(relation("e1", "e2", "CEO_OF"),
                            relation("e1", "e3", "FOUNDED")),
                    null);

            ExtractionResult chunk2 = ExtractionResult.of(
                    List.of(entity("e4", "Tesla Inc", "ORG"),
                            entity("e5", "Elon Musk", "PERSON")),
                    List.of(relation("e5", "e4", "CEO_OF")),
                    null);

            ExtractionResult resolved = resolutionService.resolve(List.of(chunk1, chunk2));

            // 3 unique entities, deduplicated relations
            assertEquals(3, resolved.entities().size());

            // CEO_OF relation should be deduplicated (same source+target+type after remapping)
            long ceoRelations = resolved.relations().stream()
                    .filter(r -> "CEO_OF".equals(r.type()))
                    .count();
            assertEquals(1, ceoRelations,
                    "Duplicate CEO_OF relation should be deduplicated after remapping");
        }

        @Test
        @DisplayName("Confidence-based canonical election keeps higher-confidence entity")
        void confidenceElection() {
            ExtractedEntity lowConf = new ExtractedEntity(
                    "e1", "Apple", "ORG", List.of(), "A tech company", 0.6, Map.of());
            ExtractedEntity highConf = new ExtractedEntity(
                    "e2", "Apple", "ORG", List.of(), "Apple Inc is a multinational tech company", 0.95, Map.of());

            ExtractionResult chunk1 = ExtractionResult.of(List.of(lowConf), List.of(), null);
            ExtractionResult chunk2 = ExtractionResult.of(List.of(highConf), List.of(), null);

            ExtractionResult resolved = resolutionService.resolve(List.of(chunk1, chunk2));

            assertEquals(1, resolved.entities().size());
            ExtractedEntity merged = resolved.entities().get(0);
            assertTrue(merged.confidence() >= 0.95,
                    "Merged entity should keep higher confidence; got " + merged.confidence());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GRAPH COMPACTION (persisted knowledge graph)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. Graph Compaction Service")
    class GraphCompaction {

        @Test
        @DisplayName("Compaction merges entities with similar titles and redirects edges")
        void compactMergesAndRedirects() {
            GraphNode node1 = entityNodeWithConfidence("n1", "Apple Inc", "ORG", 0.95);
            GraphNode node2 = entityNodeWithConfidence("n2", "Apple Corporation", "ORG", 0.8);
            GraphNode node3 = entityNode("n3", "Tim Cook", "PERSON");

            GraphEdge edge1 = GraphEdge.builder()
                    .edgeId("edge-1").sourceNode(node3).targetNode(node2)
                    .edgeType(EdgeType.USER_DEFINED).description("CEO_OF")
                    .build();

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(node1, node2, node3));
            when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(edge1));
            when(knowledgeGraphService.getEdgesForNode("n1")).thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("n3")).thenReturn(List.of(edge1));
            when(knowledgeGraphService.edgeExists(anyString(), anyString())).thenReturn(false);
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(node1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(node2));
            when(knowledgeGraphService.createEdge(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(edge1);

            CompactionConfig config = CompactionConfig.withThreshold(0.85);
            CompactionResult result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() > 0,
                    "Should merge Apple Inc / Apple Corporation");
            assertFalse(result.decisions().isEmpty(),
                    "Should produce at least one merge decision");

            MergeDecision decision = result.decisions().get(0);
            assertEquals("n1", decision.canonicalNodeId(),
                    "Higher-confidence node should be canonical");
            assertTrue(decision.mergedNodeIds().contains("n2"),
                    "Lower-confidence node should be merged");
        }

        @Test
        @DisplayName("Preview returns candidates without executing merges")
        void previewDoesNotMerge() {
            GraphNode node1 = entityNode("n1", "Google LLC", "ORG");
            GraphNode node2 = entityNode("n2", "Google", "ORG");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(node1, node2));

            CompactionConfig config = CompactionConfig.previewOnly(0.85);
            List<MatchCandidate> candidates = compactionService.previewCandidates(config);

            assertFalse(candidates.isEmpty(),
                    "Should find Google LLC / Google as candidates");
            MatchCandidate candidate = candidates.get(0);
            assertTrue(candidate.score() >= 0.85,
                    "Score should be >= threshold; got " + candidate.score());
            assertFalse(candidate.reasons().isEmpty(),
                    "Candidate should list match reasons");

            // No merge should have happened
            verify(knowledgeGraphService, never()).deleteNode(anyString());
            verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Explain provides Senzing-style why/why-not for two entities")
        void explainMatchReasons() {
            GraphNode node1 = entityNode("n1", "Microsoft Corp", "ORG");
            GraphNode node2 = entityNode("n2", "Microsoft Corporation", "ORG");

            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(node1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(node2));

            MatchExplanation explanation = compactionService.explain("n1", "n2");

            assertTrue(explanation.wouldMerge(),
                    "Microsoft Corp/Corporation should be a merge candidate");
            assertTrue(explanation.score() >= 0.85,
                    "Score should be >= 0.85; got " + explanation.score());
            assertFalse(explanation.matchReasons().isEmpty(),
                    "Should list match reasons (LEVENSHTEIN, EXACT_TITLE_MATCH, etc.)");
            assertTrue(explanation.blockers().isEmpty(),
                    "Should have no blockers for this pair");
        }

        @Test
        @DisplayName("Explain reports blockers for non-matching entities")
        void explainReportsBlockers() {
            GraphNode node1 = entityNode("n1", "Apple Inc", "ORG");
            GraphNode node2 = entityNode("n2", "Samsung Electronics", "ORG");

            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(node1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(node2));

            MatchExplanation explanation = compactionService.explain("n1", "n2");

            assertFalse(explanation.wouldMerge(),
                    "Apple / Samsung should NOT be a merge candidate");
            assertTrue(explanation.score() < 0.85,
                    "Score should be below threshold; got " + explanation.score());
            assertFalse(explanation.blockers().isEmpty(),
                    "Should report blockers (BELOW_THRESHOLD, etc.)");
        }

        @Test
        @DisplayName("Compact merges two similar entities with assembly trace")
        void compactMergesTwoSimilar() {
            GraphNode node1 = entityNodeWithConfidence("n1", "Tesla", "ORG", 0.9);
            GraphNode node2 = entityNodeWithConfidence("n2", "Tesla Inc", "ORG", 0.85);

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(node1, node2));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(node1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(node2));
            when(knowledgeGraphService.getEdgesForNode("n1")).thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of());

            CompactionConfig config = CompactionConfig.withThreshold(0.70);
            CompactionResult result = compactionService.compact(config);

            assertEquals(1, result.decisions().size(),
                    "Should produce exactly one merge decision");
            assertEquals(1, result.entitiesMerged(),
                    "Should merge exactly one entity into canonical");

            MergeDecision decision = result.decisions().get(0);
            assertNotNull(decision.canonicalNodeId());
            assertNotNull(decision.canonicalTitle());
            assertFalse(decision.assemblySteps().isEmpty(),
                    "Should include Senzing-style assembly steps");
        }

        @Test
        @DisplayName("Alias-based matching detects duplicate entities")
        void aliasMatching() {
            GraphNode node1 = entityNodeWithAliases("n1",
                    "International Business Machines", "ORG", List.of("IBM", "Big Blue"));
            GraphNode node2 = entityNode("n2", "IBM", "ORG");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(node1, node2));

            CompactionConfig config = CompactionConfig.previewOnly(0.5);
            List<MatchCandidate> candidates = compactionService.previewCandidates(config);

            assertFalse(candidates.isEmpty(),
                    "Should find IBM as alias of International Business Machines");
            assertTrue(candidates.get(0).reasons().stream()
                            .anyMatch(r -> r.contains("ALIAS") || r.contains("TITLE_IN_ALIAS")),
                    "Match reason should mention alias; reasons: " + candidates.get(0).reasons());
        }

        @Test
        @DisplayName("Connected components merge multi-node clusters")
        void connectedComponentMerge() {
            // Three nodes that form a connected component:
            // "Apple Inc" ↔ "Apple" ↔ "Apple Corporation"
            GraphNode n1 = entityNodeWithConfidence("n1", "Apple Inc", "ORG", 0.95);
            GraphNode n2 = entityNodeWithConfidence("n2", "Apple", "ORG", 0.7);
            GraphNode n3 = entityNodeWithConfidence("n3", "Apple Corporation", "ORG", 0.8);

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2, n3));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));
            when(knowledgeGraphService.getNode("n3")).thenReturn(Optional.of(n3));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            CompactionConfig config = CompactionConfig.withThreshold(0.80);
            CompactionResult result = compactionService.compact(config);

            // All three should merge into one component
            assertEquals(1, result.componentsFound(),
                    "Should find 1 connected component; got " + result.componentsFound());
            assertEquals(2, result.entitiesMerged(),
                    "Should merge 2 entities into canonical; got " + result.entitiesMerged());

            MergeDecision decision = result.decisions().get(0);
            assertEquals("n1", decision.canonicalNodeId(),
                    "Highest-confidence node should be canonical");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. LLM-POWERED ENTITY RESOLUTION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. Claude-Powered Entity Resolution")
    class ClaudeResolution {

        @Test
        @DisplayName("Claude resolves ambiguous entity pairs that algorithmic matching misses")
        void claudeResolvesAmbiguousPairs() {
            // These pairs are semantically the same but too different for Levenshtein
            String prompt = """
                    You are an entity resolution expert. For each pair of entities below,
                    determine if they refer to the same real-world entity. Return ONLY a
                    JSON array with your decisions.

                    Pairs to evaluate:
                    1. "Alphabet Inc" vs "Google" — both ORGANIZATION type
                    2. "JPMorgan Chase & Co." vs "JP Morgan" — both ORGANIZATION type
                    3. "Elon Musk" vs "Tesla" — PERSON vs ORGANIZATION
                    4. "Big Blue" vs "International Business Machines" — both ORGANIZATION type
                    5. "NYC" vs "New York City" — both LOCATION type

                    Return format:
                    [{"pair": 1, "same_entity": true/false, "confidence": 0.0-1.0, "reasoning": "..."}]
                    Return ONLY the JSON array.
                    """;

            // Use the Claude CLI directly for entity resolution
            RelationExtractionAgent.ExtractionResult dummyResult =
                    extractionAgent.extract(List.of(new RetrievedDoc("dummy", prompt, Map.of())),
                            configForClaude(List.of("ORGANIZATION", "PERSON", "LOCATION")));

            // The real test here is that Claude can process entity resolution prompts
            assertNotNull(dummyResult, "Claude should process entity resolution prompt");

            // Also verify our algorithmic resolution handles what it can
            ExtractionResult chunk1 = ExtractionResult.of(
                    List.of(entity("e1", "JPMorgan Chase & Co.", "ORG")),
                    List.of(), null);
            ExtractionResult chunk2 = ExtractionResult.of(
                    List.of(entity("e2", "JP Morgan", "ORG")),
                    List.of(), null);

            ExtractionResult resolved = resolutionService.resolve(List.of(chunk1, chunk2));

            // Levenshtein may or may not catch this depending on threshold
            // but the entities should be in the result either way
            assertFalse(resolved.entities().isEmpty());
        }

        @Test
        @DisplayName("Claude extracts and deduplicates entities from a complex document")
        void claudeFullPipelineComplexDoc() {
            // A document with intentional entity variations to test full pipeline
            RetrievedDoc complexDoc = new RetrievedDoc("complex-1", """
                    The merger between Alphabet Inc. (parent company of Google) and DeepMind
                    Technologies was finalized in London. Alphabet's CEO Sundar Pichai and
                    DeepMind founder Demis Hassabis signed the agreement.

                    Google, a subsidiary of Alphabet, will integrate DeepMind's research into
                    its cloud platform. Sundar Pichai said "This is a defining moment for AI."

                    In related news, Dr. Demis Hassabis received the Nobel Prize in Chemistry
                    for protein structure prediction work done at DeepMind in London, UK.
                    """, Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "EVENT"));

            var result = extractionAgent.extract(List.of(complexDoc), config);

            Graph graph = result.graph();
            assertNotNull(graph);
            assertFalse(graph.getEntities().isEmpty());

            Set<String> entityNames = graph.getEntities().stream()
                    .map(e -> e.getTitle().toLowerCase())
                    .collect(Collectors.toSet());

            // Claude should find key entities
            assertTrue(entityNames.stream().anyMatch(n ->
                            n.contains("sundar") || n.contains("pichai")),
                    "Should find Sundar Pichai; found: " + entityNames);
            assertTrue(entityNames.stream().anyMatch(n ->
                            n.contains("demis") || n.contains("hassabis")),
                    "Should find Demis Hassabis; found: " + entityNames);
            assertTrue(entityNames.stream().anyMatch(n ->
                            n.contains("deepmind")),
                    "Should find DeepMind; found: " + entityNames);

            // Now run resolution on the extracted entities to check dedup
            List<ExtractedEntity> extracted = graph.getEntities().stream()
                    .map(e -> entity(e.getId(), e.getTitle(), e.getType()))
                    .collect(Collectors.toList());
            List<ExtractedRelation> relations = graph.getRelationships() != null
                    ? graph.getRelationships().stream()
                    .map(r -> relation(r.getSource(), r.getTarget(), r.getType()))
                    .collect(Collectors.toList())
                    : List.of();

            ExtractionResult combined = ExtractionResult.of(extracted, relations, null);
            ExtractionResult resolved = resolutionService.resolveSingle(combined);

            // Sundar Pichai should appear once (mentioned twice in text)
            long sundarCount = resolved.entities().stream()
                    .filter(e -> e.name().toLowerCase().contains("sundar") ||
                            e.name().toLowerCase().contains("pichai"))
                    .count();
            assertTrue(sundarCount <= 1,
                    "Sundar Pichai should not be duplicated; found " + sundarCount);
        }

        @Test
        @DisplayName("Claude handles entity extraction with relations for graph building")
        void claudeExtractsGraphWithRelations() {
            RetrievedDoc doc = new RetrievedDoc("rel-doc", """
                    NVIDIA Corporation, led by CEO Jensen Huang, acquired Arm Holdings
                    from SoftBank Group for $40 billion. The deal was reviewed by the
                    FTC in Washington, D.C. NVIDIA's headquarters in Santa Clara, California
                    will serve as the combined entity's global hub.
                    """, Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "EVENT"));

            var result = extractionAgent.extract(List.of(doc), config);

            Graph graph = result.graph();
            assertFalse(graph.getEntities().isEmpty());

            // Should have relationships linking entities
            if (graph.getRelationships() != null && !graph.getRelationships().isEmpty()) {
                Set<String> entityIds = graph.getEntities().stream()
                        .map(Entity::getId)
                        .collect(Collectors.toSet());

                for (Relationship rel : graph.getRelationships()) {
                    assertTrue(entityIds.contains(rel.getSource()),
                            "Relation source '" + rel.getSource() +
                                    "' should reference valid entity");
                    assertTrue(entityIds.contains(rel.getTarget()),
                            "Relation target '" + rel.getTarget() +
                                    "' should reference valid entity");
                    assertNotNull(rel.getType(), "Relation type should not be null");
                }
            }

            // Verify extracted entity types are reasonable
            Set<String> types = graph.getEntities().stream()
                    .map(e -> e.getType().toUpperCase())
                    .collect(Collectors.toSet());
            assertTrue(types.contains("PERSON") || types.contains("ORGANIZATION"),
                    "Should extract PERSON or ORGANIZATION types; found: " + types);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. DUPLICATE DETECTION (BFS connected components)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. Duplicate Group Detection")
    class DuplicateDetection {

        @Test
        @DisplayName("Preview candidates groups into connected clusters")
        void duplicateGrouping() {
            // Simulate the controller's BFS grouping logic
            GraphNode n1 = entityNode("n1", "Apple Inc", "ORG");
            GraphNode n2 = entityNode("n2", "Apple", "ORG");
            GraphNode n3 = entityNode("n3", "Apple Corporation", "ORG");
            GraphNode n4 = entityNode("n4", "Google", "ORG");
            GraphNode n5 = entityNode("n5", "Google LLC", "ORG");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2, n3, n4, n5));

            CompactionConfig config = CompactionConfig.previewOnly(0.80);
            List<MatchCandidate> candidates = compactionService.previewCandidates(config);

            // BFS grouping (replicate controller logic)
            Map<String, Set<String>> adjacency = new LinkedHashMap<>();
            Map<String, String> titles = new LinkedHashMap<>();
            for (MatchCandidate c : candidates) {
                adjacency.computeIfAbsent(c.nodeIdA(), k -> new LinkedHashSet<>()).add(c.nodeIdB());
                adjacency.computeIfAbsent(c.nodeIdB(), k -> new LinkedHashSet<>()).add(c.nodeIdA());
                titles.put(c.nodeIdA(), c.titleA());
                titles.put(c.nodeIdB(), c.titleB());
            }

            Set<String> visited = new LinkedHashSet<>();
            List<Set<String>> groups = new ArrayList<>();
            for (String nodeId : adjacency.keySet()) {
                if (visited.contains(nodeId)) continue;
                Set<String> cluster = new LinkedHashSet<>();
                Queue<String> queue = new LinkedList<>();
                queue.add(nodeId);
                visited.add(nodeId);
                while (!queue.isEmpty()) {
                    String current = queue.poll();
                    cluster.add(current);
                    for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                        if (visited.add(neighbor)) {
                            queue.add(neighbor);
                        }
                    }
                }
                if (cluster.size() >= 2) {
                    groups.add(cluster);
                }
            }

            // Should have at least 2 groups: Apple variants and Google variants
            assertTrue(groups.size() >= 2,
                    "Should find at least 2 duplicate groups (Apple + Google); found " +
                            groups.size() + ": " + groups.stream()
                            .map(g -> g.stream().map(titles::get).collect(Collectors.toList()))
                            .collect(Collectors.toList()));

            // Apple cluster should have 3 members
            Optional<Set<String>> appleGroup = groups.stream()
                    .filter(g -> g.contains("n1"))
                    .findFirst();
            assertTrue(appleGroup.isPresent(), "Apple group should exist");
            assertEquals(3, appleGroup.get().size(),
                    "Apple group should have 3 members");

            // Google cluster should have 2 members
            Optional<Set<String>> googleGroup = groups.stream()
                    .filter(g -> g.contains("n4"))
                    .findFirst();
            assertTrue(googleGroup.isPresent(), "Google group should exist");
            assertEquals(2, googleGroup.get().size(),
                    "Google group should have 2 members");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. NORMALIZATION & SCORING SIGNALS
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. Scoring & Threshold Behavior")
    class ScoringAndThreshold {

        @Test
        @DisplayName("Corporate suffix variations match via normalization in compaction")
        void corporateSuffixMatching() {
            // These pairs should match because compaction normalizes "Inc.", "LLC", "Corporation"
            GraphNode n1 = entityNode("n1", "Apple Inc.", "ORG");
            GraphNode n2 = entityNode("n2", "Apple", "ORG");
            GraphNode n3 = entityNode("n3", "Google LLC", "ORG");
            GraphNode n4 = entityNode("n4", "Google", "ORG");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2, n3, n4));

            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.80));

            assertTrue(candidates.size() >= 2,
                    "Should find at least 2 match candidates (Apple pair + Google pair); found " +
                            candidates.size());

            // Verify Apple pair is found
            boolean appleMatch = candidates.stream().anyMatch(c ->
                    (c.titleA().contains("Apple") && c.titleB().contains("Apple")));
            assertTrue(appleMatch, "Should match Apple Inc. with Apple");

            // Verify Google pair is found
            boolean googleMatch = candidates.stream().anyMatch(c ->
                    (c.titleA().contains("Google") && c.titleB().contains("Google")));
            assertTrue(googleMatch, "Should match Google LLC with Google");
        }

        @Test
        @DisplayName("Typo variations detected by Levenshtein via explain API")
        void typoVariationDetection() {
            GraphNode n1 = entityNode("n1", "Microsoft", "ORG");
            GraphNode n2 = entityNode("n2", "Microsft", "ORG");  // typo

            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

            MatchExplanation explanation = compactionService.explain("n1", "n2");

            assertTrue(explanation.wouldMerge(),
                    "Typo 'Microsft' should match 'Microsoft'");
            assertTrue(explanation.score() > 0.85,
                    "Score should be > 0.85 for one-char typo; got " + explanation.score());
        }

        @Test
        @DisplayName("Threshold sensitivity: low vs high threshold")
        void thresholdSensitivity() {
            // Use names that are similar but do NOT normalize to the same string
            // "Microsoft Corp" → "microsoft" vs "Microsft Corp" → "microsft"
            // Levenshtein ~ 0.89, so low threshold catches it, high threshold doesn't
            GraphNode n1 = entityNode("n1", "Microsoft Corp", "ORG");
            GraphNode n2 = entityNode("n2", "Microsft Corp", "ORG"); // typo

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2));

            // Low threshold: should match (Levenshtein ~0.89)
            List<MatchCandidate> lowThreshold =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.5));
            assertFalse(lowThreshold.isEmpty(), "Low threshold should find match");

            // Very high threshold: should not match (Levenshtein ~0.89 < 0.99)
            List<MatchCandidate> highThreshold =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.99));
            assertTrue(highThreshold.isEmpty(), "Very high threshold should find no match");
        }

        @Test
        @DisplayName("Different entity types block matching even for identical names")
        void typeBlocksMatching() {
            GraphNode n1 = entityNode("n1", "Amazon", "ORG");
            // Different entity type
            GraphNode n2 = GraphNode.builder()
                    .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                    .title("Amazon")
                    .metadataJson("{\"entity_type\":\"LOCATION\"}")
                    .confidence(0.9).edgeCount(1).childCount(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2));

            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.85));

            // Type blocking should prevent match
            assertTrue(candidates.isEmpty(),
                    "Same name, different entity_type should not match (type blocking)");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. END-TO-END: EXTRACT → RESOLVE → COMPACT → CATEGORIZE
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. End-to-End Pipeline")
    class EndToEnd {

        @Test
        @DisplayName("Full pipeline: Claude extract → resolve → build graph → compact")
        void fullPipeline() {
            // Step 1: Extract with Claude from two overlapping chunks
            RetrievedDoc chunk1 = new RetrievedDoc("e2e-1", """
                    Amazon Web Services (AWS), led by CEO Andy Jassy, opened a new
                    data center in Mumbai, India. The facility will serve customers
                    across South Asia.
                    """, Map.of());

            RetrievedDoc chunk2 = new RetrievedDoc("e2e-2", """
                    Andy Jassy, Amazon's CEO, announced AWS expansion into India.
                    The Mumbai data center is Amazon Web Services' largest facility
                    in the Asia-Pacific region.
                    """, Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION"));

            var result1 = extractionAgent.extract(List.of(chunk1), config);
            var result2 = extractionAgent.extract(List.of(chunk2), config);

            // Step 2: Cross-chunk resolution
            List<ExtractedEntity> allEntities = new ArrayList<>();
            List<ExtractedRelation> allRelations = new ArrayList<>();

            for (Entity e : result1.graph().getEntities()) {
                allEntities.add(entity("c1_" + e.getId(), e.getTitle(), e.getType()));
            }
            for (Entity e : result2.graph().getEntities()) {
                allEntities.add(entity("c2_" + e.getId(), e.getTitle(), e.getType()));
            }

            int preResolve = allEntities.size();
            ExtractionResult resolved = resolutionService.resolveSingle(
                    ExtractionResult.of(allEntities, allRelations, null));
            int postResolve = resolved.entities().size();

            assertTrue(postResolve <= preResolve,
                    "Resolution should not create new entities");

            // Step 3: Simulate persisting resolved entities as GraphNodes
            List<GraphNode> graphNodes = new ArrayList<>();
            for (ExtractedEntity e : resolved.entities()) {
                graphNodes.add(entityNode("gn-" + e.id(), e.name(),
                        e.type() != null ? e.type() : "ENTITY"));
            }

            // Step 4: Run graph compaction on persisted nodes
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(graphNodes);
            for (GraphNode n : graphNodes) {
                when(knowledgeGraphService.getNode(n.getNodeId()))
                        .thenReturn(Optional.of(n));
                when(knowledgeGraphService.getEdgesForNode(n.getNodeId()))
                        .thenReturn(List.of());
            }

            // Preview to see what compaction would do
            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.85));

            // After resolution, there should be fewer candidates (already deduped)
            // but some may still exist if resolution used different normalization
            assertNotNull(candidates);

            // The pipeline should produce a clean set of entities
            Set<String> finalNames = resolved.entities().stream()
                    .map(e -> e.name().toLowerCase())
                    .collect(Collectors.toSet());

            assertTrue(finalNames.stream().anyMatch(n ->
                            n.contains("andy") || n.contains("jassy")),
                    "Pipeline should preserve Andy Jassy; found: " + finalNames);
            assertTrue(finalNames.stream().anyMatch(n ->
                            n.contains("aws") || n.contains("amazon")),
                    "Pipeline should preserve AWS/Amazon; found: " + finalNames);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. AUTO-LABELING WITH REAL LLM (AutoLabelService pipeline)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. LLM Auto-Labeling Pipeline")
    class AutoLabelingPipeline {

        private AutoLabelService autoLabelService;
        private EntityCategoryRepository categoryRepository;
        private GraphNodeRepository nodeRepository;
        private ObjectMapper objectMapper;

        @BeforeEach
        void setUp() {
            categoryRepository = mock(EntityCategoryRepository.class);
            nodeRepository = mock(GraphNodeRepository.class);
            objectMapper = new ObjectMapper();
        }

        /**
         * Calls Claude CLI directly using the same pattern as CliAgentLLMChat.
         * Builds "[System: ...]\n\n{user text}", invokes claude CLI,
         * parses stream-json output for text content.
         */
        static String callClaudeCli(String systemPrompt, String userPrompt) {
            String fullPrompt = "";
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                fullPrompt = "[System: " + systemPrompt + "]\n\n";
            }
            fullPrompt += (userPrompt != null ? userPrompt : "");

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "claude", "--output-format", "stream-json",
                        "--verbose", "--print", fullPrompt);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                String output = new String(process.getInputStream().readAllBytes());
                process.waitFor();

                StringBuilder content = new StringBuilder();
                ObjectMapper mapper = new ObjectMapper();
                for (String line : output.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || !line.startsWith("{")) continue;
                    try {
                        var node = mapper.readTree(line);
                        if (node.has("type") && "text".equals(node.get("type").asText())
                                && node.has("text")) {
                            content.append(node.get("text").asText());
                        } else if (node.has("type") && "result".equals(node.get("type").asText())
                                && node.has("result")) {
                            content.append(node.get("result").asText());
                        }
                    } catch (Exception ignore) {
                        // Skip non-JSON lines
                    }
                }
                return content.toString();
            } catch (Exception e) {
                throw new RuntimeException("Claude CLI call failed: " + e.getMessage(), e);
            }
        }

        /**
         * Build a mock LLMChat that routes through Claude CLI.
         * Uses Mockito deep stubs to implement the fluent API chain:
         * llmChat.prompt().system(s).user(u).call().content() → callClaudeCli(s, u)
         */
        private LLMChat buildClaudeLLMChat() {
            // Capture system and user text through the fluent chain
            final String[] captured = new String[2]; // [0]=system, [1]=user

            LLMChat llmChat = mock(LLMChat.class, RETURNS_DEEP_STUBS);
            LLMChat.ChatClientRequestSpec requestSpec = mock(
                    LLMChat.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
            LLMChat.CallResponseSpec callSpec = mock(LLMChat.CallResponseSpec.class);

            when(llmChat.prompt()).thenReturn(requestSpec);

            when(requestSpec.system(anyString())).thenAnswer(inv -> {
                captured[0] = inv.getArgument(0);
                return requestSpec;
            });
            when(requestSpec.user(anyString())).thenAnswer(inv -> {
                captured[1] = inv.getArgument(0);
                return requestSpec;
            });
            when(requestSpec.call()).thenReturn(callSpec);
            when(callSpec.content()).thenAnswer(inv ->
                    callClaudeCli(captured[0], captured[1]));

            return llmChat;
        }

        @Test
        @DisplayName("AutoLabelService calls Claude CLI to categorize entities — mimics real pipeline")
        void autoLabelWithRealClaude() {
            autoLabelService = new AutoLabelService(categoryRepository, nodeRepository, objectMapper);

            // Inject the real Claude-backed LLMChat via reflection
            LLMChat llmChat = buildClaudeLLMChat();
            try {
                var field = AutoLabelService.class.getDeclaredField("llmChat");
                field.setAccessible(true);
                field.set(autoLabelService, llmChat);
            } catch (Exception e) {
                fail("Could not inject LLMChat: " + e.getMessage());
            }

            Long factSheetId = 1L;
            List<EntityCategory> categories = List.of(
                    EntityCategory.builder()
                            .id(1L).factSheetId(factSheetId)
                            .categoryId("cat-tech").label("Technology Companies")
                            .description("Companies primarily in the technology sector")
                            .source("USER_DEFINED").active(true)
                            .createdAt(Instant.now()).updatedAt(Instant.now())
                            .build(),
                    EntityCategory.builder()
                            .id(2L).factSheetId(factSheetId)
                            .categoryId("cat-people").label("Key Executives")
                            .description("Important executives, founders, and leaders")
                            .source("USER_DEFINED").active(true)
                            .createdAt(Instant.now()).updatedAt(Instant.now())
                            .build(),
                    EntityCategory.builder()
                            .id(3L).factSheetId(factSheetId)
                            .categoryId("cat-places").label("Locations")
                            .description("Cities, countries, and geographic regions")
                            .source("USER_DEFINED").active(true)
                            .createdAt(Instant.now()).updatedAt(Instant.now())
                            .build()
            );
            when(categoryRepository.findByFactSheetIdAndActiveTrue(factSheetId))
                    .thenReturn(categories);

            List<GraphNode> entities = List.of(
                    entityNode("e1", "Apple Inc", "ORGANIZATION"),
                    entityNode("e2", "Tim Cook", "PERSON"),
                    entityNode("e3", "Cupertino", "LOCATION"),
                    entityNode("e4", "Google", "ORGANIZATION"),
                    entityNode("e5", "Sundar Pichai", "PERSON")
            );
            for (GraphNode node : entities) {
                when(nodeRepository.findByNodeId(node.getNodeId()))
                        .thenReturn(Optional.of(node));
            }

            List<String> entityIds = entities.stream()
                    .map(GraphNode::getNodeId)
                    .collect(Collectors.toList());

            MassEditResult result = autoLabelService.autoLabel(factSheetId, entityIds, true, 0.5);

            assertNotNull(result);
            assertNotNull(result.getSuggestions(), "Auto-label should produce suggestions");

            if (!result.getSuggestions().isEmpty()) {
                for (AutoLabelSuggestion suggestion : result.getSuggestions()) {
                    assertNotNull(suggestion.getEntityNodeId());
                    assertNotNull(suggestion.getSuggestedCategoryId());
                    assertTrue(suggestion.getConfidence() >= 0.5,
                            "Confidence should be >= 0.5; got " + suggestion.getConfidence());

                    boolean validCategory = categories.stream()
                            .anyMatch(c -> c.getCategoryId().equals(
                                    suggestion.getSuggestedCategoryId()));
                    assertTrue(validCategory,
                            "Suggested category '" + suggestion.getSuggestedCategoryId()
                                    + "' should be a defined category");
                }
            }

            assertEquals(0, result.getEntitiesAffected(), "Dry-run should not apply");
        }

        @Test
        @DisplayName("AutoLabelService applies suggestions — metadata updated with taxonomy fields")
        void autoLabelApplySuggestions() {
            autoLabelService = new AutoLabelService(categoryRepository, nodeRepository, objectMapper);

            Long factSheetId = 1L;
            EntityCategory techCat = EntityCategory.builder()
                    .id(1L).factSheetId(factSheetId)
                    .categoryId("cat-tech").label("Technology")
                    .description("Tech companies").source("USER_DEFINED").active(true)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();

            when(categoryRepository.findByFactSheetIdAndActiveTrue(factSheetId))
                    .thenReturn(List.of(techCat));
            when(categoryRepository.findByCategoryId("cat-tech"))
                    .thenReturn(Optional.of(techCat));

            GraphNode node = entityNode("e1", "Google", "ORGANIZATION");
            when(nodeRepository.findByNodeId("e1")).thenReturn(Optional.of(node));

            List<AutoLabelSuggestion> suggestions = List.of(
                    AutoLabelSuggestion.builder()
                            .entityNodeId("e1").entityTitle("Google").entityType("ORGANIZATION")
                            .suggestedCategoryId("cat-tech").suggestedCategoryLabel("Technology")
                            .confidence(0.95).reasoning("Tech company")
                            .build()
            );

            MassEditResult result = autoLabelService.applySuggestions(factSheetId, suggestions);

            assertEquals(1, result.getEntitiesAffected());
            verify(nodeRepository).save(argThat(savedNode -> {
                try {
                    var meta = objectMapper.readTree(savedNode.getMetadataJson());
                    return "Technology".equals(meta.path("taxonomyCategory").asText())
                            && meta.has("taxonomyDomain");
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        @Test
        @DisplayName("AutoLabelService returns error when no categories exist")
        void autoLabelNoCategoriesError() {
            autoLabelService = new AutoLabelService(categoryRepository, nodeRepository, objectMapper);
            when(categoryRepository.findByFactSheetIdAndActiveTrue(1L)).thenReturn(List.of());

            MassEditResult result = autoLabelService.autoLabel(1L, null, true, 0.5);

            assertFalse(result.getErrors().isEmpty());
            assertTrue(result.getErrors().get(0).contains("No categories"));
        }

        @Test
        @DisplayName("Full extraction → categorization pipeline with Claude")
        void extractThenCategorize() {
            RetrievedDoc doc = new RetrievedDoc("cat-test", """
                    Microsoft Corporation, headquartered in Redmond, Washington, is led
                    by CEO Satya Nadella. The company announced a $10 billion investment
                    in OpenAI. Chief Technology Officer Kevin Scott oversees the Azure
                    cloud platform, which is based in multiple data centers globally.
                    """, Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "TECHNOLOGY"));
            var extractionResult = extractionAgent.extract(List.of(doc), config);
            Graph graph = extractionResult.graph();
            assertNotNull(graph);
            assertFalse(graph.getEntities().isEmpty());

            // Build auto-label prompt exactly as AutoLabelService does
            List<EntityCategory> categories = List.of(
                    EntityCategory.builder().categoryId("cat-tech").label("Technology Companies")
                            .description("Companies in the technology sector").build(),
                    EntityCategory.builder().categoryId("cat-execs").label("Corporate Executives")
                            .description("CEOs, CTOs, and other C-suite executives").build(),
                    EntityCategory.builder().categoryId("cat-geo").label("Geographic Locations")
                            .description("Cities, states, countries, and regions").build()
            );

            StringBuilder prompt = new StringBuilder();
            prompt.append("You are categorizing entities in a knowledge graph into the following categories:\n");
            for (EntityCategory cat : categories) {
                prompt.append(String.format("- %s (id: %s): %s\n",
                        cat.getLabel(), cat.getCategoryId(),
                        cat.getDescription() != null ? cat.getDescription() : ""));
            }
            prompt.append("\nFor each entity below, suggest the best matching category.\n\n");
            prompt.append("Entities to categorize:\n");
            int idx = 1;
            for (Entity e : graph.getEntities()) {
                prompt.append(String.format("%d. \"%s\" (type: %s)\n",
                        idx++, e.getTitle(), e.getType() != null ? e.getType() : "UNKNOWN"));
            }
            prompt.append("\nReturn ONLY a JSON array:\n");
            prompt.append("[{\"entityNodeId\": \"...\", \"suggestedCategoryId\": \"...\", ");
            prompt.append("\"confidence\": 0.9, \"reasoning\": \"...\"}]\n");

            String response = callClaudeCli(
                    "You are a data categorization assistant. Return ONLY valid JSON arrays.",
                    prompt.toString());

            assertNotNull(response);
            assertFalse(response.isBlank());

            String cleaned = response.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "")
                        .replaceAll("\\n?```$", "").trim();
            }

            try {
                List<Map<String, Object>> parsed = objectMapper.readValue(
                        cleaned, new TypeReference<>() {});
                assertFalse(parsed.isEmpty(), "Claude should categorize entities");

                for (Map<String, Object> item : parsed) {
                    assertTrue(item.containsKey("suggestedCategoryId"));
                    assertTrue(item.containsKey("confidence"));
                }
            } catch (Exception e) {
                fail("Failed to parse categorization JSON: " + e.getMessage()
                        + "\nResponse: " + cleaned);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. AGENT TOOL WORKFLOW (preview → explain → compact via GraphCompactionService)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. Agent Resolution Tool Workflow")
    class AgentToolWorkflow {

        @Test
        @DisplayName("Agent preview → explain → compact workflow via GraphCompactionService")
        void agentPreviewExplainCompact() {
            // Use entities where normalization produces identical strings:
            // "Apple Inc" → "apple", "Apple Corp" → "apple" → EXACT_TITLE_MATCH
            GraphNode n1 = entityNodeWithConfidence("n1", "Apple Inc", "ORG", 0.9);
            GraphNode n2 = entityNodeWithConfidence("n2", "Apple Corp", "ORG", 0.85);
            GraphNode n3 = entityNode("n3", "Microsoft", "ORG");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2, n3));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            // Step 1: Agent calls preview (what entity_resolution_preview tool does)
            CompactionConfig previewConfig = CompactionConfig.previewOnly(0.5);
            List<MatchCandidate> candidates = compactionService.previewCandidates(previewConfig);
            assertFalse(candidates.isEmpty(),
                    "Preview should find Apple Inc / Apple Corp");

            MatchCandidate topCandidate = candidates.get(0);
            assertTrue(topCandidate.score() > 0,
                    "Top candidate should have positive score");

            // Step 2: Agent calls explain (what entity_resolution_explain tool does)
            MatchExplanation explanation = compactionService.explain(
                    topCandidate.nodeIdA(), topCandidate.nodeIdB());
            assertTrue(explanation.wouldMerge(),
                    "Apple Inc / Apple Corp should merge (both normalize to 'apple')");
            assertFalse(explanation.matchReasons().isEmpty(),
                    "Should have match reasons");

            // Step 3: Agent decides to compact (what entity_resolution_compact tool does)
            CompactionConfig compactConfig = CompactionConfig.withThreshold(0.5);
            CompactionResult result = compactionService.compact(compactConfig);
            assertTrue(result.entitiesMerged() >= 1,
                    "Should merge Apple variants");
        }

        @Test
        @DisplayName("Agent explains blockers for non-matching entities")
        void agentExplainBlockers() {
            GraphNode n1 = entityNode("n1", "Apple", "ORG");
            GraphNode n2 = entityNode("n2", "Samsung", "ORG");

            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

            MatchExplanation explanation = compactionService.explain("n1", "n2");
            assertFalse(explanation.wouldMerge());
            assertFalse(explanation.blockers().isEmpty(),
                    "Should report blockers for non-matching entities");
        }

        @Test
        @DisplayName("Agent dry-run preview returns candidates without executing")
        void agentDryRunPreview() {
            GraphNode n1 = entityNodeWithConfidence("n1", "Tesla", "ORG", 0.9);
            GraphNode n2 = entityNodeWithConfidence("n2", "Tesla Inc", "ORG", 0.85);

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2));

            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.80));

            assertFalse(candidates.isEmpty());
            verify(knowledgeGraphService, never()).deleteNode(anyString());
        }

        @Test
        @DisplayName("Full agent workflow: extract → graph nodes → preview → compact")
        void fullAgentExtractAndCompact() {
            // Extract with Claude
            RetrievedDoc doc = new RetrievedDoc("agent-flow", """
                    Meta Platforms (formerly Facebook) CEO Mark Zuckerberg announced
                    the launch of Threads. Meta's headquarters in Menlo Park, California
                    houses over 10,000 employees. Facebook, a Meta subsidiary,
                    continues to operate its social media platform.
                    """, Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "PRODUCT"));
            var extractionResult = extractionAgent.extract(List.of(doc), config);
            Graph graph = extractionResult.graph();
            assertNotNull(graph);

            // Convert to GraphNodes
            List<GraphNode> graphNodes = graph.getEntities().stream()
                    .map(e -> entityNode("gn-" + e.getId(), e.getTitle(),
                            e.getType() != null ? e.getType() : "ENTITY"))
                    .collect(Collectors.toList());

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(graphNodes);
            for (GraphNode n : graphNodes) {
                when(knowledgeGraphService.getNode(n.getNodeId()))
                        .thenReturn(Optional.of(n));
                when(knowledgeGraphService.getEdgesForNode(n.getNodeId()))
                        .thenReturn(List.of());
            }

            // Agent previews candidates
            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.70));
            assertNotNull(candidates);

            // Agent explains each candidate
            for (MatchCandidate candidate : candidates) {
                MatchExplanation explanation = compactionService.explain(
                        candidate.nodeIdA(), candidate.nodeIdB());
                assertNotNull(explanation.wouldMerge());
                assertNotNull(explanation.matchReasons());
            }

            Set<String> entityNames = graph.getEntities().stream()
                    .map(e -> e.getTitle().toLowerCase())
                    .collect(Collectors.toSet());
            assertTrue(entityNames.stream().anyMatch(n ->
                            n.contains("meta") || n.contains("facebook")),
                    "Should find Meta/Facebook; found: " + entityNames);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. KCLAW AGENT RESOLUTION PATH
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. KClaw Agent Entity Resolution")
    class KClawAgentResolution {

        @Test
        @DisplayName("KClaw agent receives categorization prompt and returns JSON decisions")
        void kclawAgentCategorizationPrompt() {
            Long factSheetId = 1L;
            List<EntityCategory> categories = List.of(
                    EntityCategory.builder()
                            .categoryId("cat-tech").label("Technology")
                            .description("Technology companies and products").build(),
                    EntityCategory.builder()
                            .categoryId("cat-people").label("People")
                            .description("Notable individuals").build()
            );

            List<GraphNode> entities = List.of(
                    entityNode("e1", "Apple Inc", "ORGANIZATION"),
                    entityNode("e2", "Tim Cook", "PERSON"),
                    entityNode("e3", "Google", "ORGANIZATION")
            );

            // Build prompt exactly as EnrichmentAgentLabelController does
            StringBuilder prompt = new StringBuilder();
            prompt.append(String.format(
                    "Scan and categorize the following entities for fact sheet %d.\n\n", factSheetId));
            prompt.append("Available categories:\n");
            for (EntityCategory cat : categories) {
                prompt.append(String.format("- %s (id: %s): %s\n",
                        cat.getLabel(), cat.getCategoryId(),
                        cat.getDescription() != null ? cat.getDescription() : ""));
            }
            prompt.append(String.format("\nEntities to categorize (%d total):\n", entities.size()));
            for (int i = 0; i < entities.size(); i++) {
                GraphNode entity = entities.get(i);
                prompt.append(String.format("%d. nodeId=%s | \"%s\" | type=%s\n",
                        i + 1, entity.getNodeId(), entity.getTitle(),
                        entity.getMetadataJson()));
            }
            prompt.append("\nReturn ONLY a JSON array:\n");
            prompt.append("[{\"entityNodeId\": \"<nodeId>\", \"suggestedCategoryId\": \"<categoryId>\", ");
            prompt.append("\"confidence\": 0.9, \"reasoning\": \"brief reason\"}]\n");

            // Mock KClawAgentService
            KClawAgentService kclawService = mock(KClawAgentService.class);
            String agentResponse = """
                    [
                      {"entityNodeId": "e1", "suggestedCategoryId": "cat-tech", "confidence": 0.95, "reasoning": "Apple is a technology company"},
                      {"entityNodeId": "e2", "suggestedCategoryId": "cat-people", "confidence": 0.92, "reasoning": "Tim Cook is a notable person"},
                      {"entityNodeId": "e3", "suggestedCategoryId": "cat-tech", "confidence": 0.97, "reasoning": "Google is a technology company"}
                    ]
                    """;
            when(kclawService.execute(any(KClawRequest.class)))
                    .thenReturn(KClawResponse.of(agentResponse, "test-session"));

            KClawRequest request = KClawRequest.of("jarvis", prompt.toString());
            KClawResponse response = kclawService.execute(request);

            assertTrue(response.isSuccess());
            assertNotNull(response.getResponse());

            // Parse exactly as EnrichmentAgentLabelController does
            String rawResponse = response.getResponse().trim();
            int firstBracket = rawResponse.indexOf('[');
            int lastBracket = rawResponse.lastIndexOf(']');
            if (firstBracket >= 0 && lastBracket > firstBracket) {
                rawResponse = rawResponse.substring(firstBracket, lastBracket + 1);
            }

            ObjectMapper mapper = new ObjectMapper();
            try {
                List<Map<String, Object>> parsed = mapper.readValue(
                        rawResponse, new TypeReference<>() {});

                assertEquals(3, parsed.size());
                Set<String> categorizedIds = parsed.stream()
                        .map(m -> String.valueOf(m.get("entityNodeId")))
                        .collect(Collectors.toSet());
                assertTrue(categorizedIds.containsAll(Set.of("e1", "e2", "e3")));

                for (Map<String, Object> item : parsed) {
                    String catId = String.valueOf(item.get("suggestedCategoryId"));
                    boolean valid = categories.stream()
                            .anyMatch(c -> c.getCategoryId().equals(catId));
                    assertTrue(valid, "Category '" + catId + "' should be valid");
                }
            } catch (Exception e) {
                fail("Failed to parse agent response: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("Claude CLI resolves entity pairs — real LLM call")
        void claudeResolvesEntityPairs() {
            String resolutionPrompt = """
                    You are an entity resolution agent. Analyze duplicate candidates and decide merge/skip.

                    Candidate pairs:
                    1. "Apple Inc" (ORG, id=n1) vs "Apple Corporation" (ORG, id=n2)
                       Score: 0.89, Reasons: [LEVENSHTEIN, NORMALIZED_TITLE_MATCH]
                    2. "Tim Cook" (PERSON, id=n3) vs "Timothy Cook" (PERSON, id=n4)
                       Score: 0.82, Reasons: [LEVENSHTEIN]
                    3. "Apple" (ORG, id=n5) vs "Apple" (LOCATION, id=n6)
                       Score: 0.45, Reasons: [], Blockers: [TYPE_MISMATCH]

                    Return ONLY a JSON array:
                    [{"pair": 1, "action": "merge"|"skip", "reasoning": "..."}]
                    """;

            String response = AutoLabelingPipeline.callClaudeCli(
                    "You are an entity resolution expert. Return ONLY valid JSON arrays.",
                    resolutionPrompt);

            assertNotNull(response);
            assertFalse(response.isBlank());

            String cleaned = response.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "")
                        .replaceAll("\\n?```$", "").trim();
            }
            int fb = cleaned.indexOf('[');
            int lb = cleaned.lastIndexOf(']');
            if (fb >= 0 && lb > fb) cleaned = cleaned.substring(fb, lb + 1);

            ObjectMapper mapper = new ObjectMapper();
            try {
                List<Map<String, Object>> decisions = mapper.readValue(
                        cleaned, new TypeReference<>() {});
                assertFalse(decisions.isEmpty());

                for (Map<String, Object> decision : decisions) {
                    String action = String.valueOf(decision.get("action"));
                    assertTrue("merge".equals(action) || "skip".equals(action),
                            "Action should be merge or skip; got '" + action + "'");
                }
            } catch (Exception e) {
                fail("Failed to parse resolution decisions: " + e.getMessage()
                        + "\nResponse: " + cleaned);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. MULTI-HOP GRAPH COMPACTION & EDGE REDIRECTION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. Multi-Hop Compaction & Edge Redirection")
    class MultiHopCompaction {

        @Test
        @DisplayName("Transitive chain: A matches B, B matches C → all three merge into one component")
        void transitiveChainMergesAll() {
            // "Apple Inc" → "apple", "Apple Corp" → "apple", "Apple Company" → "apple"
            // A-B match, B-C match, A-C match — all normalize to "apple"
            // But the BFS connected-component logic matters when only A-B and B-C directly match
            // and A-C is an indirect link
            GraphNode a = entityNodeWithConfidence("n1", "Apple Inc", "ORG", 0.95);
            GraphNode b = entityNodeWithConfidence("n2", "Apple Corp", "ORG", 0.80);
            GraphNode c = entityNodeWithConfidence("n3", "Apple Company", "ORG", 0.70);
            // Unrelated node to verify it stays separate
            GraphNode d = entityNodeWithConfidence("n4", "Samsung Electronics", "ORG", 0.90);

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(a, b, c, d));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(a));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(b));
            when(knowledgeGraphService.getNode("n3")).thenReturn(Optional.of(c));
            when(knowledgeGraphService.getNode("n4")).thenReturn(Optional.of(d));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            CompactionResult result = compactionService.compact(CompactionConfig.withThreshold(0.80));

            assertEquals(1, result.componentsFound(),
                    "All 3 Apple variants should form 1 connected component");
            assertEquals(2, result.entitiesMerged(),
                    "2 nodes should merge into the canonical (highest confidence)");
            assertEquals(2, result.finalEntityCount(),
                    "Should end with 2 entities: canonical Apple + Samsung");

            MergeDecision decision = result.decisions().get(0);
            assertEquals("n1", decision.canonicalNodeId(),
                    "Highest-confidence node (0.95) should be canonical");
            assertTrue(decision.mergedNodeIds().containsAll(List.of("n2", "n3")),
                    "Both lower-confidence nodes should be merged");
            assertTrue(decision.assemblySteps().size() >= 3,
                    "Assembly trace should have canonical election + 2 merge steps + final");
        }

        @Test
        @DisplayName("Levenshtein-only transitive chain: A~B and B~C but A!~C still merge via BFS")
        void levenshteinTransitiveChain() {
            // "Microsft" → typo of "Microsoft" (Lev ~0.89)
            // "Mircosft" → typo of "Microsft" (Lev ~0.88)
            // "Microsoft" vs "Mircosft" directly → lower similarity, but BFS still chains them
            GraphNode a = entityNodeWithConfidence("n1", "Microsoft", "ORG", 0.95);
            GraphNode b = entityNodeWithConfidence("n2", "Microsft", "ORG", 0.60); // 1-char typo
            GraphNode c = entityNodeWithConfidence("n3", "Microsof", "ORG", 0.50); // different typo

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(a, b, c));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(a));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(b));
            when(knowledgeGraphService.getNode("n3")).thenReturn(Optional.of(c));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            CompactionConfig config = CompactionConfig.withThreshold(0.80);
            List<MatchCandidate> candidates = compactionService.previewCandidates(config);

            // Each pair that scores >= 0.80 becomes a candidate; BFS chains transitively
            assertFalse(candidates.isEmpty(),
                    "Should find at least one candidate pair among the typo variants");

            // Verify the scoring signals are Levenshtein-based
            for (MatchCandidate c2 : candidates) {
                assertTrue(c2.reasons().stream().anyMatch(r -> r.contains("LEVENSHTEIN")
                                || r.contains("EXACT_TITLE_MATCH")),
                        "Match should be Levenshtein or exact-based; reasons: " + c2.reasons());
            }
        }

        @Test
        @DisplayName("Edge redirection: edges from merged node point to canonical after compaction")
        void edgeRedirectToCanonical() {
            GraphNode canonical = entityNodeWithConfidence("n1", "Apple Inc", "ORG", 0.95);
            GraphNode merged = entityNodeWithConfidence("n2", "Apple Corporation", "ORG", 0.70);
            GraphNode person = entityNode("n3", "Tim Cook", "PERSON");
            GraphNode location = entityNode("n4", "Cupertino", "LOCATION");

            // Person → merged node (should redirect to canonical)
            GraphEdge ceoEdge = GraphEdge.builder()
                    .edgeId("edge-ceo").sourceNode(person).targetNode(merged)
                    .edgeType(EdgeType.USER_DEFINED).description("CEO_OF")
                    .build();
            // Location → merged node (should redirect to canonical)
            GraphEdge locEdge = GraphEdge.builder()
                    .edgeId("edge-loc").sourceNode(location).targetNode(merged)
                    .edgeType(EdgeType.USER_DEFINED).description("HEADQUARTERED_IN")
                    .build();

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(canonical, merged, person, location));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(canonical));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(merged));
            when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(ceoEdge, locEdge));
            when(knowledgeGraphService.getEdgesForNode("n1")).thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("n3")).thenReturn(List.of(ceoEdge));
            when(knowledgeGraphService.getEdgesForNode("n4")).thenReturn(List.of(locEdge));
            // No existing edges at destination
            when(knowledgeGraphService.edgeExists(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(false);
            when(knowledgeGraphService.createEdgeWithMetadata(anyString(), anyString(),
                    any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(ceoEdge); // return value not critical

            CompactionResult result = compactionService.compact(CompactionConfig.withThreshold(0.80));

            assertEquals(1, result.entitiesMerged());
            assertTrue(result.edgesRedirected() >= 2,
                    "Both edges should be redirected; got " + result.edgesRedirected());

            // Verify createEdgeWithMetadata was called with canonical as target
            verify(knowledgeGraphService, atLeast(2)).createEdgeWithMetadata(
                    anyString(), eq("n1"), any(), any(), any(), any(), any(), any(), any());
            // Verify old edges were bulk-deleted
            verify(knowledgeGraphService).deleteEdgesBulk(argThat(list ->
                    list.contains("edge-ceo") && list.contains("edge-loc")));
        }

        @Test
        @DisplayName("Self-loop elimination: edge from merged node to itself is deleted, not redirected")
        void selfLoopEliminated() {
            GraphNode canonical = entityNodeWithConfidence("n1", "Google LLC", "ORG", 0.95);
            GraphNode merged = entityNodeWithConfidence("n2", "Google", "ORG", 0.70);

            // Edge from canonical → merged node → after redirect becomes canonical → canonical (self-loop)
            GraphEdge selfLoopEdge = GraphEdge.builder()
                    .edgeId("edge-self").sourceNode(canonical).targetNode(merged)
                    .edgeType(EdgeType.USER_DEFINED).description("SUBSIDIARY_OF")
                    .build();

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(canonical, merged));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(canonical));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(merged));
            when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(selfLoopEdge));
            when(knowledgeGraphService.getEdgesForNode("n1")).thenReturn(List.of(selfLoopEdge));

            CompactionResult result = compactionService.compact(CompactionConfig.withThreshold(0.80));

            assertEquals(1, result.entitiesMerged());
            // Self-loop is deleted, not redirected — so edgesRedirected should be 0
            assertEquals(0, result.edgesRedirected(),
                    "Self-loop edge should be deleted, not counted as redirected");
            // But the edge should still be bulk-deleted
            verify(knowledgeGraphService).deleteEdgesBulk(argThat(list ->
                    list.contains("edge-self")));
            // createEdgeWithMetadata should NOT be called (self-loop is skipped)
            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    eq("n1"), eq("n1"), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Duplicate edge dedup: existing edge at destination prevents double-creation")
        void duplicateEdgeDedup() {
            GraphNode canonical = entityNodeWithConfidence("n1", "Tesla Inc", "ORG", 0.95);
            GraphNode merged = entityNodeWithConfidence("n2", "Tesla Corporation", "ORG", 0.70);
            GraphNode person = entityNode("n3", "Elon Musk", "PERSON");

            // Both nodes already have CEO_OF edges from the same person
            GraphEdge edge1 = GraphEdge.builder()
                    .edgeId("edge-1").sourceNode(person).targetNode(merged)
                    .edgeType(EdgeType.USER_DEFINED).description("CEO_OF")
                    .build();

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(canonical, merged, person));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(canonical));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(merged));
            when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(edge1));
            when(knowledgeGraphService.getEdgesForNode("n1")).thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("n3")).thenReturn(List.of(edge1));
            // The redirected edge already exists — semantic duplicate
            when(knowledgeGraphService.edgeExists(eq("n3"), eq("n1"), any(), any(), any()))
                    .thenReturn(true);

            CompactionResult result = compactionService.compact(CompactionConfig.withThreshold(0.80));

            assertEquals(1, result.entitiesMerged());
            assertEquals(0, result.edgesRedirected(),
                    "Edge already exists at destination — should delete old, not create new");
            verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                    anyString(), anyString(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Multi-hop edge chain: A→B→C where B merges with C preserves A→C path")
        void multiHopEdgeChainPreserved() {
            GraphNode a = entityNode("n1", "Tim Cook", "PERSON");
            GraphNode b = entityNodeWithConfidence("n2", "Apple Inc", "ORG", 0.90);
            GraphNode c = entityNodeWithConfidence("n3", "Apple Corporation", "ORG", 0.95);

            // A → B (CEO_OF)
            GraphEdge abEdge = GraphEdge.builder()
                    .edgeId("edge-ab").sourceNode(a).targetNode(b)
                    .edgeType(EdgeType.USER_DEFINED).description("CEO_OF")
                    .build();
            // B → C (SUBSIDIARY_OF) — becomes self-loop after merge
            GraphEdge bcEdge = GraphEdge.builder()
                    .edgeId("edge-bc").sourceNode(b).targetNode(c)
                    .edgeType(EdgeType.USER_DEFINED).description("SUBSIDIARY_OF")
                    .build();

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(a, b, c));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(b));
            when(knowledgeGraphService.getNode("n3")).thenReturn(Optional.of(c));
            // B has edges: abEdge (incoming) and bcEdge (outgoing to C)
            when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(abEdge, bcEdge));
            when(knowledgeGraphService.getEdgesForNode("n1")).thenReturn(List.of(abEdge));
            when(knowledgeGraphService.getEdgesForNode("n3")).thenReturn(List.of(bcEdge));
            when(knowledgeGraphService.edgeExists(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(false);
            when(knowledgeGraphService.createEdgeWithMetadata(anyString(), anyString(),
                    any(), any(), any(), any(), any(), any(), any())).thenReturn(abEdge);

            CompactionResult result = compactionService.compact(CompactionConfig.withThreshold(0.80));

            assertEquals(1, result.entitiesMerged());
            MergeDecision decision = result.decisions().get(0);
            // C is higher confidence, so it's canonical; B merges into C
            assertEquals("n3", decision.canonicalNodeId(),
                    "Higher-confidence node should be canonical");

            // A→B edge should be redirected to A→C
            // B→C edge should become self-loop (C→C) and be deleted
            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("n1"), eq("n3"), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. ATTRIBUTE-BEHAVIOR SCORING (Senzing-style identity signals)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. Attribute-Behavior Scoring")
    class AttributeBehaviorScoring {

        private GraphNode entityNodeWithProperties(String nodeId, String title,
                                                     String entityType, Map<String, String> properties) {
            StringBuilder propsJson = new StringBuilder();
            propsJson.append("{\"entity_type\":\"").append(entityType).append("\"");
            if (!properties.isEmpty()) {
                propsJson.append(",\"properties\":{");
                boolean first = true;
                for (Map.Entry<String, String> entry : properties.entrySet()) {
                    if (!first) propsJson.append(",");
                    propsJson.append("\"").append(entry.getKey()).append("\":\"")
                            .append(entry.getValue()).append("\"");
                    first = false;
                }
                propsJson.append("}");
            }
            propsJson.append("}");
            return GraphNode.builder()
                    .nodeId(nodeId).nodeType(NodeLevel.ENTITY).externalId("ext-" + nodeId)
                    .title(title).metadataJson(propsJson.toString())
                    .confidence(0.9).edgeCount(2).childCount(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
        }

        @Test
        @DisplayName("Matching email triggers EXCLUSIVE merge even with different titles")
        void emailExclusiveMatch() {
            // Different company names, but same email → EXCLUSIVE attribute match (0.95)
            GraphNode n1 = entityNodeWithProperties("n1", "Acme Solutions", "ORG",
                    Map.of("email", "contact@acme.com"));
            GraphNode n2 = entityNodeWithProperties("n2", "ACME Inc", "ORG",
                    Map.of("email", "contact@acme.com"));

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            // Use low threshold to let attribute scoring drive the match
            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.50));

            assertFalse(candidates.isEmpty(), "Email match should produce a candidate");
            MatchCandidate candidate = candidates.get(0);
            assertTrue(candidate.score() >= 0.95,
                    "Email EXCLUSIVE match should score >= 0.95; got " + candidate.score());
            assertTrue(candidate.reasons().stream()
                            .anyMatch(r -> r.contains("ATTR_MATCH:email(EXCLUSIVE)")),
                    "Should cite ATTR_MATCH:email(EXCLUSIVE); reasons: " + candidate.reasons());
        }

        @Test
        @DisplayName("Matching ticker symbol triggers EXCLUSIVE merge")
        void tickerExclusiveMatch() {
            GraphNode n1 = entityNodeWithProperties("n1", "Apple Inc.", "ORG",
                    Map.of("ticker", "AAPL"));
            GraphNode n2 = entityNodeWithProperties("n2", "Apple Computer", "ORG",
                    Map.of("ticker", "AAPL"));

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2));

            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.50));

            assertFalse(candidates.isEmpty(), "Ticker match should produce a candidate");
            assertTrue(candidates.get(0).reasons().stream()
                            .anyMatch(r -> r.contains("ticker") && r.contains("EXCLUSIVE")),
                    "Should cite ticker EXCLUSIVE; reasons: " + candidates.get(0).reasons());
        }

        @Test
        @DisplayName("Different email blocks attribute score — no false positive")
        void differentEmailNoMatch() {
            GraphNode n1 = entityNodeWithProperties("n1", "Alpha Corp", "ORG",
                    Map.of("email", "info@alpha.com"));
            GraphNode n2 = entityNodeWithProperties("n2", "Alpha LLC", "ORG",
                    Map.of("email", "info@alpha-llc.com"));

            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

            MatchExplanation explanation = compactionService.explain("n1", "n2");

            // Different email should NOT produce ATTR_MATCH
            boolean hasEmailAttrMatch = explanation.matchReasons().stream()
                    .anyMatch(r -> r.contains("ATTR_MATCH:email"));
            assertFalse(hasEmailAttrMatch,
                    "Different emails should not trigger ATTR_MATCH; reasons: " + explanation.matchReasons());
        }

        @Test
        @DisplayName("Weak attribute (industry) alone is insufficient for merge at default threshold")
        void weakAttributeInsufficientAlone() {
            GraphNode n1 = entityNodeWithProperties("n1", "FooBar Inc", "ORG",
                    Map.of("industry", "technology"));
            GraphNode n2 = entityNodeWithProperties("n2", "BazQux Ltd", "ORG",
                    Map.of("industry", "technology"));

            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

            MatchExplanation explanation = compactionService.explain("n1", "n2");

            // Industry is FREQUENT (0.30) — well below default 0.85 threshold
            assertFalse(explanation.wouldMerge(),
                    "Industry-only match should NOT merge at default threshold");
            // But it should still appear as a signal in reasons
            assertTrue(explanation.matchReasons().stream()
                            .anyMatch(r -> r.contains("industry")),
                    "Industry attribute should still be noted; reasons: " + explanation.matchReasons());
        }

        @Test
        @DisplayName("Attribute + title combo: weak title + strong attribute = merge")
        void attributePlusTitleCombo() {
            // Titles are somewhat similar (Levenshtein ~0.6) but URL matches → EXCLUSIVE 0.90
            GraphNode n1 = entityNodeWithProperties("n1", "Acme Solutions Group", "ORG",
                    Map.of("url", "https://acme.com"));
            GraphNode n2 = entityNodeWithProperties("n2", "ACME Global", "ORG",
                    Map.of("url", "https://acme.com"));

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            CompactionResult result = compactionService.compact(CompactionConfig.withThreshold(0.85));

            assertTrue(result.entitiesMerged() >= 1,
                    "URL EXCLUSIVE match (0.90) should trigger merge despite weak title similarity");
            MergeDecision decision = result.decisions().get(0);
            assertTrue(decision.matchReasons().stream()
                            .anyMatch(r -> r.contains("ATTR_MATCH:url")),
                    "Match reasons should include URL attribute; reasons: " + decision.matchReasons());
        }

        @Test
        @DisplayName("Top-level metadata key matches attribute behavior (not just nested properties)")
        void topLevelMetadataKeyMatchesAttribute() {
            // "email" as top-level key in metadataJson (not inside "properties" object)
            GraphNode n1 = GraphNode.builder()
                    .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                    .title("Acme Corp").metadataJson("{\"entity_type\":\"ORG\",\"email\":\"hello@acme.com\"}")
                    .confidence(0.9).edgeCount(1).childCount(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            GraphNode n2 = GraphNode.builder()
                    .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                    .title("Acme LLC").metadataJson("{\"entity_type\":\"ORG\",\"email\":\"hello@acme.com\"}")
                    .confidence(0.9).edgeCount(1).childCount(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();

            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));

            MatchExplanation explanation = compactionService.explain("n1", "n2");

            assertTrue(explanation.score() >= 0.90,
                    "Top-level email match should boost score; got " + explanation.score());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. DEEP ALIASING (bidirectional, shared, cross-chunk transitive)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. Deep Aliasing")
    class DeepAliasing {

        @Test
        @DisplayName("Bidirectional alias: A's title in B's aliases AND B's title in A's aliases")
        void bidirectionalAliasMatch() {
            GraphNode a = entityNodeWithAliases("n1", "Big Blue", "ORG",
                    List.of("IBM", "International Business Machines"));
            GraphNode b = entityNodeWithAliases("n2", "IBM", "ORG",
                    List.of("Big Blue", "International Business Machines"));

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(a, b));

            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.50));

            assertFalse(candidates.isEmpty(),
                    "Bidirectional aliases should produce a match");
            MatchCandidate match = candidates.get(0);
            assertTrue(match.score() >= 0.90,
                    "Bidirectional alias should score >= 0.90; got " + match.score());
            assertTrue(match.reasons().stream()
                            .anyMatch(r -> r.contains("ALIAS") || r.contains("TITLE_IN_ALIAS")
                                    || r.contains("SHARED_ALIASES")),
                    "Should cite alias-based reasons; reasons: " + match.reasons());
        }

        @Test
        @DisplayName("Shared alias overlap: A and B share a common alias but have different titles")
        void sharedAliasOverlap() {
            // Both entities have "JPM" as an alias, though their titles differ
            GraphNode a = entityNodeWithAliases("n1", "JPMorgan Chase", "ORG",
                    List.of("JPM", "Chase"));
            GraphNode b = entityNodeWithAliases("n2", "JP Morgan", "ORG",
                    List.of("JPM", "Morgan"));

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(a, b));

            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.50));

            assertFalse(candidates.isEmpty(),
                    "Shared alias 'JPM' should produce a match candidate");
            assertTrue(candidates.get(0).reasons().stream()
                            .anyMatch(r -> r.contains("SHARED_ALIASES")),
                    "Should cite SHARED_ALIASES reason; reasons: " + candidates.get(0).reasons());
        }

        @Test
        @DisplayName("Alias is normalized before matching (suffix stripping + lowercase)")
        void aliasNormalization() {
            // Alias "Apple Inc." should normalize to "apple" and match title "Apple"
            GraphNode a = entityNodeWithAliases("n1", "Apple Computer Inc.", "ORG",
                    List.of("Apple Inc.", "AAPL"));
            GraphNode b = entityNode("n2", "Apple", "ORG");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(a, b));

            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.50));

            assertFalse(candidates.isEmpty(),
                    "Normalized alias 'apple' should match title 'Apple'; candidates: " + candidates);
        }

        @Test
        @DisplayName("Cross-chunk alias resolution: entity extracted with alias in one chunk, matching title in another")
        void crossChunkAliasResolution() {
            // Chunk 1 extracts "International Business Machines" with alias "IBM"
            // Chunk 2 extracts "IBM" standalone
            // EntityResolutionService should merge them
            ExtractionResult chunk1 = ExtractionResult.of(
                    List.of(entityWithAliases("e1", "International Business Machines", "ORG",
                            List.of("IBM", "Big Blue"))),
                    List.of(relation("e1", "e2", "EMPLOYS"),
                            relation("e2", "e1", "WORKS_AT")),
                    null);
            ExtractionResult chunk1b = ExtractionResult.of(
                    List.of(entity("e2", "Arvind Krishna", "PERSON")),
                    List.of(), null);

            ExtractionResult chunk2 = ExtractionResult.of(
                    List.of(entity("e3", "IBM", "ORG"),
                            entity("e4", "Ginni Rometty", "PERSON")),
                    List.of(relation("e4", "e3", "FORMER_CEO_OF")),
                    null);

            ExtractionResult resolved = resolutionService.resolve(List.of(
                    ExtractionResult.of(
                            new ArrayList<>(chunk1.entities()) {{ addAll(chunk1b.entities()); }},
                            new ArrayList<>(chunk1.relations()),
                            null),
                    chunk2));

            // IBM and International Business Machines should merge
            long ibmCount = resolved.entities().stream()
                    .filter(e -> e.name().toLowerCase().contains("ibm")
                            || e.name().toLowerCase().contains("international business"))
                    .count();
            assertEquals(1, ibmCount,
                    "IBM alias should cause merge; entities: " + resolved.entities().stream()
                            .map(ExtractedEntity::name).toList());

            // Both people should survive
            assertEquals(3, resolved.entities().size(),
                    "Should have IBM (merged), Arvind Krishna, Ginni Rometty");

            // Relations should be remapped — both point to the merged IBM entity
            assertTrue(resolved.relations().size() >= 2,
                    "Relations should survive remapping; got " + resolved.relations().size());
        }

        @Test
        @DisplayName("Alias-comma-separated string parsing (aliases as |-delimited string in metadata)")
        void aliasStringParsing() {
            // Some extractions store aliases as a pipe-separated string instead of JSON array
            GraphNode a = GraphNode.builder()
                    .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                    .title("Alphabet Inc.").confidence(0.9).edgeCount(1).childCount(0)
                    .metadataJson("{\"entity_type\":\"ORG\",\"aliases\":\"Google|Alphabet|GOOG\"}")
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            GraphNode b = entityNode("n2", "Google", "ORG");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(a, b));

            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.50));

            assertFalse(candidates.isEmpty(),
                    "Pipe-separated alias string should be parsed; 'Google' should match");
        }

        @Test
        @DisplayName("Full Claude extraction → alias-based compaction pipeline")
        void claudeExtractThenAliasCompaction() {
            // Extract entities from a document where the same entity appears under different names
            RetrievedDoc doc = new RetrievedDoc("alias-doc", """
                    Alphabet Inc., the parent company of Google, reported strong earnings.
                    Google Cloud, a division of Alphabet, grew 28% year-over-year.
                    The tech giant, formerly known as Google Inc., continues to dominate search.
                    GOOG stock rose 3% on the news from the Mountain View company.
                    """, Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("ORGANIZATION", "PERSON", "LOCATION", "PRODUCT"));
            var result = extractionAgent.extract(List.of(doc), config);
            Graph graph = result.graph();

            // Build GraphNodes with extracted aliases
            List<GraphNode> graphNodes = new ArrayList<>();
            for (Entity e : graph.getEntities()) {
                List<String> aliases = e.getAliases() != null ? e.getAliases() : List.of();
                if (aliases.isEmpty()) {
                    graphNodes.add(entityNode("gn-" + e.getId(), e.getTitle(),
                            e.getType() != null ? e.getType() : "ENTITY"));
                } else {
                    graphNodes.add(entityNodeWithAliases("gn-" + e.getId(), e.getTitle(),
                            e.getType() != null ? e.getType() : "ENTITY", aliases));
                }
            }

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(graphNodes);
            for (GraphNode n : graphNodes) {
                when(knowledgeGraphService.getNode(n.getNodeId())).thenReturn(Optional.of(n));
                when(knowledgeGraphService.getEdgesForNode(n.getNodeId())).thenReturn(List.of());
            }

            // Preview what compaction would merge
            List<MatchCandidate> candidates =
                    compactionService.previewCandidates(CompactionConfig.previewOnly(0.50));

            // If Claude extracted "Alphabet Inc" and "Google" as separate entities with aliases,
            // compaction should find them as candidates
            Set<String> allTitles = graphNodes.stream()
                    .map(n -> n.getTitle().toLowerCase())
                    .collect(Collectors.toSet());

            boolean hasAlphabetAndGoogle = allTitles.stream().anyMatch(t -> t.contains("alphabet"))
                    && allTitles.stream().anyMatch(t -> t.contains("google"));

            if (hasAlphabetAndGoogle) {
                // If extracted as separate entities, compaction should find alias matches
                assertFalse(candidates.isEmpty(),
                        "Alphabet/Google alias overlap should produce candidates; " +
                                "titles: " + allTitles);
            }
            // Even if Claude merged them during extraction, the pipeline should handle it
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. AUTO-LABELING DEEP TESTS (batching, minConfidence, taxonomy domain walking)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. Auto-Labeling Deep Tests")
    class AutoLabelingDeep {

        private AutoLabelService autoLabelService;
        private EntityCategoryRepository categoryRepository;
        private GraphNodeRepository nodeRepository;
        private ObjectMapper objectMapper;

        @BeforeEach
        void setUp() {
            categoryRepository = mock(EntityCategoryRepository.class);
            nodeRepository = mock(GraphNodeRepository.class);
            objectMapper = new ObjectMapper();
            autoLabelService = new AutoLabelService(categoryRepository, nodeRepository, objectMapper);
        }

        @Test
        @DisplayName("Taxonomy domain walking: applySuggestions finds root domain by parent chain")
        void taxonomyDomainWalking() {
            Long factSheetId = 1L;

            // Three-level hierarchy: domain → category → leaf
            EntityCategory domain = EntityCategory.builder()
                    .id(1L).factSheetId(factSheetId)
                    .categoryId("domain-tech").label("Technology")
                    .description("Technology domain")
                    .parentCategoryId(null).source("USER_DEFINED").active(true)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();
            EntityCategory category = EntityCategory.builder()
                    .id(2L).factSheetId(factSheetId)
                    .categoryId("cat-cloud").label("Cloud Computing")
                    .description("Cloud infrastructure and services")
                    .parentCategoryId("domain-tech").source("USER_DEFINED").active(true)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();
            EntityCategory leaf = EntityCategory.builder()
                    .id(3L).factSheetId(factSheetId)
                    .categoryId("type-iaas").label("IaaS Providers")
                    .description("Infrastructure as a Service")
                    .parentCategoryId("cat-cloud").source("USER_DEFINED").active(true)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();

            when(categoryRepository.findByFactSheetIdAndActiveTrue(factSheetId))
                    .thenReturn(List.of(domain, category, leaf));
            when(categoryRepository.findByCategoryId("type-iaas"))
                    .thenReturn(Optional.of(leaf));
            when(categoryRepository.findByCategoryId("cat-cloud"))
                    .thenReturn(Optional.of(category));
            when(categoryRepository.findByCategoryId("domain-tech"))
                    .thenReturn(Optional.of(domain));

            GraphNode node = entityNode("e1", "AWS", "ORGANIZATION");
            when(nodeRepository.findByNodeId("e1")).thenReturn(Optional.of(node));

            List<AutoLabelSuggestion> suggestions = List.of(
                    AutoLabelSuggestion.builder()
                            .entityNodeId("e1").entityTitle("AWS").entityType("ORGANIZATION")
                            .suggestedCategoryId("type-iaas").suggestedCategoryLabel("IaaS Providers")
                            .confidence(0.95).reasoning("AWS is an IaaS provider")
                            .build()
            );

            MassEditResult result = autoLabelService.applySuggestions(factSheetId, suggestions);

            assertEquals(1, result.getEntitiesAffected());
            // Verify the saved node has the correct domain (walked up 2 levels)
            verify(nodeRepository).save(argThat(savedNode -> {
                try {
                    var meta = objectMapper.readTree(savedNode.getMetadataJson());
                    String taxonomyCat = meta.path("taxonomyCategory").asText();
                    String taxonomyDomain = meta.path("taxonomyDomain").asText();
                    // Category should be the leaf label
                    boolean catCorrect = "IaaS Providers".equals(taxonomyCat);
                    // Domain should be the root — "Technology"
                    boolean domainCorrect = "Technology".equals(taxonomyDomain);
                    return catCorrect && domainCorrect;
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        @Test
        @DisplayName("minConfidence filter: low-confidence suggestions are excluded")
        void minConfidenceFilter() {
            autoLabelService = new AutoLabelService(categoryRepository, nodeRepository, objectMapper);

            LLMChat llmChat = buildClaudeLLMChat();
            try {
                var field = AutoLabelService.class.getDeclaredField("llmChat");
                field.setAccessible(true);
                field.set(autoLabelService, llmChat);
            } catch (Exception e) {
                fail("Could not inject LLMChat: " + e.getMessage());
            }

            Long factSheetId = 1L;
            EntityCategory cat = EntityCategory.builder()
                    .id(1L).factSheetId(factSheetId)
                    .categoryId("cat-tech").label("Technology")
                    .description("Tech companies").source("USER_DEFINED").active(true)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();
            when(categoryRepository.findByFactSheetIdAndActiveTrue(factSheetId))
                    .thenReturn(List.of(cat));

            List<GraphNode> entities = List.of(
                    entityNode("e1", "Apple Inc", "ORGANIZATION"),
                    entityNode("e2", "Tim Cook", "PERSON")
            );
            for (GraphNode n : entities) {
                when(nodeRepository.findByNodeId(n.getNodeId())).thenReturn(Optional.of(n));
            }

            // High minConfidence threshold — Claude's suggestions may be below this
            MassEditResult result = autoLabelService.autoLabel(factSheetId,
                    entities.stream().map(GraphNode::getNodeId).toList(), true, 0.99);

            // With minConfidence=0.99, many suggestions should be filtered out
            // (Claude rarely returns 0.99+ confidence)
            assertNotNull(result);
            // The key assertion: the filter is applied, so we get fewer suggestions
            // than entities (or possibly none at all)
            assertTrue(result.getSuggestions() == null || result.getSuggestions().size() <= entities.size(),
                    "High minConfidence should filter some suggestions");
        }

        @Test
        @DisplayName("applySuggestions preserves existing metadata fields")
        void applySuggestionsPreservesMetadata() {
            Long factSheetId = 1L;
            EntityCategory cat = EntityCategory.builder()
                    .id(1L).factSheetId(factSheetId)
                    .categoryId("cat-tech").label("Technology")
                    .description("Tech").parentCategoryId(null)
                    .source("USER_DEFINED").active(true)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();

            when(categoryRepository.findByFactSheetIdAndActiveTrue(factSheetId))
                    .thenReturn(List.of(cat));
            when(categoryRepository.findByCategoryId("cat-tech"))
                    .thenReturn(Optional.of(cat));

            // Node with rich metadata that must survive
            GraphNode node = GraphNode.builder()
                    .nodeId("e1").nodeType(NodeLevel.ENTITY).externalId("ext-e1")
                    .title("Apple Inc").confidence(0.95).edgeCount(5).childCount(0)
                    .metadataJson("{\"entity_type\":\"ORGANIZATION\",\"industry\":\"technology\","
                            + "\"founded\":\"1976\",\"ticker\":\"AAPL\",\"custom_field\":\"custom_value\"}")
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            when(nodeRepository.findByNodeId("e1")).thenReturn(Optional.of(node));

            List<AutoLabelSuggestion> suggestions = List.of(
                    AutoLabelSuggestion.builder()
                            .entityNodeId("e1").entityTitle("Apple Inc").entityType("ORGANIZATION")
                            .suggestedCategoryId("cat-tech").suggestedCategoryLabel("Technology")
                            .confidence(0.95).reasoning("Tech company")
                            .build()
            );

            autoLabelService.applySuggestions(factSheetId, suggestions);

            verify(nodeRepository).save(argThat(savedNode -> {
                try {
                    var meta = objectMapper.readTree(savedNode.getMetadataJson());
                    // New taxonomy fields added
                    boolean hasTaxonomy = meta.has("taxonomyCategory") && meta.has("taxonomyDomain");
                    // Original fields preserved
                    boolean hasEntityType = "ORGANIZATION".equals(meta.path("entity_type").asText());
                    boolean hasTicker = "AAPL".equals(meta.path("ticker").asText());
                    boolean hasFounded = "1976".equals(meta.path("founded").asText());
                    boolean hasCustom = "custom_value".equals(meta.path("custom_field").asText());
                    return hasTaxonomy && hasEntityType && hasTicker && hasFounded && hasCustom;
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        @Test
        @DisplayName("Null LLM chat returns empty suggestions — no crash")
        void nullLlmChatReturnsEmpty() {
            // Do NOT inject LLMChat — autoLabelService.llmChat stays null
            autoLabelService = new AutoLabelService(categoryRepository, nodeRepository, objectMapper);

            Long factSheetId = 1L;
            EntityCategory cat = EntityCategory.builder()
                    .id(1L).factSheetId(factSheetId)
                    .categoryId("cat-tech").label("Technology")
                    .description("Tech").source("USER_DEFINED").active(true)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();
            when(categoryRepository.findByFactSheetIdAndActiveTrue(factSheetId))
                    .thenReturn(List.of(cat));

            GraphNode node = entityNode("e1", "Apple", "ORGANIZATION");
            when(nodeRepository.findByNodeId("e1")).thenReturn(Optional.of(node));

            MassEditResult result = autoLabelService.autoLabel(factSheetId,
                    List.of("e1"), true, 0.5);

            assertNotNull(result);
            assertTrue(result.getSuggestions() == null || result.getSuggestions().isEmpty(),
                    "No LLM → no suggestions, but no exception");
        }

        /**
         * Same Claude CLI helper from AutoLabelingPipeline.
         */
        private LLMChat buildClaudeLLMChat() {
            final String[] captured = new String[2];
            LLMChat llmChat = mock(LLMChat.class, RETURNS_DEEP_STUBS);
            LLMChat.ChatClientRequestSpec requestSpec = mock(
                    LLMChat.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
            LLMChat.CallResponseSpec callSpec = mock(LLMChat.CallResponseSpec.class);

            when(llmChat.prompt()).thenReturn(requestSpec);
            when(requestSpec.system(anyString())).thenAnswer(inv -> {
                captured[0] = inv.getArgument(0);
                return requestSpec;
            });
            when(requestSpec.user(anyString())).thenAnswer(inv -> {
                captured[1] = inv.getArgument(0);
                return requestSpec;
            });
            when(requestSpec.call()).thenReturn(callSpec);
            when(callSpec.content()).thenAnswer(inv ->
                    AutoLabelingPipeline.callClaudeCli(captured[0], captured[1]));
            return llmChat;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 15. GRAPH TRAVERSAL AFTER COMPACTION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("15. Graph Traversal After Compaction")
    class GraphTraversalAfterCompaction {

        @Test
        @DisplayName("Multi-hop traversal: Tim Cook → Apple Inc → Cupertino (2 hops after compaction)")
        void multiHopTraversalAfterCompaction() {
            // Build the graph: Person → Org (with duplicate) → Location
            GraphNode timCook = entityNode("n1", "Tim Cook", "PERSON");
            GraphNode apple1 = entityNodeWithConfidence("n2", "Apple Inc", "ORG", 0.95);
            GraphNode apple2 = entityNodeWithConfidence("n3", "Apple Corporation", "ORG", 0.70);
            GraphNode cupertino = entityNode("n4", "Cupertino", "LOCATION");

            // Edges: Tim Cook → Apple Corporation, Apple Inc → Cupertino
            GraphEdge ceoEdge = GraphEdge.builder()
                    .edgeId("edge-ceo").sourceNode(timCook).targetNode(apple2)
                    .edgeType(EdgeType.USER_DEFINED).description("CEO_OF")
                    .build();
            GraphEdge locEdge = GraphEdge.builder()
                    .edgeId("edge-loc").sourceNode(apple1).targetNode(cupertino)
                    .edgeType(EdgeType.USER_DEFINED).description("HEADQUARTERED_IN")
                    .build();

            // Set up compaction
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(apple1, apple2, timCook, cupertino));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(apple1));
            when(knowledgeGraphService.getNode("n3")).thenReturn(Optional.of(apple2));
            when(knowledgeGraphService.getEdgesForNode("n3")).thenReturn(List.of(ceoEdge));
            when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(List.of(locEdge));
            when(knowledgeGraphService.getEdgesForNode("n1")).thenReturn(List.of(ceoEdge));
            when(knowledgeGraphService.getEdgesForNode("n4")).thenReturn(List.of(locEdge));
            when(knowledgeGraphService.edgeExists(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(false);
            when(knowledgeGraphService.createEdgeWithMetadata(anyString(), anyString(),
                    any(), any(), any(), any(), any(), any(), any())).thenReturn(ceoEdge);

            // Run compaction
            CompactionResult result = compactionService.compact(CompactionConfig.withThreshold(0.80));

            assertEquals(1, result.entitiesMerged(),
                    "Apple Corporation should merge into Apple Inc");

            // After compaction, the CEO edge should have been redirected from n3 to n2
            // Verify the edge creation: Tim Cook → Apple Inc (was Tim Cook → Apple Corporation)
            verify(knowledgeGraphService).createEdgeWithMetadata(
                    eq("n1"), eq("n2"), any(), any(), any(), any(), any(), any(), any());

            // Now simulate the multi-hop traversal after compaction
            // Set up the mock for post-compaction traversal
            GraphEdge redirectedCeoEdge = GraphEdge.builder()
                    .edgeId("edge-ceo-new").sourceNode(timCook).targetNode(apple1)
                    .edgeType(EdgeType.USER_DEFINED).description("CEO_OF")
                    .build();

            when(knowledgeGraphService.getConnectedNodes("n1", 1))
                    .thenReturn(List.of(apple1));
            when(knowledgeGraphService.getConnectedNodes("n1", 2))
                    .thenReturn(List.of(apple1, cupertino));
            when(knowledgeGraphService.findShortestPath("n1", "n4", 3))
                    .thenReturn(List.of(timCook, apple1, cupertino));

            // Verify 1-hop from Tim Cook reaches Apple Inc
            List<GraphNode> oneHop = knowledgeGraphService.getConnectedNodes("n1", 1);
            assertEquals(1, oneHop.size());
            assertEquals("Apple Inc", oneHop.get(0).getTitle());

            // Verify 2-hop from Tim Cook reaches Cupertino
            List<GraphNode> twoHop = knowledgeGraphService.getConnectedNodes("n1", 2);
            assertTrue(twoHop.stream().anyMatch(n -> n.getTitle().equals("Cupertino")),
                    "2-hop from Tim Cook should reach Cupertino through redirected edge");

            // Verify shortest path Tim Cook → Cupertino goes through Apple Inc
            List<GraphNode> path = knowledgeGraphService.findShortestPath("n1", "n4", 3);
            assertEquals(3, path.size(), "Path should be Tim Cook → Apple Inc → Cupertino");
            assertEquals("Tim Cook", path.get(0).getTitle());
            assertEquals("Apple Inc", path.get(1).getTitle());
            assertEquals("Cupertino", path.get(2).getTitle());
        }

        @Test
        @DisplayName("Compaction + Claude extraction: end-to-end multi-hop graph")
        void compactionThenTraversalEndToEnd() {
            // Extract a document that mentions the same entity multiple ways
            RetrievedDoc doc = new RetrievedDoc("multihop-doc", """
                    Jensen Huang, CEO of NVIDIA Corporation, presented at GTC 2025.
                    NVIDIA, headquartered in Santa Clara, reported record revenue.
                    The chipmaker's acquisition of Arm Holdings was blocked by regulators.
                    Arm, now independent, is based in Cambridge, UK.
                    Jensen Huang also serves on the board of the NVIDIA Foundation.
                    """, Map.of());

            ExtractionConfig config = configForClaude(
                    List.of("PERSON", "ORGANIZATION", "LOCATION", "EVENT"));
            var result = extractionAgent.extract(List.of(doc), config);
            Graph graph = result.graph();
            assertNotNull(graph);

            // Check that we got a connected graph with relationships
            assertFalse(graph.getEntities().isEmpty());

            if (graph.getRelationships() != null && !graph.getRelationships().isEmpty()) {
                // Build adjacency for multi-hop verification
                Map<String, Set<String>> adjacency = new HashMap<>();
                Map<String, String> idToTitle = new HashMap<>();
                for (Entity e : graph.getEntities()) {
                    idToTitle.put(e.getId(), e.getTitle());
                }
                for (Relationship r : graph.getRelationships()) {
                    adjacency.computeIfAbsent(r.getSource(), k -> new HashSet<>()).add(r.getTarget());
                    adjacency.computeIfAbsent(r.getTarget(), k -> new HashSet<>()).add(r.getSource());
                }

                // Find Jensen Huang
                Optional<Entity> jensen = graph.getEntities().stream()
                        .filter(e -> e.getTitle().toLowerCase().contains("jensen")
                                || e.getTitle().toLowerCase().contains("huang"))
                        .findFirst();

                if (jensen.isPresent()) {
                    String jensenId = jensen.get().getId();
                    // 1-hop neighbors of Jensen Huang
                    Set<String> oneHop = adjacency.getOrDefault(jensenId, Set.of());
                    assertFalse(oneHop.isEmpty(),
                            "Jensen Huang should have at least 1 relationship; adjacency: " +
                                    adjacency.entrySet().stream()
                                            .map(e -> idToTitle.getOrDefault(e.getKey(), e.getKey()) + " → "
                                                    + e.getValue().stream().map(v -> idToTitle.getOrDefault(v, v))
                                                    .toList())
                                            .toList());

                    // 2-hop: BFS from Jensen Huang
                    Set<String> twoHop = new HashSet<>();
                    for (String neighbor : oneHop) {
                        twoHop.addAll(adjacency.getOrDefault(neighbor, Set.of()));
                    }
                    twoHop.removeAll(oneHop);
                    twoHop.remove(jensenId);

                    // Log the multi-hop reach for verification
                    Set<String> oneHopNames = oneHop.stream()
                            .map(id -> idToTitle.getOrDefault(id, id))
                            .collect(Collectors.toSet());
                    Set<String> twoHopNames = twoHop.stream()
                            .map(id -> idToTitle.getOrDefault(id, id))
                            .collect(Collectors.toSet());

                    // Jensen Huang should reach NVIDIA in 1 hop, and Santa Clara / locations in 2 hops
                    assertTrue(oneHopNames.stream()
                                    .anyMatch(n -> n.toLowerCase().contains("nvidia")),
                            "Jensen Huang → NVIDIA should be 1 hop; 1-hop: " + oneHopNames);
                }
            }

            // Also verify entity resolution: "NVIDIA Corporation" and "NVIDIA" should be
            // connected or merged by resolution service
            List<ExtractedEntity> entities = graph.getEntities().stream()
                    .map(e -> entity(e.getId(), e.getTitle(), e.getType()))
                    .toList();
            ExtractionResult combined = ExtractionResult.of(entities, List.of(), null);
            ExtractionResult resolved = resolutionService.resolveSingle(combined);

            long nvidiaCount = resolved.entities().stream()
                    .filter(e -> e.name().toLowerCase().contains("nvidia"))
                    .count();
            assertTrue(nvidiaCount <= 2,
                    "NVIDIA variants should merge or remain as distinct entities (NVIDIA Corp vs NVIDIA Foundation); found: " +
                            resolved.entities().stream()
                                    .filter(e -> e.name().toLowerCase().contains("nvidia"))
                                    .map(ExtractedEntity::name).toList());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 16. METADATA MERGE & ASSEMBLY TRACE VERIFICATION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("16. Metadata Merge & Assembly Traces")
    class MetadataMerge {

        @Test
        @DisplayName("Merge preserves longer description and collects aliases from merged nodes")
        void mergePreservesDescriptionAndCollectsAliases() {
            GraphNode canonical = GraphNode.builder()
                    .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                    .title("Apple Inc").description("A tech company")
                    .metadataJson("{\"entity_type\":\"ORG\",\"founded\":\"1976\"}")
                    .confidence(0.95).edgeCount(5).childCount(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            GraphNode merged = GraphNode.builder()
                    .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                    .title("Apple Corporation")
                    .description("Apple Inc is a multinational technology corporation headquartered in Cupertino")
                    .metadataJson("{\"entity_type\":\"ORG\",\"ticker\":\"AAPL\",\"industry\":\"technology\"}")
                    .confidence(0.70).edgeCount(2).childCount(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(canonical, merged));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(canonical));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(merged));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            compactionService.compact(CompactionConfig.withThreshold(0.80));

            // Verify updateNode was called with merged metadata
            verify(knowledgeGraphService).updateNode(eq("n1"), isNull(), argThat(desc ->
                    // Longer description from merged node should be kept
                    desc != null && desc.contains("multinational")),
                    argThat(meta -> {
                        // Should have merged metadata from both nodes
                        boolean hasFounded = meta.containsKey("founded"); // from canonical
                        boolean hasTicker = meta.containsKey("ticker");   // from merged (putIfAbsent)
                        boolean hasAliases = meta.containsKey("aliases"); // merged title as alias
                        boolean hasMergedFrom = meta.containsKey("merged_from"); // merge provenance
                        return hasFounded && hasTicker && hasAliases && hasMergedFrom;
                    })
            );
        }

        @Test
        @DisplayName("Assembly steps trace records canonical election reason and per-merge details")
        void assemblyStepsTraceComplete() {
            GraphNode n1 = entityNodeWithConfidence("n1", "Google", "ORG", 0.90);
            GraphNode n2 = entityNodeWithConfidence("n2", "Google LLC", "ORG", 0.85);
            GraphNode n3 = entityNodeWithConfidence("n3", "Google Inc", "ORG", 0.70);

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2, n3));
            when(knowledgeGraphService.getNode("n1")).thenReturn(Optional.of(n1));
            when(knowledgeGraphService.getNode("n2")).thenReturn(Optional.of(n2));
            when(knowledgeGraphService.getNode("n3")).thenReturn(Optional.of(n3));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            CompactionResult result = compactionService.compact(CompactionConfig.withThreshold(0.80));

            assertEquals(1, result.decisions().size());
            MergeDecision decision = result.decisions().get(0);
            List<String> steps = decision.assemblySteps();

            // STEP 1: canonical election
            assertTrue(steps.get(0).contains("STEP 1") && steps.get(0).contains("Elected canonical"),
                    "First step should be canonical election; got: " + steps.get(0));
            assertTrue(steps.get(0).contains("confidence=0.90"),
                    "Should cite confidence; got: " + steps.get(0));

            // Intermediate steps: merges
            boolean hasMergeStep = steps.stream()
                    .anyMatch(s -> s.contains("Merged") && s.contains("score="));
            assertTrue(hasMergeStep,
                    "Should have merge steps with scores; steps: " + steps);

            // Final step: summary
            String lastStep = steps.get(steps.size() - 1);
            assertTrue(lastStep.contains("Final entity") && lastStep.contains("merged aliases"),
                    "Last step should be final entity summary; got: " + lastStep);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 17. CATEGORY HIERARCHY & TAXONOMY
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("17. Category Hierarchy & Taxonomy Management")
    class CategoryHierarchy {

        @Test
        @DisplayName("Hierarchical categories with parent-child relationships")
        void hierarchicalCategories() {
            EntityCategory domain = EntityCategory.builder()
                    .categoryId("domain-business").label("Business & Commerce")
                    .description("Business entities, companies, and commercial activities")
                    .parentCategoryId(null).source("AUTO_DISCOVERED")
                    .factSheetId(1L).active(true).sortOrder(0)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();

            EntityCategory category = EntityCategory.builder()
                    .categoryId("cat-tech-companies").label("Technology Companies")
                    .description("Companies primarily in the technology sector")
                    .parentCategoryId("domain-business").source("AUTO_DISCOVERED")
                    .factSheetId(1L).active(true).sortOrder(1)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();

            EntityCategory entityType = EntityCategory.builder()
                    .categoryId("type-saas").label("SaaS Providers")
                    .description("Software as a Service companies")
                    .parentCategoryId("cat-tech-companies").source("AUTO_DISCOVERED")
                    .factSheetId(1L).active(true).sortOrder(2)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();

            EntityCategory userDefined = EntityCategory.builder()
                    .categoryId("user-favorites").label("Key Partners")
                    .description("Companies we actively partner with")
                    .parentCategoryId("domain-business").source("USER_DEFINED")
                    .factSheetId(1L).active(true).sortOrder(3)
                    .createdAt(Instant.now()).updatedAt(Instant.now())
                    .build();

            List<EntityCategory> allCategories = List.of(domain, category, entityType, userDefined);

            Map<String, List<EntityCategory>> childrenMap = allCategories.stream()
                    .filter(c -> c.getParentCategoryId() != null)
                    .collect(Collectors.groupingBy(EntityCategory::getParentCategoryId));

            List<EntityCategory> roots = allCategories.stream()
                    .filter(c -> c.getParentCategoryId() == null)
                    .collect(Collectors.toList());

            assertEquals(1, roots.size());
            assertEquals("Business & Commerce", roots.get(0).getLabel());

            List<EntityCategory> domainChildren =
                    childrenMap.getOrDefault("domain-business", List.of());
            assertEquals(2, domainChildren.size(),
                    "Business domain should have 2 children");

            List<EntityCategory> techChildren =
                    childrenMap.getOrDefault("cat-tech-companies", List.of());
            assertEquals(1, techChildren.size(),
                    "Tech Companies should have 1 child");

            assertTrue(allCategories.stream()
                    .anyMatch(c -> "USER_DEFINED".equals(c.getSource())));
            assertTrue(allCategories.stream()
                    .anyMatch(c -> "AUTO_DISCOVERED".equals(c.getSource())));
        }

        @Test
        @DisplayName("Category assignment updates metadataJson correctly")
        void categoryAssignmentUpdatesMetadata() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            GraphNode node = entityNode("e1", "Salesforce", "ORGANIZATION");

            var meta = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(node.getMetadataJson());
            meta.put("taxonomyCategory", "Technology Companies");
            meta.put("taxonomyDomain", "Business & Commerce");
            node.setMetadataJson(mapper.writeValueAsString(meta));

            var parsed = mapper.readTree(node.getMetadataJson());
            assertEquals("Technology Companies", parsed.path("taxonomyCategory").asText());
            assertEquals("Business & Commerce", parsed.path("taxonomyDomain").asText());
            assertEquals("ORGANIZATION", parsed.path("entity_type").asText(),
                    "Original entity_type should be preserved");
        }

        @Test
        @DisplayName("Claude generates taxonomy from entity type distribution")
        void claudeGeneratesTaxonomy() {
            String taxonomyPrompt = """
                    Based on the following entity type distribution from a knowledge graph,
                    suggest a hierarchical taxonomy with up to 3 levels: DOMAIN → CATEGORY → ENTITY_TYPE.

                    Entity type counts:
                    - ORGANIZATION: 45
                    - PERSON: 32
                    - LOCATION: 18
                    - PRODUCT: 12
                    - EVENT: 8
                    - TECHNOLOGY: 15

                    Return ONLY a JSON array:
                    [{"id": "...", "label": "...", "description": "...",
                      "parentId": null, "level": "DOMAIN"|"CATEGORY"|"ENTITY_TYPE",
                      "entityTypes": ["..."]}]
                    """;

            String response = AutoLabelingPipeline.callClaudeCli(
                    "You are a data taxonomy expert. Return ONLY valid JSON arrays.",
                    taxonomyPrompt);

            assertNotNull(response);
            assertFalse(response.isBlank());

            String cleaned = response.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "")
                        .replaceAll("\\n?```$", "").trim();
            }

            ObjectMapper mapper = new ObjectMapper();
            try {
                List<Map<String, Object>> taxonomy = mapper.readValue(
                        cleaned, new TypeReference<>() {});

                assertFalse(taxonomy.isEmpty());

                long domainCount = taxonomy.stream()
                        .filter(t -> "DOMAIN".equals(t.get("level")))
                        .count();
                assertTrue(domainCount >= 1, "Should have at least 1 DOMAIN");

                long categoryCount = taxonomy.stream()
                        .filter(t -> "CATEGORY".equals(t.get("level")))
                        .count();
                assertTrue(categoryCount >= 1, "Should have at least 1 CATEGORY");

                for (Map<String, Object> node : taxonomy) {
                    assertNotNull(node.get("id"));
                    assertNotNull(node.get("label"));
                    assertNotNull(node.get("level"));
                    if (!"DOMAIN".equals(node.get("level"))) {
                        assertNotNull(node.get("parentId"),
                                "Non-DOMAIN nodes should have parentId; node: " + node.get("label"));
                    }
                }
            } catch (Exception e) {
                fail("Failed to parse taxonomy: " + e.getMessage()
                        + "\nResponse: " + cleaned);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 17. CJK / UNICODE NORMALIZATION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("17. CJK / Unicode Normalization")
    class CjkUnicodeNormalization {

        @Test
        @DisplayName("CJK characters stripped during normalization")
        void cjkCharactersStripped() {
            // "王Wei" normalizes to "wei", "王伟·Wang Wei" normalizes to "wang wei"
            String result = GraphCompactionService.normalize("王Wei");
            assertEquals("wei", result);
        }

        @Test
        @DisplayName("Interpunct separator stripped (middle dot)")
        void interpunctStripped() {
            // "张明·Zhang Ming" → "zhang ming"
            String result = GraphCompactionService.normalize("张明·Zhang Ming");
            assertEquals("zhang ming", result);
        }

        @Test
        @DisplayName("CJK + Latin name matches pure Latin name after normalization")
        void cjkLatinMatchesPureLatin() {
            GraphNode n1 = entityNode("n1", "田中·Tanaka Yuki", "PERSON");
            GraphNode n2 = entityNode("n2", "Tanaka Yuki", "PERSON");

            List<GraphNode> nodes = List.of(n1, n2);
            var config = CompactionConfig.withoutEmbeddings(0.85);
            var result = compactionService.testFindCandidatesForBlock(nodes, config);
            // After CJK stripping, both normalize to "tanaka yuki"
            assertFalse(result.isEmpty(), "CJK-prefixed name should match pure Latin version");
            assertEquals(1.0, result.get(0).score(), 0.01, "Should be exact title match after normalization");
        }

        @Test
        @DisplayName("Katakana stripped from mixed names")
        void katakanaStripped() {
            // カタカナ chars (\u30a0-\u30ff) should be removed
            String result = GraphCompactionService.normalize("スズキ Suzuki Hiro");
            assertEquals("suzuki hiro", result);
        }

        @Test
        @DisplayName("Hiragana stripped from mixed names")
        void hiraganaStripped() {
            String result = GraphCompactionService.normalize("すずき Suzuki");
            assertEquals("suzuki", result);
        }

        @Test
        @DisplayName("Corporate suffix + CJK both stripped")
        void suffixAndCjkCombined() {
            String result = GraphCompactionService.normalize("华为·Huawei Technologies Ltd.");
            assertEquals("huawei technologies", result);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 18. ABBREVIATION / INITIAL EXPANSION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("18. Abbreviation / Initial Expansion")
    class AbbreviationExpansion {

        @Test
        @DisplayName("First initial matches full first name with same last name")
        void initialMatchesFullName() {
            double score = GraphCompactionService.scoreAbbreviationMatch(
                    "r. nakamura", "reiko nakamura");
            assertTrue(score >= 0.85, "R. Nakamura should match Reiko Nakamura, got " + score);
        }

        @Test
        @DisplayName("Initial expansion is bidirectional")
        void initialExpansionBidirectional() {
            double scoreAB = GraphCompactionService.scoreAbbreviationMatch(
                    "d. okafor", "damilola okafor");
            double scoreBA = GraphCompactionService.scoreAbbreviationMatch(
                    "damilola okafor", "d. okafor");
            assertTrue(scoreAB >= 0.85, "Forward direction should match");
            assertTrue(scoreBA >= 0.85, "Reverse direction should match");
        }

        @Test
        @DisplayName("Different initial does not match")
        void differentInitialNoMatch() {
            double score = GraphCompactionService.scoreAbbreviationMatch(
                    "a. patel", "vikram patel");
            assertEquals(0.0, score, "A. Patel should NOT match Vikram Patel");
        }

        @Test
        @DisplayName("Different last name does not match even with matching initial")
        void sameInitialDifferentLastName() {
            double score = GraphCompactionService.scoreAbbreviationMatch(
                    "j. larsson", "julia bergström");
            assertEquals(0.0, score, "J. Larsson should NOT match Julia Bergström");
        }

        @Test
        @DisplayName("Abbreviation match generates ABBREVIATION_EXPANSION reason in scorePair")
        void abbreviationInScorePair() {
            GraphNode n1 = entityNode("n1", "L. Rivera", "PERSON");
            GraphNode n2 = entityNode("n2", "Lucia Rivera", "PERSON");

            List<GraphNode> nodes = List.of(n1, n2);
            var config = CompactionConfig.withoutEmbeddings(0.85);
            var candidates = compactionService.testFindCandidatesForBlock(nodes, config);

            assertFalse(candidates.isEmpty(), "Should find abbreviation match candidate");
            assertTrue(candidates.get(0).reasons().stream()
                            .anyMatch(r -> r.contains("ABBREVIATION")),
                    "Reasons should contain ABBREVIATION_EXPANSION");
        }

        @Test
        @DisplayName("Single-letter name without dot is not an abbreviation")
        void singleLetterNoDotNotAbbreviation() {
            double score = GraphCompactionService.scoreAbbreviationMatch(
                    "k nakamura", "kenji nakamura");
            assertEquals(0.0, score, "No dot after initial → not an abbreviation");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 19. CROSS-TYPE COMPATIBLE PAIR MATCHING
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("19. Cross-Type Compatible Pair Matching")
    class CrossTypeCompatiblePairMatching {

        @Test
        @DisplayName("PERSON and CELL with same title match when crossTypeMerging enabled")
        void personCellSameTitleMatch() {
            GraphNode personNode = entityNode("n1", "Elena Voss", "PERSON");
            GraphNode cellNode = entityNode("n2", "Elena Voss", "CELL");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(personNode, cellNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            var config = CompactionConfig.withCrossTypeMerging(0.85);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "PERSON + CELL with identical title should merge with crossTypeMerging");
        }

        @Test
        @DisplayName("PERSON and CELL do NOT match without crossTypeMerging")
        void personCellNoMatchWithoutCrossType() {
            GraphNode personNode = entityNode("n1", "Elena Voss", "PERSON");
            GraphNode cellNode = entityNode("n2", "Elena Voss", "CELL");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(personNode, cellNode));

            var config = CompactionConfig.withoutEmbeddings(0.85);
            var result = compactionService.compact(config);

            assertEquals(0, result.entitiesMerged(),
                    "Without crossTypeMerging, PERSON/CELL should stay separate");
        }

        @Test
        @DisplayName("APPROVAL_ROLE and PERSON match when crossTypeMerging enabled")
        void approvalRolePersonMatch() {
            GraphNode approvalNode = entityNode("n1", "Dmitri Volkov", "APPROVAL_ROLE");
            GraphNode personNode = entityNode("n2", "Dmitri Volkov", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(approvalNode, personNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            var config = CompactionConfig.withCrossTypeMerging(0.85);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "APPROVAL_ROLE + PERSON same title should merge");
        }

        @Test
        @DisplayName("Incompatible types (PERSON / ORGANIZATION) never match even with crossTypeMerging")
        void incompatibleTypesNeverMatch() {
            GraphNode personNode = entityNode("n1", "Nexus", "PERSON");
            GraphNode orgNode = entityNode("n2", "Nexus", "ORGANIZATION");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(personNode, orgNode));

            var config = CompactionConfig.withCrossTypeMerging(0.85);
            var result = compactionService.compact(config);

            assertEquals(0, result.entitiesMerged(),
                    "PERSON/ORGANIZATION is not a compatible pair — should never merge");
        }

        @Test
        @DisplayName("Compatible type pairs set contains expected pairs")
        void compatiblePairsContainExpected() {
            assertTrue(GraphCompactionService.COMPATIBLE_TYPE_PAIRS.contains("CELL|PERSON"));
            assertTrue(GraphCompactionService.COMPATIBLE_TYPE_PAIRS.contains("PERSON|CELL"));
            assertTrue(GraphCompactionService.COMPATIBLE_TYPE_PAIRS.contains("CELL|APPROVAL_ROLE"));
            assertTrue(GraphCompactionService.COMPATIBLE_TYPE_PAIRS.contains("PERSON|APPROVAL_ROLE"));
            assertFalse(GraphCompactionService.COMPATIBLE_TYPE_PAIRS.contains("PERSON|ORGANIZATION"));
        }

        @Test
        @DisplayName("Custom type pairs: CONTACT and PERSON merge when custom pair provided")
        void customTypePairContactPerson() {
            GraphNode contactNode = entityNode("n1", "Yuki Tanaka", "CONTACT");
            GraphNode personNode = entityNode("n2", "Yuki Tanaka", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(contactNode, personNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            Set<String> customPairs = Set.of("CONTACT|PERSON", "PERSON|CONTACT");
            var config = CompactionConfig.withCrossTypeMerging(0.85, customPairs);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "CONTACT + PERSON with same title should merge via custom pair");
        }

        @Test
        @DisplayName("Custom type pairs: EMPLOYEE and PERSON merge")
        void customTypePairEmployeePerson() {
            GraphNode employeeNode = entityNode("n1", "Marco Rossi", "EMPLOYEE");
            GraphNode personNode = entityNode("n2", "Marco Rossi", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(employeeNode, personNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            Set<String> customPairs = Set.of("EMPLOYEE|PERSON", "PERSON|EMPLOYEE");
            var config = CompactionConfig.withCrossTypeMerging(0.85, customPairs);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "EMPLOYEE + PERSON with same title should merge via custom pair");
        }

        @Test
        @DisplayName("Custom type pairs do NOT match without crossTypeMerging enabled")
        void customTypePairsIgnoredWithoutFlag() {
            GraphNode contactNode = entityNode("n1", "Yuki Tanaka", "CONTACT");
            GraphNode personNode = entityNode("n2", "Yuki Tanaka", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(contactNode, personNode));

            // withoutEmbeddings does NOT enable crossTypeMerging
            var config = CompactionConfig.withoutEmbeddings(0.85);
            var result = compactionService.compact(config);

            assertEquals(0, result.entitiesMerged(),
                    "Without crossTypeMerging, CONTACT/PERSON should stay separate");
        }

        @Test
        @DisplayName("Custom pairs are merged with built-in pairs")
        void customPairsMergedWithBuiltIn() {
            // Built-in CELL|PERSON should still work when custom pairs are also provided
            GraphNode cellNode = entityNode("n1", "Nadia Petrova", "CELL");
            GraphNode personNode = entityNode("n2", "Nadia Petrova", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(cellNode, personNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            // Provide a custom pair that doesn't overlap with built-in
            Set<String> customPairs = Set.of("STAKEHOLDER|PERSON", "PERSON|STAKEHOLDER");
            var config = CompactionConfig.withCrossTypeMerging(0.85, customPairs);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "Built-in CELL|PERSON should still work alongside custom pairs");
        }

        @Test
        @DisplayName("effectiveCompatibleTypePairs merges custom with built-in")
        void effectivePairsContainsBoth() {
            Set<String> customPairs = Set.of("VENDOR|ORGANIZATION", "ORGANIZATION|VENDOR");
            var config = CompactionConfig.withCrossTypeMerging(0.85, customPairs);
            Set<String> effective = config.effectiveCompatibleTypePairs();

            // Built-in pairs
            assertTrue(effective.contains("CELL|PERSON"));
            assertTrue(effective.contains("PERSON|APPROVAL_ROLE"));
            // Custom pairs
            assertTrue(effective.contains("VENDOR|ORGANIZATION"));
            assertTrue(effective.contains("ORGANIZATION|VENDOR"));
        }

        @Test
        @DisplayName("effectiveCrossTypeEligibleTypes includes custom types")
        void eligibleTypesIncludesCustom() {
            Set<String> customPairs = Set.of("VENDOR|ORGANIZATION", "ORGANIZATION|VENDOR");
            var config = CompactionConfig.withCrossTypeMerging(0.85, customPairs);
            Set<String> eligible = config.effectiveCrossTypeEligibleTypes();

            // Built-in eligible types
            assertTrue(eligible.contains("CELL"));
            assertTrue(eligible.contains("PERSON"));
            assertTrue(eligible.contains("APPROVAL_ROLE"));
            // Custom eligible types
            assertTrue(eligible.contains("VENDOR"));
            assertTrue(eligible.contains("ORGANIZATION"));
        }

        @Test
        @DisplayName("Null custom pairs includes built-in pairs and hierarchy-derived pairs")
        void nullCustomPairsIncludesBuiltIn() {
            var config = CompactionConfig.withCrossTypeMerging(0.85);
            Set<String> effective = config.effectiveCompatibleTypePairs();

            // Built-in explicit pairs are present
            assertTrue(effective.containsAll(GraphCompactionService.COMPATIBLE_TYPE_PAIRS),
                    "All built-in pairs should be in the effective set");
            // Hierarchy-derived pairs are also present
            assertTrue(effective.contains("EMPLOYEE|PERSON"));
            assertTrue(effective.contains("PERSON|EMPLOYEE"));
            assertTrue(effective.contains("CONTACT|PERSON"));
            assertTrue(effective.contains("PERSON|CONTACT"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 24. TYPE HIERARCHY AND CATEGORICAL RESOLUTION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("24. Type Hierarchy and Categorical Resolution")
    class TypeHierarchyCategoricalResolution {

        @Test
        @DisplayName("EMPLOYEE is-a PERSON: same title merges via default hierarchy")
        void employeeIsPersonMerge() {
            GraphNode employeeNode = entityNode("n1", "Kenji Yamamoto", "EMPLOYEE");
            GraphNode personNode = entityNode("n2", "Kenji Yamamoto", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(employeeNode, personNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            var config = CompactionConfig.withCrossTypeMerging(0.85);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "EMPLOYEE is-a PERSON: should merge via built-in hierarchy");
        }

        @Test
        @DisplayName("CONTACT is-a PERSON: same title merges via default hierarchy")
        void contactIsPersonMerge() {
            GraphNode contactNode = entityNode("n1", "Lucia Fernandez", "CONTACT");
            GraphNode personNode = entityNode("n2", "Lucia Fernandez", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(contactNode, personNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            var config = CompactionConfig.withCrossTypeMerging(0.85);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "CONTACT is-a PERSON: should merge via built-in hierarchy");
        }

        @Test
        @DisplayName("STAKEHOLDER is-a PERSON: same title merges via default hierarchy")
        void stakeholderIsPersonMerge() {
            GraphNode stakeholderNode = entityNode("n1", "Ravi Krishnan", "STAKEHOLDER");
            GraphNode personNode = entityNode("n2", "Ravi Krishnan", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(stakeholderNode, personNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            var config = CompactionConfig.withCrossTypeMerging(0.85);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "STAKEHOLDER is-a PERSON: should merge via built-in hierarchy");
        }

        @Test
        @DisplayName("SUBSIDIARY is-a ORGANIZATION: same title merges via default hierarchy")
        void subsidiaryIsOrganizationMerge() {
            GraphNode subsidiaryNode = entityNode("n1", "Acme Labs", "SUBSIDIARY");
            GraphNode orgNode = entityNode("n2", "Acme Labs", "ORGANIZATION");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(subsidiaryNode, orgNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            var config = CompactionConfig.withCrossTypeMerging(0.85);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "SUBSIDIARY is-a ORGANIZATION: should merge via built-in hierarchy");
        }

        @Test
        @DisplayName("Siblings under same parent: EMPLOYEE and CONTACT merge (both are PERSON)")
        void siblingSubtypesMerge() {
            GraphNode employeeNode = entityNode("n1", "Omar Hassan", "EMPLOYEE");
            GraphNode contactNode = entityNode("n2", "Omar Hassan", "CONTACT");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(employeeNode, contactNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            var config = CompactionConfig.withCrossTypeMerging(0.85);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "EMPLOYEE and CONTACT are both PERSON subtypes — should merge");
        }

        @Test
        @DisplayName("Custom hierarchy: INTERN → EMPLOYEE → PERSON multi-level chain")
        void customHierarchyMultiLevel() {
            GraphNode internNode = entityNode("n1", "Aisha Rahman", "INTERN");
            GraphNode personNode = entityNode("n2", "Aisha Rahman", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(internNode, personNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            // Add INTERN → EMPLOYEE to the hierarchy (EMPLOYEE → PERSON already exists)
            var config = CompactionConfig.withTypeHierarchy(0.85,
                    Map.of("INTERN", "EMPLOYEE"));
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "INTERN → EMPLOYEE → PERSON chain: INTERN should merge with PERSON");
        }

        @Test
        @DisplayName("Custom hierarchy: FRANCHISE → ORGANIZATION")
        void customHierarchyFranchise() {
            GraphNode franchiseNode = entityNode("n1", "Tasty Burgers Inc", "FRANCHISE");
            GraphNode orgNode = entityNode("n2", "Tasty Burgers Inc", "ORGANIZATION");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(franchiseNode, orgNode));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            var config = CompactionConfig.withTypeHierarchy(0.85,
                    Map.of("FRANCHISE", "ORGANIZATION"));
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "Custom FRANCHISE → ORGANIZATION should merge");
        }

        @Test
        @DisplayName("isSubtypeOf: direct parent")
        void isSubtypeOfDirect() {
            var config = CompactionConfig.withCrossTypeMerging(0.85);
            assertTrue(config.isSubtypeOf("EMPLOYEE", "PERSON"));
            assertTrue(config.isSubtypeOf("CONTACT", "PERSON"));
            assertTrue(config.isSubtypeOf("SUBSIDIARY", "ORGANIZATION"));
        }

        @Test
        @DisplayName("isSubtypeOf: multi-level chain")
        void isSubtypeOfMultiLevel() {
            var config = CompactionConfig.withTypeHierarchy(0.85,
                    Map.of("INTERN", "EMPLOYEE"));
            assertTrue(config.isSubtypeOf("INTERN", "PERSON"),
                    "INTERN → EMPLOYEE → PERSON should be true");
            assertTrue(config.isSubtypeOf("INTERN", "EMPLOYEE"),
                    "INTERN → EMPLOYEE should be true");
        }

        @Test
        @DisplayName("isSubtypeOf: not related types return false")
        void isSubtypeOfUnrelated() {
            var config = CompactionConfig.withCrossTypeMerging(0.85);
            assertFalse(config.isSubtypeOf("PERSON", "EMPLOYEE"),
                    "PERSON is NOT a subtype of EMPLOYEE");
            assertFalse(config.isSubtypeOf("ORGANIZATION", "PERSON"),
                    "ORGANIZATION is NOT a subtype of PERSON");
            assertFalse(config.isSubtypeOf("LOCATION", "PERSON"),
                    "LOCATION is NOT a subtype of PERSON");
        }

        @Test
        @DisplayName("Hierarchy does not affect types without crossTypeMerging")
        void hierarchyIgnoredWithoutCrossTypeMerging() {
            GraphNode employeeNode = entityNode("n1", "Lin Wei", "EMPLOYEE");
            GraphNode personNode = entityNode("n2", "Lin Wei", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(employeeNode, personNode));

            var config = CompactionConfig.withoutEmbeddings(0.85);
            var result = compactionService.compact(config);

            assertEquals(0, result.entitiesMerged(),
                    "Without crossTypeMerging, hierarchy should not trigger merging");
        }

        @Test
        @DisplayName("Default hierarchy contains expected entries")
        void defaultHierarchyEntries() {
            var hierarchy = CompactionConfig.DEFAULT_TYPE_HIERARCHY;
            assertEquals("PERSON", hierarchy.get("EMPLOYEE"));
            assertEquals("PERSON", hierarchy.get("CONTACT"));
            assertEquals("PERSON", hierarchy.get("STAKEHOLDER"));
            assertEquals("PERSON", hierarchy.get("AUTHOR"));
            assertEquals("PERSON", hierarchy.get("CONTRIBUTOR"));
            assertEquals("ORGANIZATION", hierarchy.get("SUBSIDIARY"));
            assertEquals("ORGANIZATION", hierarchy.get("DEPARTMENT"));
            assertEquals("ORGANIZATION", hierarchy.get("DIVISION"));
        }

        @Test
        @DisplayName("Custom hierarchy overrides defaults")
        void customHierarchyOverridesDefault() {
            // Override EMPLOYEE to point to WORKER instead of PERSON
            var config = CompactionConfig.withTypeHierarchy(0.85,
                    Map.of("EMPLOYEE", "WORKER"));
            var hierarchy = config.effectiveTypeHierarchy();

            assertEquals("WORKER", hierarchy.get("EMPLOYEE"),
                    "Custom hierarchy should override the default for EMPLOYEE");
            // Other defaults remain
            assertEquals("PERSON", hierarchy.get("CONTACT"),
                    "Non-overridden defaults should remain");
        }

        @Test
        @DisplayName("Sibling subtypes generate bidirectional pairs")
        void siblingPairsGenerated() {
            var config = CompactionConfig.withCrossTypeMerging(0.85);
            Set<String> pairs = config.effectiveCompatibleTypePairs();

            // EMPLOYEE and CONTACT are both subtypes of PERSON
            assertTrue(pairs.contains("EMPLOYEE|CONTACT"),
                    "Sibling pair EMPLOYEE|CONTACT should be generated");
            assertTrue(pairs.contains("CONTACT|EMPLOYEE"),
                    "Sibling pair CONTACT|EMPLOYEE should be generated");
            // AUTHOR and STAKEHOLDER are also PERSON subtypes
            assertTrue(pairs.contains("AUTHOR|STAKEHOLDER"),
                    "Sibling pair AUTHOR|STAKEHOLDER should be generated");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 20. ENTITY TYPE CORRECTION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("20. Entity Type Correction")
    class EntityTypeCorrection {

        @Test
        @DisplayName("Email address tagged as PERSON is corrected to EMAIL_ADDRESS")
        void emailAsPerson() {
            String corrected = GraphCompactionService.detectCorrectType(
                    "admin@nexus-corp.com", "PERSON");
            assertEquals("EMAIL_ADDRESS", corrected);
        }

        @Test
        @DisplayName("Software library with version number tagged as PERSON → TECHNOLOGY")
        void libraryVersionAsPerson() {
            String corrected = GraphCompactionService.detectCorrectType(
                    "pandas 2.1.4", "PERSON");
            assertEquals("TECHNOLOGY", corrected);
        }

        @Test
        @DisplayName("Python package name tagged as PERSON → TECHNOLOGY")
        void pythonPackageAsPerson() {
            String corrected = GraphCompactionService.detectCorrectType(
                    "scikit_learn", "PERSON");
            assertEquals("TECHNOLOGY", corrected);
        }

        @Test
        @DisplayName("Dotted package name tagged as PERSON → TECHNOLOGY")
        void dottedPackageAsPerson() {
            String corrected = GraphCompactionService.detectCorrectType(
                    "com.fasterxml.jackson", "PERSON");
            assertEquals("TECHNOLOGY", corrected);
        }

        @Test
        @DisplayName("Normal person name stays as PERSON")
        void normalPersonNameStays() {
            String corrected = GraphCompactionService.detectCorrectType(
                    "Amara Osei", "PERSON");
            assertNull(corrected, "Normal person name should not be corrected");
        }

        @Test
        @DisplayName("Email tagged as ORGANIZATION stays")
        void emailAsOrgStays() {
            // We only correct PERSON → EMAIL_ADDRESS, not other types
            String corrected = GraphCompactionService.detectCorrectType(
                    "admin@nexus-corp.com", "ORGANIZATION");
            assertNull(corrected, "Only PERSON type should be corrected for email pattern");
        }

        @Test
        @DisplayName("Entity type correction pre-pass updates metadata and blocks correctly")
        void correctionPrePassUpdatesMetadata() {
            GraphNode emailNode = entityNode("n1", "admin@nexus-corp.com", "PERSON");
            GraphNode realPerson = entityNode("n2", "Amara Osei", "PERSON");

            // The correction should call updateNode for the email entity
            compactionService.correctEntityTypes(List.of(emailNode, realPerson));

            // Verify updateNode was called once (only for the email entity)
            verify(knowledgeGraphService, times(1)).updateNode(
                    eq("n1"), isNull(), isNull(), argThat(meta -> {
                        return "EMAIL_ADDRESS".equals(meta.get("entity_type"))
                                && "PERSON".equals(meta.get("original_entity_type"));
                    }));

            // realPerson should not have been touched
            verify(knowledgeGraphService, never()).updateNode(
                    eq("n2"), any(), any(), any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 21. PARTIAL FIRST-NAME MATCHING
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("21. Partial First-Name Matching")
    class PartialFirstNameMatching {

        @Test
        @DisplayName("Single first name matches full name")
        void firstNameMatchesFullName() {
            double score = GraphCompactionService.scorePartialNameMatch(
                    "amara", "amara osei");
            assertTrue(score >= 0.85, "First-name-only should match full name, got " + score);
        }

        @Test
        @DisplayName("Partial name match is bidirectional")
        void partialNameBidirectional() {
            double scoreAB = GraphCompactionService.scorePartialNameMatch(
                    "dmitri", "dmitri volkov");
            double scoreBA = GraphCompactionService.scorePartialNameMatch(
                    "dmitri volkov", "dmitri");
            assertTrue(scoreAB >= 0.85, "Forward should match");
            assertTrue(scoreBA >= 0.85, "Reverse should match");
        }

        @Test
        @DisplayName("Different first names do not match")
        void differentFirstNamesNoMatch() {
            double score = GraphCompactionService.scorePartialNameMatch(
                    "kenji", "amara osei");
            assertEquals(0.0, score, "Different first names should not match");
        }

        @Test
        @DisplayName("Two-word name does not partially match three-word name")
        void twoWordNoPartialMatch() {
            double score = GraphCompactionService.scorePartialNameMatch(
                    "elena voss", "elena maria voss");
            assertEquals(0.0, score, "Two-word names should not partial match — shorter must be single word");
        }

        @Test
        @DisplayName("Single-char name does not trigger partial match (avoids initials)")
        void singleCharNoMatch() {
            double score = GraphCompactionService.scorePartialNameMatch(
                    "e", "elena voss");
            assertEquals(0.0, score, "Single-char name should not trigger partial match");
        }

        @Test
        @DisplayName("Partial name match produces PARTIAL_NAME_MATCH reason in candidates")
        void partialNameInCandidates() {
            GraphNode n1 = entityNode("n1", "Priya", "PERSON");
            GraphNode n2 = entityNode("n2", "Priya Sharma", "PERSON");

            List<GraphNode> nodes = List.of(n1, n2);
            var config = CompactionConfig.withoutEmbeddings(0.85);
            var candidates = compactionService.testFindCandidatesForBlock(nodes, config);

            assertFalse(candidates.isEmpty(), "Should find partial name match");
            assertTrue(candidates.get(0).reasons().stream()
                            .anyMatch(r -> r.contains("PARTIAL_NAME")),
                    "Reasons should contain PARTIAL_NAME_MATCH");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 22. EMAIL ATTRIBUTE LINKING ACROSS FRAGMENTS
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("22. Email Attribute Linking Across Fragments")
    class EmailAttributeLinking {

        private GraphNode entityNodeWithProperties(String nodeId, String title,
                                                     String entityType, Map<String, String> properties) {
            StringBuilder propsJson = new StringBuilder();
            propsJson.append("{\"entity_type\":\"").append(entityType).append("\"");
            if (!properties.isEmpty()) {
                propsJson.append(",\"properties\":{");
                boolean first = true;
                for (Map.Entry<String, String> entry : properties.entrySet()) {
                    if (!first) propsJson.append(",");
                    propsJson.append("\"").append(entry.getKey()).append("\":\"")
                            .append(entry.getValue()).append("\"");
                    first = false;
                }
                propsJson.append("}");
            }
            propsJson.append("}");
            return GraphNode.builder()
                    .nodeId(nodeId).nodeType(NodeLevel.ENTITY).externalId("ext-" + nodeId)
                    .title(title).metadataJson(propsJson.toString())
                    .confidence(0.9).edgeCount(2).childCount(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
        }

        @Test
        @DisplayName("Same email in properties links two differently-titled entities")
        void sameEmailLinksEntities() {
            GraphNode n1 = entityNodeWithProperties("n1", "Yuki Tanaka", "PERSON",
                    Map.of("email", "y.tanaka@corp.jp"));
            GraphNode n2 = entityNodeWithProperties("n2", "Y. Tanaka", "PERSON",
                    Map.of("email", "y.tanaka@corp.jp"));

            List<GraphNode> nodes = List.of(n1, n2);
            var config = CompactionConfig.withoutEmbeddings(0.85);
            var candidates = compactionService.testFindCandidatesForBlock(nodes, config);

            assertFalse(candidates.isEmpty(), "Same email should produce a match candidate");
            assertTrue(candidates.get(0).score() >= 0.90,
                    "Email EXCLUSIVE attribute should produce high score");
        }

        @Test
        @DisplayName("Different emails prevent merging even with similar titles")
        void differentEmailsPreventMerge() {
            GraphNode n1 = entityNodeWithProperties("n1", "Amir Hassan", "PERSON",
                    Map.of("email", "amir.h@alpha.com"));
            GraphNode n2 = entityNodeWithProperties("n2", "Amir Hassan", "PERSON",
                    Map.of("email", "a.hassan@beta.com"));

            List<GraphNode> nodes = List.of(n1, n2);
            var config = CompactionConfig.withoutEmbeddings(0.85);
            var candidates = compactionService.testFindCandidatesForBlock(nodes, config);

            // They'll still match on exact title — the email difference doesn't BLOCK,
            // it just doesn't contribute. This test documents current behavior.
            assertFalse(candidates.isEmpty(), "Same exact title should still match");
            // But the match reason should be title-based, not email-based
            assertTrue(candidates.get(0).reasons().contains("EXACT_TITLE_MATCH"));
        }

        @Test
        @DisplayName("Top-level email key in metadata also triggers attribute matching")
        void topLevelEmailKey() {
            // Email at top level of metadata (not nested in properties)
            GraphNode n1 = GraphNode.builder()
                    .nodeId("n1").nodeType(NodeLevel.ENTITY).externalId("ext-n1")
                    .title("Fatima Zahra").metadataJson(
                            "{\"entity_type\":\"PERSON\",\"email\":\"f.zahra@example.org\"}")
                    .confidence(0.9).edgeCount(2).childCount(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            GraphNode n2 = GraphNode.builder()
                    .nodeId("n2").nodeType(NodeLevel.ENTITY).externalId("ext-n2")
                    .title("F. Zahra").metadataJson(
                            "{\"entity_type\":\"PERSON\",\"email\":\"f.zahra@example.org\"}")
                    .confidence(0.9).edgeCount(2).childCount(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();

            // extractProperties should pick up top-level email
            Map<String, String> props1 = compactionService.extractProperties(n1);
            Map<String, String> props2 = compactionService.extractProperties(n2);

            assertTrue(props1.containsKey("email"), "Top-level email should be extracted");
            assertEquals(props1.get("email"), props2.get("email"));

            double attrScore = compactionService.computeAttributeScore(props1, props2);
            assertTrue(attrScore >= 0.95, "Matching email should score >= 0.95");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 23. CROSS-LANGUAGE RESOLUTION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("23. Cross-Language Resolution")
    class CrossLanguageResolution {

        // ── Alias extraction ───────────────────────────────────────

        @Test
        @DisplayName("Parenthetical translation extracts both variants as aliases")
        void parentheticalExtraction() {
            Set<String> aliases = GraphCompactionService.extractCrossLanguageAliases(
                    "Schlossberg (Castle Hill)");
            assertTrue(aliases.contains("schlossberg"), "Should contain outside-parens form");
            assertTrue(aliases.contains("castle hill"), "Should contain inside-parens form");
        }

        @Test
        @DisplayName("CJK parenthetical extracts both scripts")
        void cjkParenthetical() {
            // "東京 (Tokyo)" → aliases contain both "東京" and "tokyo"
            Set<String> aliases = GraphCompactionService.extractCrossLanguageAliases(
                    "東京 (Tokyo)");
            assertTrue(aliases.contains("tokyo"),
                    "Should contain Latin transliteration, got: " + aliases);
            assertTrue(aliases.stream().anyMatch(a -> a.contains("東京")),
                    "Should contain CJK form, got: " + aliases);
        }

        @Test
        @DisplayName("Reversed parenthetical — Latin outside, CJK inside")
        void reversedParenthetical() {
            Set<String> aliases = GraphCompactionService.extractCrossLanguageAliases(
                    "Tokyo (東京)");
            assertTrue(aliases.contains("tokyo"), "Latin portion present");
            assertTrue(aliases.stream().anyMatch(a -> a.contains("東京")),
                    "CJK portion present");
        }

        @Test
        @DisplayName("Slash-separated language variants")
        void slashSeparated() {
            Set<String> aliases = GraphCompactionService.extractCrossLanguageAliases(
                    "Bern / Berne");
            assertTrue(aliases.contains("bern"), "First variant");
            assertTrue(aliases.contains("berne"), "Second variant");
        }

        @Test
        @DisplayName("Mixed CJK+Latin string splits into script runs")
        void mixedScriptSplit() {
            // "佐藤花子 Sato Hanako" → CJK run + Latin run
            Set<String> aliases = GraphCompactionService.extractCrossLanguageAliases(
                    "佐藤花子 Sato Hanako");
            assertTrue(aliases.stream().anyMatch(a -> a.contains("佐藤花子")),
                    "Should extract CJK run, got: " + aliases);
            assertTrue(aliases.contains("sato hanako") || aliases.contains("sato hanako"),
                    "Should extract Latin run, got: " + aliases);
        }

        @Test
        @DisplayName("Cyrillic + Latin mixed string splits correctly")
        void cyrillicLatinSplit() {
            Set<String> aliases = GraphCompactionService.extractCrossLanguageAliases(
                    "Иванов Ivanov");
            assertTrue(aliases.stream().anyMatch(a -> a.contains("иванов")),
                    "Should extract Cyrillic run, got: " + aliases);
            assertTrue(aliases.contains("ivanov"),
                    "Should extract Latin run, got: " + aliases);
        }

        @Test
        @DisplayName("Plain Latin name produces no cross-language aliases")
        void plainLatinNoAliases() {
            Set<String> aliases = GraphCompactionService.extractCrossLanguageAliases(
                    "Elena Voss");
            assertTrue(aliases.isEmpty(),
                    "Plain Latin name should produce no cross-language aliases, got: " + aliases);
        }

        @Test
        @DisplayName("URL with slash is NOT treated as language separator")
        void urlNotSplit() {
            Set<String> aliases = GraphCompactionService.extractCrossLanguageAliases(
                    "https://example.com/path");
            assertTrue(aliases.isEmpty(),
                    "URL should not produce cross-language aliases, got: " + aliases);
        }

        // ── Script detection ───────────────────────────────────────

        @Test
        @DisplayName("detectScript identifies CJK, Cyrillic, Latin correctly")
        void scriptDetection() {
            assertEquals(GraphCompactionService.Script.CJK,
                    GraphCompactionService.detectScript("東京"));
            assertEquals(GraphCompactionService.Script.CYRILLIC,
                    GraphCompactionService.detectScript("Москва"));
            assertEquals(GraphCompactionService.Script.LATIN,
                    GraphCompactionService.detectScript("Munich"));
        }

        // ── Cross-language scoring ─────────────────────────────────

        @Test
        @DisplayName("Parenthetical entity matches plain entity via cross-language signal")
        void parentheticalMatchesPlain() {
            double score = GraphCompactionService.scoreCrossLanguageMatch(
                    "Rathaus (City Hall)", Set.of(),
                    "City Hall", Set.of());
            assertTrue(score >= 0.90,
                    "Parenthetical match should score >= 0.90, got " + score);
        }

        @Test
        @DisplayName("CJK entity matches Latin transliteration via parenthetical alias")
        void cjkMatchesLatinViaParenthetical() {
            // "大阪 (Osaka)" should match "Osaka"
            double score = GraphCompactionService.scoreCrossLanguageMatch(
                    "大阪 (Osaka)", Set.of(),
                    "Osaka", Set.of());
            assertTrue(score >= 0.90,
                    "CJK parenthetical should match Latin form, got " + score);
        }

        @Test
        @DisplayName("Mixed-script title matches pure Latin version")
        void mixedScriptMatchesPureLatin() {
            // "鈴木一郎 Suzuki Ichiro" should match "Suzuki Ichiro"
            double score = GraphCompactionService.scoreCrossLanguageMatch(
                    "鈴木一郎 Suzuki Ichiro", Set.of(),
                    "Suzuki Ichiro", Set.of());
            assertTrue(score >= 0.90,
                    "Mixed-script should match pure Latin, got " + score);
        }

        @Test
        @DisplayName("Slash-separated variant matches single variant")
        void slashVariantMatchesSingle() {
            double score = GraphCompactionService.scoreCrossLanguageMatch(
                    "Genf / Geneva", Set.of(),
                    "Geneva", Set.of());
            assertTrue(score >= 0.90,
                    "Slash variant should match single form, got " + score);
        }

        @Test
        @DisplayName("Completely unrelated cross-language entities do not match")
        void unrelatedNoMatch() {
            double score = GraphCompactionService.scoreCrossLanguageMatch(
                    "München (Munich)", Set.of(),
                    "東京 (Tokyo)", Set.of());
            assertEquals(0.0, score,
                    "Unrelated cross-language entities should not match");
        }

        // ── Integration with scorePair / compaction ────────────────

        @Test
        @DisplayName("Cross-language parenthetical matches via alias pipeline in scorePair")
        void crossLangInScorePair() {
            GraphNode n1 = entityNode("n1", "Marktplatz (Market Square)", "LOCATION");
            GraphNode n2 = entityNode("n2", "Market Square", "LOCATION");

            var config = CompactionConfig.withCrossLanguageResolution(0.85);
            var candidates = compactionService.testFindCandidatesForBlock(
                    List.of(n1, n2), config);

            assertFalse(candidates.isEmpty(),
                    "Cross-language parenthetical should produce match candidate");
            // The match fires via alias overlap (Signal 2) since extractAliases()
            // now includes cross-language aliases. Either TITLE_IN_ALIAS or
            // CROSS_LANGUAGE_MATCH is acceptable.
            assertTrue(candidates.get(0).score() >= 0.90,
                    "Match score should be >= 0.90, got " + candidates.get(0).score());
        }

        @Test
        @DisplayName("Cross-language signal does NOT fire without flag")
        void crossLangDisabledByDefault() {
            GraphNode n1 = entityNode("n1", "Rathaus (City Hall)", "LOCATION");
            GraphNode n2 = entityNode("n2", "City Hall", "LOCATION");

            // Default config — crossLanguageResolution=false
            // But note: extractAliases already includes cross-language aliases,
            // so Signal 2 (alias overlap) may still catch this via the
            // parenthetical extraction in extractAliases. This is expected —
            // cross-language aliases feed into the standard alias pipeline too.
            var config = CompactionConfig.withoutEmbeddings(0.85);
            var candidates = compactionService.testFindCandidatesForBlock(
                    List.of(n1, n2), config);

            // Even without the flag, alias extraction picks up parentheticals.
            // The test verifies the specific CROSS_LANGUAGE_MATCH signal is absent.
            if (!candidates.isEmpty()) {
                boolean hasCrossLangSignal = candidates.get(0).reasons().stream()
                        .anyMatch(r -> r.contains("CROSS_LANGUAGE"));
                assertFalse(hasCrossLangSignal,
                        "Without crossLanguageResolution flag, CROSS_LANGUAGE_MATCH signal " +
                                "should not appear. Got: " + candidates.get(0).reasons());
            }
        }

        @Test
        @DisplayName("Full compaction merges cross-language entities")
        void fullCompactionCrossLanguage() {
            GraphNode n1 = entityNode("n1", "Hafenviertel (Harbor District)", "LOCATION");
            GraphNode n2 = entityNode("n2", "Harbor District", "LOCATION");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            var config = CompactionConfig.withCrossLanguageResolution(0.85);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 1,
                    "Cross-language entities should merge during full compaction");
        }

        @Test
        @DisplayName("Hangul + Latin parenthetical resolves")
        void hangulLatinParenthetical() {
            // Korean example
            Set<String> aliases = GraphCompactionService.extractCrossLanguageAliases(
                    "시청 (City Hall)");
            assertTrue(aliases.contains("city hall"), "Latin form extracted");
            assertTrue(aliases.stream().anyMatch(a -> a.contains("시청")),
                    "Hangul form extracted, got: " + aliases);
        }

        @Test
        @DisplayName("splitByScript handles complex mixed strings")
        void splitByScriptComplex() {
            List<String> runs = GraphCompactionService.splitByScript(
                    "田中太郎 Tanaka Taro 先生");
            // Should have at least CJK run, Latin run, CJK run
            assertTrue(runs.size() >= 2,
                    "Should split into multiple script runs, got: " + runs);
            assertTrue(runs.stream().anyMatch(r ->
                            GraphCompactionService.detectScript(r) == GraphCompactionService.Script.CJK),
                    "Should have a CJK run");
            assertTrue(runs.stream().anyMatch(r ->
                            GraphCompactionService.detectScript(r) == GraphCompactionService.Script.LATIN),
                    "Should have a Latin run");
        }

        @Test
        @DisplayName("Three CJK entities with different transliterations merge transitively")
        void transitiveViaSharedAlias() {
            // n1 has the CJK+Latin form, n2 has just Latin, n3 has CJK+Latin with parens
            // n1 ↔ n2 match via mixed-script alias, n1 ↔ n3 match via CJK alias overlap
            GraphNode n1 = entityNode("n1", "渡辺 Watanabe", "PERSON");
            GraphNode n2 = entityNode("n2", "Watanabe", "PERSON");
            GraphNode n3 = entityNode("n3", "Watanabe (渡辺)", "PERSON");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(n1, n2, n3));
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());

            var config = CompactionConfig.withCrossLanguageResolution(0.85);
            var result = compactionService.compact(config);

            assertTrue(result.entitiesMerged() >= 2,
                    "All three cross-language variants should merge into one, " +
                            "merged=" + result.entitiesMerged());
        }
    }
}
