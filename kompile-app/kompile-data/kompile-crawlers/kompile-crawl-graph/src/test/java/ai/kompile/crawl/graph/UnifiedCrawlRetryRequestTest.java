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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.CrawlChunkingConfig;
import ai.kompile.core.crawl.graph.DistributedGraphRuntimeContext;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import ai.kompile.core.crawler.pipeline.ContentRouteRule;
import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class UnifiedCrawlRetryRequestTest {

    @Test
    void retryCopyRetainsCompleteRequestAndDoesNotAliasMutableState() throws Exception {
        List<String> sourceNested = new ArrayList<>(List.of("source-value"));
        Map<String, Object> sourceProperties = new LinkedHashMap<>();
        sourceProperties.put("nested", sourceNested);
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .label("source")
                .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/docs")
                .includePatterns(new ArrayList<>(List.of("**/*.md")))
                .properties(sourceProperties)
                .pipelineId("pipeline-a")
                .build();

        GraphExtractionConfig graphExtraction = GraphExtractionConfig.builder()
                .llmProvider("provider-a")
                .modelName("model-a")
                .entityTypes(new ArrayList<>(List.of("PERSON")))
                .extractionModelAllow(new ArrayList<>(List.of("model-a")))
                .build();
        Map<String, Object> chunkingOptions = new LinkedHashMap<>();
        chunkingOptions.put("nested", new ArrayList<>(List.of("chunk-value")));
        CrawlChunkingConfig chunking = CrawlChunkingConfig.builder()
                .chunkerName("recursive")
                .chunkSize(512)
                .options(chunkingOptions)
                .build();
        ProcessingRouteConfig route = ProcessingRouteConfig.builder()
                .fallbackEnabled(false)
                .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                        .id("native-chat")
                        .type(ProcessingRouteConfig.ProcessingBackendType.CHAT_MODEL)
                        .provider("codex")
                        .modelName("route-model")
                        .capabilities(new ArrayList<>(List.of("text")))
                        .build()))
                .build();
        IngestPipelineDefinition pipeline = IngestPipelineDefinition.builder()
                .pipelineId("pipeline-a")
                .displayName("Pipeline A")
                .extractionLlmProvider("provider-a")
                .extractionModelName("model-a")
                .options(new LinkedHashMap<>(Map.of("mode", "focused")))
                .build();
        ContentRouteRule routeRule = ContentRouteRule.builder()
                .pipelineId("pipeline-a")
                .contentTypes(new ArrayList<>(List.of("text/markdown")))
                .metadataMatchers(new LinkedHashMap<>(Map.of("kind", "docs")))
                .build();
        UnifiedCrawlRequest.HydrationConfig hydration = UnifiedCrawlRequest.HydrationConfig.builder()
                .enabledStageIds(new HashSet<>(Set.of("DERIVATION", "HEALTH")))
                .confidencePruneThreshold(0.6)
                .dryRun(true)
                .build();
        UnifiedCrawlRequest.RuntimeConfig runtime = UnifiedCrawlRequest.RuntimeConfig.builder()
                .clearGraphBeforeRun(true)
                .embeddingAlgorithm("ROTATE")
                .embeddingDim(64)
                .embeddingEpochs(7)
                .build();
        Map<String, Object> preprocessing = new LinkedHashMap<>();
        preprocessing.put("strategy", "PII_REDACTION");
        preprocessing.put("nested", new ArrayList<>(List.of("redact")));
        UnifiedCrawlRequest.DistributionConfig distribution = UnifiedCrawlRequest.DistributionConfig.builder()
                .workerCount(2)
                .workerMetadata(new LinkedHashMap<>(Map.of("lane", "retry-test")))
                .build();
        DistributedGraphRuntimeContext runtimeContext = new DistributedGraphRuntimeContext(
                "https://graph-authority.example/", "secret-bearer", "writer-lease",
                "session", "partition", 2);

        UnifiedCrawlRequest original = UnifiedCrawlRequest.builder()
                .name("original crawl")
                .factSheetId(17L)
                .factSheetName("docs")
                .sources(new ArrayList<>(List.of(source)))
                .graphExtraction(graphExtraction)
                .chunking(chunking)
                .vectorIndex(VectorIndexConfig.builder().enabled(true).collectionName("docs").build())
                .processingRoute(route)
                .runtimeConfig(runtime)
                .preprocessing(preprocessing)
                .hydration(hydration)
                .pipelines(new ArrayList<>(List.of(pipeline)))
                .routeRules(new ArrayList<>(List.of(routeRule)))
                .defaultPipelineId("pipeline-a")
                .distribution(distribution)
                .distributedGraphExecution(new ai.kompile.core.crawl.graph.DistributedGraphExecution(
                        1, "session", "partition", 0, 1, 1, "owner", 17L,
                        "logical", "physical", "generation", "physical", 4L, true, true))
                .distributedGraphRuntimeContext(runtimeContext)
                .retryFromJobId("old-job")
                .retryPhase("OLD_PHASE")
                .retryDocumentKeys(new ArrayList<>(List.of("old-key")))
                .maxValidationRetries(5)
                .deriveOntology(false)
                .enabledSteps(new ArrayList<>(List.of("GRAPH_EXTRACTION")))
                .archivedSteps(new ArrayList<>(List.of("VECTOR_INDEXING")))
                .strictSteps(true)
                .build();

        UnifiedCrawlRequest copy = UnifiedCrawlGraphServiceImpl.copyRequestForRetry(
                original, "job-123", "GRAPH_EXTRACTION", List.of("failed-key"), false);

        assertNotSame(original, copy);
        assertEquals("original crawl (retry)", copy.getName());
        assertEquals("job-123", copy.getRetryFromJobId());
        assertEquals("GRAPH_EXTRACTION", copy.getRetryPhase());
        assertEquals(List.of("failed-key"), copy.getRetryDocumentKeys());
        assertEquals(original.getFactSheetId(), copy.getFactSheetId());
        assertEquals(original.getFactSheetName(), copy.getFactSheetName());
        assertEquals(original.getSources(), copy.getSources());
        assertEquals(original.getGraphExtraction(), copy.getGraphExtraction());
        assertEquals(original.getChunking(), copy.getChunking());
        assertEquals(original.getVectorIndex(), copy.getVectorIndex());
        assertEquals(original.getProcessingRoute(), copy.getProcessingRoute());
        assertEquals(original.getRuntimeConfig(), copy.getRuntimeConfig());
        assertEquals(original.getPreprocessing(), copy.getPreprocessing());
        assertEquals(original.getHydration(), copy.getHydration());
        assertEquals(original.getPipelines(), copy.getPipelines());
        assertEquals(original.getRouteRules(), copy.getRouteRules());
        assertEquals(original.getDefaultPipelineId(), copy.getDefaultPipelineId());
        assertEquals(original.getDistribution(), copy.getDistribution());
        assertEquals(original.getDistributedGraphExecution(), copy.getDistributedGraphExecution());
        assertSame(runtimeContext, copy.getDistributedGraphRuntimeContext());
        assertEquals(original.getMaxValidationRetries(), copy.getMaxValidationRetries());
        assertEquals(original.getDeriveOntology(), copy.getDeriveOntology());
        assertEquals(original.getEnabledSteps(), copy.getEnabledSteps());
        assertEquals(original.getArchivedSteps(), copy.getArchivedSteps());
        assertEquals(original.getStrictSteps(), copy.getStrictSteps());
        assertEquals("old-job", original.getRetryFromJobId(), "Original retry metadata must remain unchanged");
        assertEquals(List.of("old-key"), original.getRetryDocumentKeys());

        assertNotSame(original.getSources(), copy.getSources());
        assertNotSame(original.getSources().get(0), copy.getSources().get(0));
        assertNotSame(original.getSources().get(0).getProperties(), copy.getSources().get(0).getProperties());
        assertNotSame(original.getGraphExtraction(), copy.getGraphExtraction());
        assertNotSame(original.getChunking(), copy.getChunking());
        assertNotSame(original.getProcessingRoute(), copy.getProcessingRoute());
        assertNotSame(original.getRuntimeConfig(), copy.getRuntimeConfig());
        assertNotSame(original.getHydration(), copy.getHydration());
        assertNotSame(original.getPipelines(), copy.getPipelines());
        assertNotSame(original.getRouteRules(), copy.getRouteRules());
        assertNotSame(original.getDistribution(), copy.getDistribution());
        assertNotSame(original.getDistributedGraphExecution(), copy.getDistributedGraphExecution());

        String serialized = new ObjectMapper().writeValueAsString(original);
        assertFalse(serialized.contains("distributedGraphRuntimeContext"), serialized);
        assertFalse(serialized.contains("secret-bearer"), serialized);

        @SuppressWarnings("unchecked")
        List<String> copiedNested = (List<String>) copy.getSources().get(0).getProperties().get("nested");
        copiedNested.add("copy-only");
        assertFalse(sourceNested.contains("copy-only"));
        copy.getRuntimeConfig().setEmbeddingEpochs(99);
        assertNotEquals(99, original.getRuntimeConfig().getEmbeddingEpochs());
    }

    @Test
    void retryCopyPreservesSelectiveKeysAndClearsThemForReplacement() {
        UnifiedCrawlRequest original = UnifiedCrawlRequest.builder()
                .name("source crawl")
                .retryFromJobId("original-job")
                .retryPhase("GRAPH_EXTRACTION")
                .retryDocumentKeys(new ArrayList<>(List.of("original-key")))
                .build();

        UnifiedCrawlRequest selective = UnifiedCrawlGraphServiceImpl.copyRequestForRetry(
                original, "retry-job", "GRAPH_EXTRACTION", List.of("failed-a", "failed-b"), false);
        UnifiedCrawlRequest replacement = UnifiedCrawlGraphServiceImpl.copyRequestForRetry(
                original, "replacement-job", "GRAPH_EXTRACTION", List.of("failed-a"), true);

        assertEquals(List.of("failed-a", "failed-b"), selective.getRetryDocumentKeys());
        assertTrue(replacement.getRetryDocumentKeys().isEmpty());
        assertEquals(List.of("original-key"), original.getRetryDocumentKeys());
    }

    @Test
    void selectiveRetryUsesCanonicalSourceMetadata_notHumanReadableTitles() {
        Document failed = new Document("same title", Map.of("source_path", "/docs/failed.md"));
        Document successful = new Document("same title", Map.of("source_path", "/docs/success.md"));

        List<Document> selected = UnifiedCrawlGraphServiceImpl.filterDocumentsForRetry(
                List.of(successful, failed), List.of("/docs/failed.md"), new CrawlDocumentTracker());

        assertEquals(List.of(failed), selected);
        assertEquals("/docs/failed.md", new CrawlDocumentTracker().documentKey(failed));
        assertFalse(selected.contains(successful));
    }

    @Test
    void replacementRetryWithoutKeysKeepsEveryLoadedDocument() {
        Document first = new Document("same title", Map.of("source_path", "/docs/first.md"));
        Document second = new Document("same title", Map.of("source_path", "/docs/second.md"));

        assertEquals(List.of(first, second), UnifiedCrawlGraphServiceImpl.filterDocumentsForRetry(
                List.of(first, second), List.of(), new CrawlDocumentTracker()));
    }

    @Test
    void retryEligibilityRequiresTerminalFailureOrTerminalPartialFailure() {
        assertFalse(UnifiedCrawlGraphServiceImpl.isRetryEligible(job(UnifiedCrawlJob.Status.PENDING, false)));
        assertFalse(UnifiedCrawlGraphServiceImpl.isRetryEligible(job(UnifiedCrawlJob.Status.RUNNING, true)));
        assertFalse(UnifiedCrawlGraphServiceImpl.isRetryEligible(job(UnifiedCrawlJob.Status.PAUSED, true)));
        assertFalse(UnifiedCrawlGraphServiceImpl.isRetryEligible(job(UnifiedCrawlJob.Status.CANCELLED, true)));
        assertFalse(UnifiedCrawlGraphServiceImpl.isRetryEligible(job(UnifiedCrawlJob.Status.COMPLETED, false)));
        assertTrue(UnifiedCrawlGraphServiceImpl.isRetryEligible(job(UnifiedCrawlJob.Status.FAILED, false)));
        assertTrue(UnifiedCrawlGraphServiceImpl.isRetryEligible(job(UnifiedCrawlJob.Status.COMPLETED, true)));
    }

    private static UnifiedCrawlJob job(UnifiedCrawlJob.Status status, boolean failedDocument) {
        Map<String, UnifiedCrawlJob.DocumentProgress> progress = failedDocument
                ? Map.of("/docs/failed.md", UnifiedCrawlJob.DocumentProgress.builder()
                .documentKey("/docs/failed.md").status("FAILED").build())
                : Map.of();
        return UnifiedCrawlJob.builder()
                .status(new AtomicReference<>(status))
                .documentProgress(progress)
                .build();
    }
}
