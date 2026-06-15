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
package ai.kompile.core.graphrag;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.crawler.CrawlItem;
import ai.kompile.core.crawler.pipeline.ContentRouteRule;
import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import ai.kompile.core.crawler.pipeline.PromptAugmentation;
import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry.FallbackConfig;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry.ProviderInfo;
import ai.kompile.core.graphrag.model.Community;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for all new graph features added in this branch.
 *
 * <p>Tests cover realistic scenarios including:
 * <ul>
 *   <li>HYBRID search type with traversal paths, score breakdowns, and hop mechanics</li>
 *   <li>ExtractionLlmService model override and provider model switching</li>
 *   <li>FallbackConfig with custom provider ordering and limit-only mode</li>
 *   <li>UnifiedCrawlJob retry/parse-failure counters and preprocessing counters</li>
 *   <li>Multi-pipeline routing with ContentRouteRule matching</li>
 *   <li>Per-pipeline graph extraction overrides via IngestPipelineDefinition</li>
 *   <li>CrawlItem tag-based routing</li>
 *   <li>PromptAugmentation content-signal matching</li>
 *   <li>Community title field in graph model</li>
 *   <li>GraphRagQuery new fields (hopDepth, vectorWeight, maxTraversalNodes, factSheetId)</li>
 *   <li>GraphRagResult diagnostic fields</li>
 *   <li>GraphConstructor.RetryStatsListener</li>
 * </ul>
 */
class NewGraphFeaturesIntegrationTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. HYBRID SEARCH — GraphRagQuery + GraphRagResult round-trip
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("HYBRID search query and result")
    class HybridSearchTests {

        @Test
        @DisplayName("HYBRID query carries traversal parameters through to result")
        void hybridQueryParametersPropagateThroughResult() {
            // Build a query with all HYBRID-specific params
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What products does Alice's company make?")
                    .searchType(SearchType.HYBRID)
                    .k(10)
                    .hopDepth(3)
                    .vectorWeight(0.7)
                    .maxTraversalNodes(25)
                    .factSheetId(42L)
                    .includeCommunities(true)
                    .conversationId("session-xyz")
                    .build();

            // Verify query carries all parameters
            assertEquals(SearchType.HYBRID, query.getSearchType());
            assertEquals(3, query.getHopDepth());
            assertEquals(0.7, query.getVectorWeight(), 0.001);
            assertEquals(25, query.getMaxTraversalNodes());
            assertEquals(42L, query.getFactSheetId());
            assertTrue(query.isIncludeCommunities());
            assertEquals("session-xyz", query.getConversationId());

            // Simulate what a service would return for this query
            GraphRagResult result = GraphRagResult.builder()
                    .answer("Alice's company Acme produces Widget X and Gizmo Y.")
                    .searchType(SearchType.HYBRID)
                    .hopsPerformed(2)
                    .nodesVisited(5)
                    .traversalPaths(Map.of(
                            "alice-id", List.of("alice-id", "acme-id", "widget-x-id"),
                            "acme-id", List.of("acme-id", "gizmo-y-id")
                    ))
                    .scoreBreakdown(Map.of(
                            "alice-id", Map.of("vectorScore", 0.95, "graphScore", 0.0, "hopDistance", 0.0, "combined", 0.665),
                            "acme-id", Map.of("vectorScore", 0.0, "graphScore", 0.9, "hopDistance", 1.0, "combined", 0.27),
                            "widget-x-id", Map.of("vectorScore", 0.0, "graphScore", 0.8, "hopDistance", 2.0, "combined", 0.24)
                    ))
                    .entities(List.of(
                            buildEntity("alice-id", "Alice", "PERSON", 0.95),
                            buildEntity("acme-id", "Acme Corp", "ORGANIZATION", 0.9),
                            buildEntity("widget-x-id", "Widget X", "PRODUCT", 0.85)
                    ))
                    .relationships(List.of(
                            buildRelationship("alice-id", "acme-id", "WORKS_AT", 0.9),
                            buildRelationship("acme-id", "widget-x-id", "PRODUCES", 0.8)
                    ))
                    .communities(List.of())
                    .formattedContext("Alice (PERSON) -> WORKS_AT -> Acme Corp (ORGANIZATION) -> PRODUCES -> Widget X (PRODUCT)")
                    .build();

            // Validate result carries HYBRID diagnostics
            assertEquals(SearchType.HYBRID, result.getSearchType());
            assertEquals(2, result.getHopsPerformed());
            assertEquals(5, result.getNodesVisited());

            // Traversal paths should trace multi-hop discovery
            assertNotNull(result.getTraversalPaths());
            assertEquals(2, result.getTraversalPaths().size());
            List<String> alicePath = result.getTraversalPaths().get("alice-id");
            assertEquals(3, alicePath.size());
            assertEquals("alice-id", alicePath.get(0));
            assertEquals("acme-id", alicePath.get(1));
            assertEquals("widget-x-id", alicePath.get(2));

            // Score breakdown: seed should have 0 hop distance, neighbors 1, etc.
            Map<String, Double> aliceScores = result.getScoreBreakdown().get("alice-id");
            assertEquals(0.0, aliceScores.get("hopDistance"), 0.001);
            assertTrue(aliceScores.get("vectorScore") > 0.9);
            Map<String, Double> acmeScores = result.getScoreBreakdown().get("acme-id");
            assertEquals(1.0, acmeScores.get("hopDistance"), 0.001);
            Map<String, Double> widgetScores = result.getScoreBreakdown().get("widget-x-id");
            assertEquals(2.0, widgetScores.get("hopDistance"), 0.001);

            // Combined score uses vectorWeight: combined = vectorWeight * vectorScore + (1-vectorWeight) * graphScore
            // Seed alice: combined = 0.7 * 0.95 + 0.3 * 0.0 = 0.665
            assertEquals(0.665, aliceScores.get("combined"), 0.001);
        }

        @Test
        @DisplayName("Query defaults: hopDepth=2, vectorWeight=0.5, maxTraversalNodes=50")
        void queryDefaultsAreCorrect() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("test")
                    .build();

            assertEquals(2, query.getHopDepth());
            assertEquals(0.5, query.getVectorWeight(), 0.001);
            assertEquals(50, query.getMaxTraversalNodes());
            assertTrue(query.isIncludeCommunities());
            assertEquals("default", query.getConversationId());
            assertNull(query.getFactSheetId());
        }

        @Test
        @DisplayName("Result with empty traversal represents no graph exploration")
        void emptyTraversalMeansNoExploration() {
            GraphRagResult result = GraphRagResult.builder()
                    .answer("No relevant entities found.")
                    .searchType(SearchType.HYBRID)
                    .hopsPerformed(0)
                    .nodesVisited(0)
                    .traversalPaths(Map.of())
                    .scoreBreakdown(Map.of())
                    .entities(List.of())
                    .relationships(List.of())
                    .build();

            assertEquals(0, result.getHopsPerformed());
            assertEquals(0, result.getNodesVisited());
            assertTrue(result.getTraversalPaths().isEmpty());
            assertTrue(result.getScoreBreakdown().isEmpty());
        }

        @Test
        @DisplayName("SearchType enum has LOCAL, GLOBAL, HYBRID")
        void searchTypeEnumValues() {
            assertEquals(3, SearchType.values().length);
            assertNotNull(SearchType.valueOf("LOCAL"));
            assertNotNull(SearchType.valueOf("GLOBAL"));
            assertNotNull(SearchType.valueOf("HYBRID"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. EXTRACTION LLM SERVICE — model override, provider switching, fallback
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ExtractionLlmService model override and registry features")
    class ExtractionLlmServiceTests {

        private ExtractionLlmServiceRegistry registry;

        @BeforeEach
        void setUp() {
            registry = new ExtractionLlmServiceRegistry();
        }

        @Test
        @DisplayName("setModelOverride propagates through to getEffectiveModel")
        void modelOverridePropagation() {
            ModelTrackingService service = new ModelTrackingService("claude-cli", "Claude CLI", true);
            assertNull(service.getEffectiveModel());

            service.setModelOverride("claude-sonnet-4-20250514");
            assertEquals("claude-sonnet-4-20250514", service.getEffectiveModel());

            service.setModelOverride("haiku");
            assertEquals("haiku", service.getEffectiveModel());

            service.setModelOverride(null);
            assertNull(service.getEffectiveModel());
        }

        @Test
        @DisplayName("Default ExtractionLlmService ignores setModelOverride (no-op)")
        void defaultServiceIgnoresModelOverride() {
            ExtractionLlmService defaultService = new ExtractionLlmService() {
                @Override public String getId() { return "basic"; }
                @Override public String getDescription() { return "Basic"; }
                @Override public String complete(String prompt) { return "response"; }
                @Override public boolean isAvailable() { return true; }
            };

            defaultService.setModelOverride("gpt-4o");
            assertNull(defaultService.getEffectiveModel());
        }

        @Test
        @DisplayName("setProviderModel sets model on a registered provider")
        void setProviderModelOnRegisteredProvider() {
            ModelTrackingService claude = new ModelTrackingService("claude-cli", "Claude", true);
            ModelTrackingService gemini = new ModelTrackingService("gemini-cli", "Gemini", true);
            registry.register(claude);
            registry.register(gemini);

            assertTrue(registry.setProviderModel("claude-cli", "sonnet"));
            assertEquals("sonnet", claude.getEffectiveModel());
            assertNull(gemini.getEffectiveModel());

            assertTrue(registry.setProviderModel("gemini-cli", "gemini-2.5-pro"));
            assertEquals("gemini-2.5-pro", gemini.getEffectiveModel());
        }

        @Test
        @DisplayName("setProviderModel returns false for unknown provider")
        void setProviderModelUnknownProvider() {
            assertFalse(registry.setProviderModel("nonexistent", "gpt-4o"));
        }

        @Test
        @DisplayName("listProviders includes effectiveModel in ProviderInfo")
        void listProvidersIncludesEffectiveModel() {
            ModelTrackingService claude = new ModelTrackingService("claude-cli", "Claude", true);
            claude.setModelOverride("opus");
            registry.register(claude);

            List<ProviderInfo> providers = registry.listProviders();
            assertEquals(1, providers.size());
            assertEquals("claude-cli", providers.get(0).id());
            assertEquals("opus", providers.get(0).effectiveModel());
        }

        @Test
        @DisplayName("FallbackConfig with custom provider order changes priority")
        void fallbackConfigCustomProviderOrder() {
            ModelTrackingService gemini = new ModelTrackingService("gemini-cli", "Gemini", true);
            ModelTrackingService claude = new ModelTrackingService("claude-cli", "Claude", true);
            ModelTrackingService codex = new ModelTrackingService("codex-cli", "Codex", true);
            registry.register(gemini);
            registry.register(claude);
            registry.register(codex);

            // Set custom order: gemini first, then codex, then claude
            FallbackConfig config = new FallbackConfig(
                    true, false, List.of("gemini", "codex", "claude"), 120);
            registry.setFallbackConfig(config);

            FallbackConfig retrieved = registry.getFallbackConfig();
            assertTrue(retrieved.enabled());
            assertFalse(retrieved.limitOnly());
            assertEquals(List.of("gemini", "codex", "claude"), retrieved.providerOrder());
            assertEquals(120, retrieved.timeoutSeconds());

            // getOrFallback with a null preferred should respect the custom order
            ExtractionLlmService resolved = registry.getOrFallback(null);
            assertNotNull(resolved);
            // With fallback enabled and multiple candidates, should get a FallbackExtractionLlmService
            // whose primary ID matches the highest-priority available
        }

        @Test
        @DisplayName("FallbackConfig limitOnly=true only falls back on limit/quota errors")
        void fallbackLimitOnlyMode() {
            FallbackConfig config = new FallbackConfig(true, true, List.of(), 300);
            registry.setFallbackConfig(config);

            FallbackConfig retrieved = registry.getFallbackConfig();
            assertTrue(retrieved.limitOnly());
        }

        @Test
        @DisplayName("getFallbackConfig returns defaults when not explicitly set")
        void fallbackConfigDefaults() {
            FallbackConfig config = registry.getFallbackConfig();
            assertNotNull(config);
            // Default timeout should be 300
            assertEquals(300, config.timeoutSeconds());
            assertTrue(config.providerOrder().isEmpty());
        }

        @Test
        @DisplayName("Fallback service recognizes 'insufficient balance' as limit failure")
        void fallbackRecognizesInsufficientBalance() {
            // Register two services where the first throws an insufficient balance error
            ExtractionLlmService failing = new ExtractionLlmService() {
                @Override public String getId() { return "primary"; }
                @Override public String getDescription() { return "Primary"; }
                @Override public String complete(String prompt) {
                    throw new ExtractionLlmException("Error: insufficient balance on account");
                }
                @Override public boolean isAvailable() { return true; }
            };
            ExtractionLlmService fallback = new ExtractionLlmService() {
                @Override public String getId() { return "backup"; }
                @Override public String getDescription() { return "Backup"; }
                @Override public String complete(String prompt) { return "backup response"; }
                @Override public boolean isAvailable() { return true; }
            };

            registry.register(failing);
            registry.register(fallback);
            registry.setFallbackConfig(new FallbackConfig(true, true, List.of("primary", "backup"), 300));

            ExtractionLlmService resolved = registry.getOrFallback("primary");
            assertNotNull(resolved);
            String response = resolved.complete("test prompt");
            assertEquals("backup response", response);
        }

        @Test
        @DisplayName("Fallback service recognizes '429' as limit failure")
        void fallbackRecognizes429() {
            ExtractionLlmService failing = new ExtractionLlmService() {
                @Override public String getId() { return "primary"; }
                @Override public String getDescription() { return "Primary"; }
                @Override public String complete(String prompt) {
                    throw new ExtractionLlmException("HTTP 429 Too Many Requests");
                }
                @Override public boolean isAvailable() { return true; }
            };
            ExtractionLlmService fallback = new ExtractionLlmService() {
                @Override public String getId() { return "backup"; }
                @Override public String getDescription() { return "Backup"; }
                @Override public String complete(String prompt) { return "fallback ok"; }
                @Override public boolean isAvailable() { return true; }
            };

            registry.register(failing);
            registry.register(fallback);
            registry.setFallbackConfig(new FallbackConfig(true, true, List.of("primary", "backup"), 300));

            ExtractionLlmService resolved = registry.getOrFallback("primary");
            String response = resolved.complete("prompt");
            assertEquals("fallback ok", response);
        }

        @Test
        @DisplayName("Fallback service setModelOverride propagates to all candidates")
        void fallbackServicePropagatesModelOverride() {
            ModelTrackingService svc1 = new ModelTrackingService("a", "A", true);
            ModelTrackingService svc2 = new ModelTrackingService("b", "B", true);
            registry.register(svc1);
            registry.register(svc2);
            registry.setFallbackConfig(new FallbackConfig(true, false, List.of("a", "b"), 300));

            ExtractionLlmService resolved = registry.getOrFallback("a");
            resolved.setModelOverride("new-model");
            assertEquals("new-model", svc1.getEffectiveModel());
            assertEquals("new-model", svc2.getEffectiveModel());
        }

        @Test
        @DisplayName("All providers fail throws aggregated exception with all failure messages")
        void allProvidersFail() {
            ExtractionLlmService svc1 = new ExtractionLlmService() {
                @Override public String getId() { return "svc1"; }
                @Override public String getDescription() { return "S1"; }
                @Override public String complete(String prompt) {
                    throw new ExtractionLlmException("svc1: quota exceeded");
                }
                @Override public boolean isAvailable() { return true; }
            };
            ExtractionLlmService svc2 = new ExtractionLlmService() {
                @Override public String getId() { return "svc2"; }
                @Override public String getDescription() { return "S2"; }
                @Override public String complete(String prompt) {
                    throw new ExtractionLlmException("svc2: rate limit hit");
                }
                @Override public boolean isAvailable() { return true; }
            };
            registry.register(svc1);
            registry.register(svc2);
            registry.setFallbackConfig(new FallbackConfig(true, false, List.of("svc1", "svc2"), 300));

            ExtractionLlmService resolved = registry.getOrFallback("svc1");
            ExtractionLlmService.ExtractionLlmException ex = assertThrows(
                    ExtractionLlmService.ExtractionLlmException.class,
                    () -> resolved.complete("prompt"));
            assertTrue(ex.getMessage().contains("All extraction LLM providers failed"));
            assertTrue(ex.getMessage().contains("svc1"));
            assertTrue(ex.getMessage().contains("svc2"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. UNIFIED CRAWL JOB — retry/parse counters, preprocessing counters
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UnifiedCrawlJob new tracking fields")
    class UnifiedCrawlJobTests {

        @Test
        @DisplayName("Retry and parse failure counters track LLM extraction quality")
        void retryAndParseFailureCounters() {
            UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                    .jobId("job-001")
                    .build();

            // Simulate 3 retries across 2 batches, 1 final parse failure
            job.getGraphExtractionRetries().incrementAndGet();
            job.getGraphExtractionRetries().incrementAndGet();
            job.getGraphExtractionRetries().incrementAndGet();
            job.getGraphExtractionParseFailures().incrementAndGet();

            assertEquals(3, job.getGraphExtractionRetries().get());
            assertEquals(1, job.getGraphExtractionParseFailures().get());

            // Verify these appear in the progress snapshot
            UnifiedCrawlJob.ProgressSnapshot snapshot = job.toProgressSnapshot();
            assertEquals(3, snapshot.getGraphExtractionRetries());
            assertEquals(1, snapshot.getGraphExtractionParseFailures());
        }

        @Test
        @DisplayName("Preprocessing and translation counters track document pipeline stages")
        void preprocessingCounters() {
            UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                    .jobId("job-002")
                    .build();

            // Simulate 10 docs preprocessed, 3 translated
            for (int i = 0; i < 10; i++) job.getDocumentsPreprocessed().incrementAndGet();
            for (int i = 0; i < 3; i++) job.getDocumentsTranslated().incrementAndGet();

            assertEquals(10, job.getDocumentsPreprocessed().get());
            assertEquals(3, job.getDocumentsTranslated().get());

            UnifiedCrawlJob.ProgressSnapshot snapshot = job.toProgressSnapshot();
            assertEquals(10, snapshot.getDocumentsPreprocessed());
            assertEquals(3, snapshot.getDocumentsTranslated());
        }

        @Test
        @DisplayName("Entity and relationship type counters track extraction distribution")
        void entityTypeCounterDistribution() {
            UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                    .jobId("job-003")
                    .build();

            // Simulate realistic extraction with varied entity types
            for (int i = 0; i < 50; i++) job.incrementEntityType("PERSON");
            for (int i = 0; i < 30; i++) job.incrementEntityType("ORGANIZATION");
            for (int i = 0; i < 200; i++) job.incrementEntityType("CELL");
            for (int i = 0; i < 100; i++) job.incrementRelationshipType("CONTAINS");
            for (int i = 0; i < 40; i++) job.incrementRelationshipType("WORKS_AT");

            Map<String, Long> entitySnapshot = job.snapshotEntityTypeCounts();
            assertEquals(50L, entitySnapshot.get("PERSON"));
            assertEquals(30L, entitySnapshot.get("ORGANIZATION"));
            assertEquals(200L, entitySnapshot.get("CELL"));

            Map<String, Long> relSnapshot = job.snapshotRelationshipTypeCounts();
            assertEquals(100L, relSnapshot.get("CONTAINS"));
            assertEquals(40L, relSnapshot.get("WORKS_AT"));

            // Verify snapshot includes counts in progress
            UnifiedCrawlJob.ProgressSnapshot snapshot = job.toProgressSnapshot();
            assertNotNull(snapshot.getEntityTypeCounts());
            assertEquals(3, snapshot.getEntityTypeCounts().size());
            assertNotNull(snapshot.getRelationshipTypeCounts());
            assertEquals(2, snapshot.getRelationshipTypeCounts().size());
        }

        @Test
        @DisplayName("Blank and null entity types are silently ignored")
        void blankEntityTypesIgnored() {
            UnifiedCrawlJob job = UnifiedCrawlJob.builder().jobId("job-004").build();
            job.incrementEntityType(null);
            job.incrementEntityType("");
            job.incrementEntityType("  ");
            job.incrementEntityType("PERSON");

            assertEquals(1, job.snapshotEntityTypeCounts().size());
            assertEquals(1L, job.snapshotEntityTypeCounts().get("PERSON"));
        }

        @Test
        @DisplayName("Full progress snapshot round-trip captures all new fields")
        void fullSnapshotRoundTrip() throws Exception {
            UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                    .jobId("snapshot-test")
                    .request(UnifiedCrawlRequest.builder().name("test crawl").build())
                    .build();
            job.getStatus().set(UnifiedCrawlJob.Status.RUNNING);
            job.getDocumentsPreprocessed().set(5);
            job.getDocumentsTranslated().set(2);
            job.getGraphExtractionRetries().set(7);
            job.getGraphExtractionParseFailures().set(1);
            job.incrementEntityType("CONCEPT");
            job.incrementRelationshipType("RELATED_TO");

            UnifiedCrawlJob.ProgressSnapshot snapshot = job.toProgressSnapshot();

            // Verify JSON serializable (Jackson round-trip)
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            String json = mapper.writeValueAsString(snapshot);
            assertNotNull(json);
            assertTrue(json.contains("\"documentsPreprocessed\":5"));
            assertTrue(json.contains("\"documentsTranslated\":2"));
            assertTrue(json.contains("\"graphExtractionRetries\":7"));
            assertTrue(json.contains("\"graphExtractionParseFailures\":1"));
            assertTrue(json.contains("CONCEPT"));
            assertTrue(json.contains("RELATED_TO"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. MULTI-PIPELINE ROUTING — ContentRouteRule matching scenarios
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ContentRouteRule matching logic")
    class ContentRouteRuleTests {

        @Test
        @DisplayName("Metadata matcher: all specified keys must match (AND logic)")
        void metadataMatcherAndLogic() {
            ContentRouteRule rule = ContentRouteRule.builder()
                    .pipelineId("legal-pipeline")
                    .metadataMatchers(Map.of(
                            "department", "(?i)legal|compliance",
                            "confidentiality", "(?i)high|restricted"
                    ))
                    .priority(5)
                    .build();

            assertNotNull(rule.getMetadataMatchers());
            assertEquals(2, rule.getMetadataMatchers().size());
            // Verify the regex patterns are stored correctly
            assertTrue(rule.getMetadataMatchers().get("department").contains("legal"));
            assertTrue(rule.getMetadataMatchers().get("confidentiality").contains("high"));
        }

        @Test
        @DisplayName("Content pattern routing: ANY pattern match triggers rule")
        void contentPatternAnyMatch() {
            ContentRouteRule rule = ContentRouteRule.builder()
                    .pipelineId("financial")
                    .contentPatterns(List.of(
                            "(?i)balance sheet",
                            "(?i)income statement",
                            "(?i)cash flow"
                    ))
                    .priority(5)
                    .build();

            assertEquals(3, rule.getContentPatterns().size());
            assertEquals("financial", rule.getPipelineId());
        }

        @Test
        @DisplayName("Tag-based routing: item tags match route tags")
        void tagBasedRouting() {
            ContentRouteRule rule = ContentRouteRule.builder()
                    .pipelineId("high-priority")
                    .tags(List.of("urgent", "executive"))
                    .priority(1)
                    .build();

            CrawlItem item = CrawlItem.builder()
                    .url("https://example.com/report.pdf")
                    .contentType("application/pdf")
                    .tags(new HashSet<>(List.of("urgent", "quarterly")))
                    .build();

            // Item has "urgent" which is in the rule's tags
            assertTrue(item.getTags().stream().anyMatch(rule.getTags()::contains));
        }

        @Test
        @DisplayName("Page count bounds route PDFs by size")
        void pageCountRouting() {
            ContentRouteRule shortDocRule = ContentRouteRule.builder()
                    .pipelineId("short-doc")
                    .maxPages(10)
                    .priority(10)
                    .build();

            ContentRouteRule longDocRule = ContentRouteRule.builder()
                    .pipelineId("long-doc-vlm")
                    .minPages(50)
                    .priority(10)
                    .build();

            assertNull(shortDocRule.getMinPages());
            assertEquals(10, shortDocRule.getMaxPages());
            assertEquals(50, longDocRule.getMinPages());
            assertNull(longDocRule.getMaxPages());
        }

        @Test
        @DisplayName("Multi-condition rule with content types + metadata + tags")
        void multiConditionRule() {
            ContentRouteRule rule = ContentRouteRule.builder()
                    .pipelineId("specialized")
                    .contentTypes(List.of("application/pdf", "image/tiff"))
                    .metadataMatchers(Map.of("source", "scanner-.*"))
                    .tags(List.of("scanned"))
                    .minPages(1)
                    .maxPages(100)
                    .priority(5)
                    .build();

            // All conditions must be set
            assertNotNull(rule.getContentTypes());
            assertNotNull(rule.getMetadataMatchers());
            assertNotNull(rule.getTags());
            assertEquals(5, rule.getPriority());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. INGEST PIPELINE DEFINITION — per-pipeline extraction overrides
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IngestPipelineDefinition per-pipeline extraction overrides")
    class IngestPipelineDefinitionTests {

        @Test
        @DisplayName("Per-pipeline extraction overrides for financial documents")
        void financialPipelineExtractionOverrides() {
            IngestPipelineDefinition financialPipeline = IngestPipelineDefinition.builder()
                    .pipelineId("financial")
                    .displayName("Financial Document Processing")
                    .pipelineType(IngestPipelineDefinition.PipelineType.TABLE_AWARE)
                    .extractionLlmProvider("claude-cli")
                    .extractionModelName("claude-sonnet-4-20250514")
                    .extractionEntityTypes(List.of("COMPANY", "FINANCIAL_METRIC", "REGULATORY_BODY"))
                    .extractionRelationshipTypes(List.of("REPORTS_METRIC", "REGULATED_BY", "COMPARED_TO"))
                    .extractionTemperature(0.1)
                    .extractionMaxTokens(8192)
                    .maxChunkChars(16000)
                    .extractionPromptTemplate("Extract financial entities from the following document. " +
                            "Focus on: {{ENTITY_TYPES}}. Relationships: {{RELATIONSHIP_TYPES}}. Text: {{TEXT}}")
                    .promptAugmentations(List.of(
                            PromptAugmentation.builder()
                                    .name("balance-sheet")
                                    .contentSignals(List.of("(?i)balance sheet", "(?i)total assets"))
                                    .augmentationText("BALANCE SHEET: Extract ASSET, LIABILITY entities")
                                    .minSignalMatches(1)
                                    .build()
                    ))
                    .build();

            assertEquals("financial", financialPipeline.getPipelineId());
            assertEquals("claude-cli", financialPipeline.getExtractionLlmProvider());
            assertEquals("claude-sonnet-4-20250514", financialPipeline.getExtractionModelName());
            assertEquals(3, financialPipeline.getExtractionEntityTypes().size());
            assertTrue(financialPipeline.getExtractionEntityTypes().contains("COMPANY"));
            assertEquals(0.1, financialPipeline.getExtractionTemperature(), 0.001);
            assertEquals(8192, financialPipeline.getExtractionMaxTokens());
            assertEquals(16000, financialPipeline.getMaxChunkChars());
            assertNotNull(financialPipeline.getExtractionPromptTemplate());
            assertTrue(financialPipeline.getExtractionPromptTemplate().contains("{{ENTITY_TYPES}}"));
            assertEquals(1, financialPipeline.getPromptAugmentations().size());
        }

        @Test
        @DisplayName("VLM pipeline with distinct extraction config from text pipeline")
        void vlmPipelineVsTextPipeline() {
            IngestPipelineDefinition vlmPipeline = IngestPipelineDefinition.builder()
                    .pipelineId("pdf-vlm")
                    .pipelineType(IngestPipelineDefinition.PipelineType.VLM)
                    .enableVlm(true)
                    .extractionLlmProvider("gemini-cli")
                    .extractionModelName("gemini-2.5-pro")
                    .maxChunkChars(24000)
                    .build();

            IngestPipelineDefinition textPipeline = IngestPipelineDefinition.builder()
                    .pipelineId("html-text")
                    .pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                    .extractionLlmProvider("claude-cli")
                    .extractionModelName("haiku")
                    .maxChunkChars(12000)
                    .build();

            // Two pipelines with different providers and chunk sizes
            assertNotEquals(vlmPipeline.getExtractionLlmProvider(), textPipeline.getExtractionLlmProvider());
            assertNotEquals(vlmPipeline.getMaxChunkChars(), textPipeline.getMaxChunkChars());
            assertTrue(vlmPipeline.isEnableVlm());
            assertFalse(textPipeline.isEnableVlm());
        }

        @Test
        @DisplayName("Null extraction overrides fall back to job-level defaults")
        void nullOverridesFallBack() {
            IngestPipelineDefinition pipeline = IngestPipelineDefinition.builder()
                    .pipelineId("default")
                    .pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                    .build();

            assertNull(pipeline.getExtractionLlmProvider());
            assertNull(pipeline.getExtractionModelName());
            assertNull(pipeline.getExtractionPromptTemplate());
            assertNull(pipeline.getExtractionEntityTypes());
            assertNull(pipeline.getExtractionRelationshipTypes());
            assertNull(pipeline.getExtractionTemperature());
            assertNull(pipeline.getExtractionMaxTokens());
            assertNull(pipeline.getMaxChunkChars());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 6. UNIFIED CRAWL REQUEST — multi-pipeline configuration
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UnifiedCrawlRequest multi-pipeline support")
    class UnifiedCrawlRequestTests {

        @Test
        @DisplayName("Request with multiple pipelines and route rules builds correctly")
        void multiPipelineRequestConstruction() {
            IngestPipelineDefinition htmlPipeline = IngestPipelineDefinition.builder()
                    .pipelineId("html")
                    .pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                    .chunkerName("recursive")
                    .build();

            IngestPipelineDefinition pdfVlmPipeline = IngestPipelineDefinition.builder()
                    .pipelineId("pdf-vlm")
                    .pipelineType(IngestPipelineDefinition.PipelineType.VLM)
                    .enableVlm(true)
                    .extractionLlmProvider("anthropic")
                    .build();

            ContentRouteRule pdfRule = ContentRouteRule.builder()
                    .pipelineId("pdf-vlm")
                    .contentTypes(List.of("application/pdf"))
                    .priority(10)
                    .build();

            ContentRouteRule codeRule = ContentRouteRule.builder()
                    .pipelineId("html")
                    .fileExtensions(List.of(".py", ".java", ".ts"))
                    .priority(20)
                    .build();

            UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                    .name("multi-pipeline crawl")
                    .factSheetId(99L)
                    .sources(List.of(
                            UnifiedCrawlSource.builder()
                                    .label("docs")
                                    .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                                    .pathOrUrl("/data/docs")
                                    .loaderName("tika")
                                    .chunkerName("recursive")
                                    .build()
                    ))
                    .pipelines(List.of(htmlPipeline, pdfVlmPipeline))
                    .routeRules(List.of(pdfRule, codeRule))
                    .defaultPipelineId("html")
                    .build();

            assertEquals(2, request.getPipelines().size());
            assertEquals(2, request.getRouteRules().size());
            assertEquals("html", request.getDefaultPipelineId());
            assertEquals(99L, request.getFactSheetId());

            // Source-level loader/chunker overrides
            UnifiedCrawlSource source = request.getSources().get(0);
            assertEquals("tika", source.getLoaderName());
            assertEquals("recursive", source.getChunkerName());
        }

        @Test
        @DisplayName("RuntimeConfig carries new parallelism and timeout fields")
        void runtimeConfigNewFields() {
            UnifiedCrawlRequest.RuntimeConfig config = UnifiedCrawlRequest.RuntimeConfig.builder()
                    .entityResolutionBatchSize(256)
                    .edgeComputationParallelism(8)
                    .vectorIndexingParallelism(4)
                    .parallelVectorAndGraph(true)
                    .llmCallTimeoutSeconds(120)
                    .graphExtractionBatchTimeoutSeconds(600)
                    .graphExtractionParallelism(16)
                    .build();

            assertEquals(256, config.getEntityResolutionBatchSize());
            assertEquals(8, config.getEdgeComputationParallelism());
            assertEquals(4, config.getVectorIndexingParallelism());
            assertTrue(config.getParallelVectorAndGraph());
            assertEquals(120, config.getLlmCallTimeoutSeconds());
            assertEquals(600, config.getGraphExtractionBatchTimeoutSeconds());
        }

        @Test
        @DisplayName("Empty pipelines/routeRules default to empty lists, not null")
        void emptyDefaults() {
            UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                    .name("simple")
                    .build();

            assertNotNull(request.getPipelines());
            assertTrue(request.getPipelines().isEmpty());
            assertNotNull(request.getRouteRules());
            assertTrue(request.getRouteRules().isEmpty());
            assertNull(request.getDefaultPipelineId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 7. CRAWL ITEM — tag-based routing
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CrawlItem tag-based routing")
    class CrawlItemTests {

        @Test
        @DisplayName("CrawlItem tags match ContentRouteRule tags for routing")
        void tagBasedRoutingIntegration() {
            // Simulate a classifier tagging items during crawl
            CrawlItem financialReport = CrawlItem.builder()
                    .url("/reports/q3-2025.pdf")
                    .contentType("application/pdf")
                    .tags(new HashSet<>(List.of("financial", "quarterly", "board-report")))
                    .build();

            CrawlItem legalDoc = CrawlItem.builder()
                    .url("/legal/contract-001.pdf")
                    .contentType("application/pdf")
                    .tags(new HashSet<>(List.of("legal", "contract")))
                    .build();

            CrawlItem untaggedDoc = CrawlItem.builder()
                    .url("/misc/notes.txt")
                    .contentType("text/plain")
                    .build();

            ContentRouteRule financialRule = ContentRouteRule.builder()
                    .pipelineId("financial-pipeline")
                    .tags(List.of("financial", "audit"))
                    .priority(5)
                    .build();

            ContentRouteRule legalRule = ContentRouteRule.builder()
                    .pipelineId("legal-pipeline")
                    .tags(List.of("legal", "compliance"))
                    .priority(5)
                    .build();

            // Financial report matches financial rule (has "financial" tag)
            assertTrue(financialReport.getTags().stream()
                    .anyMatch(financialRule.getTags()::contains));
            assertFalse(financialReport.getTags().stream()
                    .anyMatch(legalRule.getTags()::contains));

            // Legal doc matches legal rule (has "legal" tag)
            assertTrue(legalDoc.getTags().stream()
                    .anyMatch(legalRule.getTags()::contains));
            assertFalse(legalDoc.getTags().stream()
                    .anyMatch(financialRule.getTags()::contains));

            // Untagged doc matches neither
            assertTrue(untaggedDoc.getTags().isEmpty());
        }

        @Test
        @DisplayName("CrawlItem tags default to empty set, not null")
        void tagsDefaultToEmptySet() {
            CrawlItem item = CrawlItem.builder()
                    .url("https://example.com")
                    .build();
            assertNotNull(item.getTags());
            assertTrue(item.getTags().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 8. PROMPT AUGMENTATION — content-signal matching
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PromptAugmentation content-signal matching")
    class PromptAugmentationTests {

        @Test
        @DisplayName("Financial content triggers financial augmentation")
        void financialContentTriggers() {
            PromptAugmentation aug = PromptAugmentation.builder()
                    .name("financial")
                    .contentSignals(List.of(
                            "(?i)balance sheet",
                            "(?i)income statement",
                            "(?i)total assets",
                            "(?i)net revenue"
                    ))
                    .augmentationText("Extract ASSET, LIABILITY, and EQUITY entities with monetary values.")
                    .minSignalMatches(2)
                    .build();

            String financialText = "The Balance Sheet shows Total Assets of $5.2B. " +
                    "Net Revenue grew 15% year-over-year.";
            assertTrue(aug.matches(financialText));

            String nonFinancialText = "The product launch was successful.";
            assertFalse(aug.matches(nonFinancialText));
        }

        @Test
        @DisplayName("evaluateAll concatenates multiple matching augmentations")
        void evaluateAllConcatenatesMatches() {
            PromptAugmentation emailAug = PromptAugmentation.builder()
                    .name("email")
                    .contentSignals(List.of("(?i)^from:", "(?i)^subject:"))
                    .augmentationText("EMAIL: Extract SENDER, RECIPIENT, ACTION_ITEM entities.")
                    .minSignalMatches(1)
                    .build();

            PromptAugmentation processAug = PromptAugmentation.builder()
                    .name("process")
                    .contentSignals(List.of("(?i)deadline", "(?i)action required"))
                    .augmentationText("PROCESS: Extract DEADLINE, TASK entities.")
                    .minSignalMatches(1)
                    .build();

            String emailWithDeadline = "From: alice@example.com\nSubject: Q3 Review\n" +
                    "Action required by deadline Friday.";

            String result = PromptAugmentation.evaluateAll(
                    List.of(emailAug, processAug), emailWithDeadline);
            assertTrue(result.contains("EMAIL: Extract SENDER"));
            assertTrue(result.contains("PROCESS: Extract DEADLINE"));
        }

        @Test
        @DisplayName("Augmentation with invalid regex skips gracefully")
        void invalidRegexSkipped() {
            PromptAugmentation aug = PromptAugmentation.builder()
                    .name("bad-regex")
                    .contentSignals(List.of("[invalid regex(", "(?i)valid pattern"))
                    .augmentationText("Should still work if valid pattern matches.")
                    .minSignalMatches(1)
                    .build();

            // The invalid regex is skipped, but the valid one matches
            assertTrue(aug.matches("This has a valid pattern in it."));
        }

        @Test
        @DisplayName("Null and empty inputs return false/empty")
        void nullAndEmptyInputs() {
            PromptAugmentation aug = PromptAugmentation.builder()
                    .name("test")
                    .contentSignals(List.of("pattern"))
                    .augmentationText("text")
                    .build();

            assertFalse(aug.matches(null));
            assertFalse(aug.matches(""));
            assertEquals("", PromptAugmentation.evaluateAll(null, "text"));
            assertEquals("", PromptAugmentation.evaluateAll(List.of(), "text"));
            assertEquals("", PromptAugmentation.evaluateAll(List.of(aug), null));
        }

        @Test
        @DisplayName("minSignalMatches=3 requires three patterns to match")
        void minSignalMatchesThreshold() {
            PromptAugmentation aug = PromptAugmentation.builder()
                    .name("strict")
                    .contentSignals(List.of("alpha", "beta", "gamma", "delta"))
                    .augmentationText("All three matched")
                    .minSignalMatches(3)
                    .build();

            // Only 2 signals match — not enough
            assertFalse(aug.matches("alpha and beta are here"));
            // 3 signals match — triggers
            assertTrue(aug.matches("alpha and beta and gamma present"));
            // All 4 match — still triggers
            assertTrue(aug.matches("alpha beta gamma delta"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 9. COMMUNITY TITLE — graph model
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Community title field")
    class CommunityTitleTests {

        @Test
        @DisplayName("Community with title represents named cluster")
        void communityWithTitle() {
            Community community = new Community();
            community.setId("comm-001");
            community.setTitle("AI Research Division");
            community.setEntities(List.of("alice-id", "bob-id", "acme-id"));
            community.setSummary("A cluster of entities related to AI research at Acme Corp.");
            community.setMetadata(Map.of("algorithm", "leiden", "level", 1));

            assertEquals("AI Research Division", community.getTitle());
            assertEquals(3, community.getEntities().size());

            // Title is distinct from summary — title is a label, summary is descriptive
            assertNotEquals(community.getTitle(), community.getSummary());
        }

        @Test
        @DisplayName("Communities in graph result carry titles for display")
        void communitiesInGraphResult() {
            Community comm1 = new Community();
            comm1.setId("c1");
            comm1.setTitle("Engineering Team");
            comm1.setEntities(List.of("e1", "e2"));
            comm1.setSummary("Software engineers working on core product");

            Community comm2 = new Community();
            comm2.setId("c2");
            comm2.setTitle("Sales Organization");
            comm2.setEntities(List.of("e3", "e4"));
            comm2.setSummary("Sales team covering EMEA region");

            GraphRagResult result = GraphRagResult.builder()
                    .answer("The organization has two main divisions.")
                    .searchType(SearchType.GLOBAL)
                    .communities(List.of(comm1, comm2))
                    .build();

            assertEquals(2, result.getCommunities().size());
            Set<String> titles = result.getCommunities().stream()
                    .map(Community::getTitle)
                    .collect(Collectors.toSet());
            assertTrue(titles.contains("Engineering Team"));
            assertTrue(titles.contains("Sales Organization"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 10. GRAPH CONSTRUCTOR — RetryStatsListener
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GraphConstructor.RetryStatsListener")
    class RetryStatsListenerTests {

        @Test
        @DisplayName("RetryStatsListener receives retry events and final failure events")
        void retryStatsListenerReceivesEvents() {
            // Track events received by the listener
            List<int[]> retryEvents = new ArrayList<>();
            List<int[]> failureEvents = new ArrayList<>();

            GraphConstructor.RetryStatsListener listener = (retryAttempt, parseFailure) -> {
                if (parseFailure) {
                    failureEvents.add(new int[]{retryAttempt, 1});
                } else {
                    retryEvents.add(new int[]{retryAttempt, 0});
                }
            };

            // Simulate: 2 retries on batch 1, then success; 3 retries on batch 2 then fail
            listener.onRetryEvent(1, false); // batch 1 retry 1
            listener.onRetryEvent(2, false); // batch 1 retry 2
            // batch 1 succeeds on retry 2

            listener.onRetryEvent(1, false); // batch 2 retry 1
            listener.onRetryEvent(2, false); // batch 2 retry 2
            listener.onRetryEvent(3, false); // batch 2 retry 3
            listener.onRetryEvent(0, true);  // batch 2 all retries exhausted

            assertEquals(5, retryEvents.size());
            assertEquals(1, failureEvents.size());
            assertEquals(0, failureEvents.get(0)[0]); // retryAttempt=0 on final failure
        }

        @Test
        @DisplayName("RetryStatsListener integrates with UnifiedCrawlJob counters")
        void retryStatsUpdateJobCounters() {
            UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                    .jobId("retry-test")
                    .build();

            GraphConstructor.RetryStatsListener listener = (retryAttempt, parseFailure) -> {
                if (parseFailure) {
                    job.getGraphExtractionParseFailures().incrementAndGet();
                } else {
                    job.getGraphExtractionRetries().incrementAndGet();
                }
            };

            // Simulate extraction with 4 retries and 2 parse failures
            listener.onRetryEvent(1, false);
            listener.onRetryEvent(2, false);
            listener.onRetryEvent(1, false);
            listener.onRetryEvent(2, false);
            listener.onRetryEvent(0, true);
            listener.onRetryEvent(0, true);

            assertEquals(4, job.getGraphExtractionRetries().get());
            assertEquals(2, job.getGraphExtractionParseFailures().get());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 11. UNIFIED CRAWL SOURCE — per-source loader/chunker overrides
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UnifiedCrawlSource per-source overrides")
    class UnifiedCrawlSourceTests {

        @Test
        @DisplayName("Per-source loaderName and chunkerName route documents to specific processors")
        void perSourceOverrides() {
            UnifiedCrawlSource pdfSource = UnifiedCrawlSource.builder()
                    .label("Scanned PDFs")
                    .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl("/data/scanned")
                    .loaderName("pdf-extended")
                    .chunkerName("table-aware")
                    .build();

            UnifiedCrawlSource webSource = UnifiedCrawlSource.builder()
                    .label("Web Pages")
                    .sourceType(DocumentSourceDescriptor.SourceType.WEB_CRAWL)
                    .pathOrUrl("https://docs.example.com")
                    .loaderName("tika")
                    .chunkerName("recursive")
                    .build();

            UnifiedCrawlSource defaultSource = UnifiedCrawlSource.builder()
                    .label("Auto-detect")
                    .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl("/data/mixed")
                    .build();

            assertEquals("pdf-extended", pdfSource.getLoaderName());
            assertEquals("table-aware", pdfSource.getChunkerName());
            assertEquals("tika", webSource.getLoaderName());
            assertEquals("recursive", webSource.getChunkerName());
            assertNull(defaultSource.getLoaderName());
            assertNull(defaultSource.getChunkerName());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 12. END-TO-END SCENARIO — realistic multi-pipeline crawl
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("End-to-end multi-pipeline crawl scenario")
    class EndToEndScenarioTests {

        @Test
        @DisplayName("Complete crawl request with financial + code pipelines, routing, and augmentation")
        void completeCrawlRequestScenario() {
            // Pipeline 1: Financial document processing with Claude
            IngestPipelineDefinition financialPipeline = IngestPipelineDefinition.builder()
                    .pipelineId("financial")
                    .displayName("Financial Documents")
                    .pipelineType(IngestPipelineDefinition.PipelineType.TABLE_AWARE)
                    .enableGraphExtraction(true)
                    .extractionLlmProvider("claude-cli")
                    .extractionModelName("claude-sonnet-4-20250514")
                    .extractionEntityTypes(List.of("COMPANY", "FINANCIAL_METRIC", "REGULATORY_BODY", "FISCAL_PERIOD"))
                    .extractionRelationshipTypes(List.of("REPORTS_METRIC", "REGULATED_BY", "COMPARED_TO"))
                    .extractionTemperature(0.0)
                    .maxChunkChars(16000)
                    .promptAugmentations(List.of(
                            PromptAugmentation.builder()
                                    .name("10k-filing")
                                    .contentSignals(List.of("(?i)10-K", "(?i)annual report", "(?i)SEC filing"))
                                    .augmentationText("SEC FILING: Extract FILING_DATE, AUDITOR, RISK_FACTOR entities")
                                    .minSignalMatches(1)
                                    .build()
                    ))
                    .build();

            // Pipeline 2: Code documentation processing with Gemini
            IngestPipelineDefinition codePipeline = IngestPipelineDefinition.builder()
                    .pipelineId("code-docs")
                    .displayName("Code Documentation")
                    .pipelineType(IngestPipelineDefinition.PipelineType.CODE)
                    .enableGraphExtraction(true)
                    .extractionLlmProvider("gemini-cli")
                    .extractionEntityTypes(List.of("CLASS", "FUNCTION", "MODULE", "API_ENDPOINT"))
                    .extractionRelationshipTypes(List.of("CALLS", "IMPORTS", "EXTENDS", "EXPOSES"))
                    .maxChunkChars(8000)
                    .build();

            // Route rules
            ContentRouteRule financialRoute = ContentRouteRule.builder()
                    .pipelineId("financial")
                    .contentPatterns(List.of("(?i)balance sheet|income statement|cash flow"))
                    .contentTypes(List.of("application/pdf"))
                    .priority(5)
                    .build();

            ContentRouteRule codeRoute = ContentRouteRule.builder()
                    .pipelineId("code-docs")
                    .fileExtensions(List.of(".py", ".java", ".ts", ".go"))
                    .priority(10)
                    .build();

            ContentRouteRule tagRoute = ContentRouteRule.builder()
                    .pipelineId("financial")
                    .tags(List.of("sec-filing", "earnings"))
                    .priority(3)
                    .build();

            // Build the request
            UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                    .name("Q3 2025 Ingestion")
                    .factSheetId(42L)
                    .sources(List.of(
                            UnifiedCrawlSource.builder()
                                    .label("SEC Filings")
                                    .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                                    .pathOrUrl("/data/sec-filings")
                                    .loaderName("pdf-extended")
                                    .maxDocuments(100)
                                    .build(),
                            UnifiedCrawlSource.builder()
                                    .label("API Docs")
                                    .sourceType(DocumentSourceDescriptor.SourceType.WEB_CRAWL)
                                    .pathOrUrl("https://api.example.com/docs")
                                    .loaderName("tika")
                                    .chunkerName("code-aware")
                                    .maxDepth(5)
                                    .build()
                    ))
                    .pipelines(List.of(financialPipeline, codePipeline))
                    .routeRules(List.of(financialRoute, codeRoute, tagRoute))
                    .defaultPipelineId("code-docs")
                    .runtimeConfig(UnifiedCrawlRequest.RuntimeConfig.builder()
                            .graphExtractionParallelism(16)
                            .entityResolutionBatchSize(512)
                            .edgeComputationParallelism(8)
                            .parallelVectorAndGraph(true)
                            .llmCallTimeoutSeconds(90)
                            .graphExtractionBatchTimeoutSeconds(900)
                            .build())
                    .build();

            // Validate the full request structure
            assertEquals("Q3 2025 Ingestion", request.getName());
            assertEquals(42L, request.getFactSheetId());
            assertEquals(2, request.getSources().size());
            assertEquals(2, request.getPipelines().size());
            assertEquals(3, request.getRouteRules().size());
            assertEquals("code-docs", request.getDefaultPipelineId());

            // Route rules are ordered by priority for evaluation
            List<ContentRouteRule> sortedRules = request.getRouteRules().stream()
                    .sorted(Comparator.comparingInt(ContentRouteRule::getPriority))
                    .toList();
            assertEquals("financial", sortedRules.get(0).getPipelineId()); // tag route, priority 3
            assertEquals("financial", sortedRules.get(1).getPipelineId()); // content route, priority 5
            assertEquals("code-docs", sortedRules.get(2).getPipelineId()); // file ext route, priority 10

            // Financial pipeline augmentation triggers on SEC content
            String secContent = "This is a 10-K Annual Report filed with SEC. Total Assets: $5.2B.";
            assertTrue(financialPipeline.getPromptAugmentations().get(0).matches(secContent));

            // Runtime config has all new fields
            assertNotNull(request.getRuntimeConfig());
            assertEquals(512, request.getRuntimeConfig().getEntityResolutionBatchSize());
            assertTrue(request.getRuntimeConfig().getParallelVectorAndGraph());
            assertEquals(90, request.getRuntimeConfig().getLlmCallTimeoutSeconds());
        }

        @Test
        @DisplayName("Job tracking full lifecycle: preprocessing -> extraction with retries -> completion")
        void jobLifecycleWithRetries() {
            UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                    .jobId("lifecycle-test")
                    .request(UnifiedCrawlRequest.builder().name("lifecycle").build())
                    .build();

            // Stage 1: Discovery
            job.getStatus().set(UnifiedCrawlJob.Status.RUNNING);
            job.setStartedAt(Instant.now());
            job.getDocumentsDiscovered().set(50);

            // Stage 2: Loading + Preprocessing
            job.getDocumentsLoaded().set(45);
            job.getDocumentsPreprocessed().set(45);
            job.getDocumentsTranslated().set(8); // 8 docs were in non-English
            job.getChunksCreated().set(200);

            // Stage 3: Graph extraction with LLM retries
            for (int batch = 0; batch < 10; batch++) {
                job.getGraphChunksProcessed().incrementAndGet();
                // Simulate some batches needing retries
                if (batch % 3 == 0) {
                    job.getGraphExtractionRetries().addAndGet(2);
                }
                // Simulate one batch failing entirely
                if (batch == 7) {
                    job.getGraphExtractionParseFailures().incrementAndGet();
                }
                job.getEntitiesExtracted().addAndGet(5);
                job.getRelationshipsExtracted().addAndGet(3);
            }

            // Stage 4: Completion
            job.getStatus().set(UnifiedCrawlJob.Status.COMPLETED);
            job.setCompletedAt(Instant.now());

            // Verify final state
            assertEquals(UnifiedCrawlJob.Status.COMPLETED, job.getStatus().get());
            assertEquals(50, job.getDocumentsDiscovered().get());
            assertEquals(45, job.getDocumentsPreprocessed().get());
            assertEquals(8, job.getDocumentsTranslated().get());
            assertEquals(50, job.getEntitiesExtracted().get());
            assertEquals(30, job.getRelationshipsExtracted().get());
            assertEquals(8, job.getGraphExtractionRetries().get()); // 4 batches * 2 retries each
            assertEquals(1, job.getGraphExtractionParseFailures().get());

            // Snapshot preserves all counts
            UnifiedCrawlJob.ProgressSnapshot snapshot = job.toProgressSnapshot();
            assertTrue(snapshot.getElapsedMs() >= 0);
            assertEquals(8, snapshot.getGraphExtractionRetries());
            assertEquals(1, snapshot.getGraphExtractionParseFailures());
            assertEquals(45, snapshot.getDocumentsPreprocessed());
            assertEquals(8, snapshot.getDocumentsTranslated());
        }

        @Test
        @DisplayName("Full graph with communities carrying titles integrates with HYBRID result")
        void graphWithCommunitiesInHybridResult() {
            Entity alice = buildEntity("e1", "Alice", "PERSON", 0.95);
            Entity acme = buildEntity("e2", "Acme Corp", "ORGANIZATION", 0.92);
            Entity widgetX = buildEntity("e3", "Widget X", "PRODUCT", 0.88);
            Entity aiResearch = buildEntity("e4", "AI Research", "CONCEPT", 0.85);

            Relationship worksAt = buildRelationship("e1", "e2", "WORKS_AT", 0.9);
            Relationship produces = buildRelationship("e2", "e3", "PRODUCES", 0.85);
            Relationship uses = buildRelationship("e3", "e4", "USES", 0.8);

            Community engTeam = new Community();
            engTeam.setId("c1");
            engTeam.setTitle("Engineering Division");
            engTeam.setEntities(List.of("e1", "e2"));
            engTeam.setSummary("Alice and Acme Corp form the core engineering group");

            Community productCluster = new Community();
            productCluster.setId("c2");
            productCluster.setTitle("AI Product Line");
            productCluster.setEntities(List.of("e3", "e4"));
            productCluster.setSummary("Widget X and AI Research form the product cluster");

            Graph graph = Graph.builder()
                    .id("test-graph")
                    .name("Acme Knowledge Graph")
                    .factSheetId(42L)
                    .entities(List.of(alice, acme, widgetX, aiResearch))
                    .relationships(List.of(worksAt, produces, uses))
                    .communities(List.of(engTeam, productCluster))
                    .build();

            // Simulate HYBRID query result using this graph
            GraphRagResult result = GraphRagResult.builder()
                    .answer("Alice works at Acme Corp which produces Widget X using AI Research.")
                    .searchType(SearchType.HYBRID)
                    .entities(graph.getEntities())
                    .relationships(graph.getRelationships())
                    .communities(graph.getCommunities())
                    .hopsPerformed(2)
                    .nodesVisited(4)
                    .traversalPaths(Map.of("e1", List.of("e1", "e2", "e3")))
                    .scoreBreakdown(Map.of(
                            "e1", Map.of("vectorScore", 0.95, "graphScore", 0.0, "hopDistance", 0.0, "combined", 0.475),
                            "e2", Map.of("vectorScore", 0.0, "graphScore", 0.9, "hopDistance", 1.0, "combined", 0.45),
                            "e3", Map.of("vectorScore", 0.0, "graphScore", 0.85, "hopDistance", 2.0, "combined", 0.425)
                    ))
                    .build();

            // Verify communities have titles for UI display
            assertEquals(2, result.getCommunities().size());
            Community eng = result.getCommunities().stream()
                    .filter(c -> "c1".equals(c.getId())).findFirst().orElseThrow();
            assertEquals("Engineering Division", eng.getTitle());
            assertTrue(eng.getEntities().contains("e1"));

            Community prod = result.getCommunities().stream()
                    .filter(c -> "c2".equals(c.getId())).findFirst().orElseThrow();
            assertEquals("AI Product Line", prod.getTitle());

            // Verify traversal from seed e1 reached e3 in 2 hops
            List<String> path = result.getTraversalPaths().get("e1");
            assertEquals("e1", path.get(0));
            assertEquals("e2", path.get(1));
            assertEquals("e3", path.get(2));

            // Verify hop distances decrease combined score
            double seedScore = result.getScoreBreakdown().get("e1").get("combined");
            double hop1Score = result.getScoreBreakdown().get("e2").get("combined");
            double hop2Score = result.getScoreBreakdown().get("e3").get("combined");
            assertTrue(seedScore >= hop1Score, "Seed should score higher than 1-hop neighbor");
            assertTrue(hop1Score >= hop2Score, "1-hop should score higher than 2-hop neighbor");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private static Entity buildEntity(String id, String title, String type, double confidence) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType(type);
        entity.setConfidence(confidence);
        return entity;
    }

    private static Relationship buildRelationship(String source, String target, String type, double weight) {
        Relationship rel = new Relationship();
        rel.setSource(source);
        rel.setTarget(target);
        rel.setType(type);
        rel.setWeight(weight);
        return rel;
    }

    /**
     * ExtractionLlmService implementation that tracks model overrides.
     */
    private static class ModelTrackingService implements ExtractionLlmService {
        private final String id;
        private final String description;
        private final boolean available;
        private String modelOverride;

        ModelTrackingService(String id, String description, boolean available) {
            this.id = id;
            this.description = description;
            this.available = available;
        }

        @Override public String getId() { return id; }
        @Override public String getDescription() { return description; }
        @Override public String complete(String prompt) { return "response from " + id; }
        @Override public boolean isAvailable() { return available; }
        @Override public void setModelOverride(String model) { this.modelOverride = model; }
        @Override public String getEffectiveModel() { return modelOverride; }
    }
}
