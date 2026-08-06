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

package ai.kompile.app.services;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy.FailureMode;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SingleSourceCrawlStarterTest {

    @Test
    void startBuildsObservableSingleSourceCrawlWithGraphAndVectorStages() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenReturn(UnifiedCrawlJob.builder()
                .jobId("job-1")
                .build());

        SingleSourceCrawlStarter starter = new SingleSourceCrawlStarter(
                crawlService,
                null,
                null,
                null,
                null,
                null);
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .label("Pasted text")
                .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/tmp/pasted-text.md")
                .maxDocuments(1)
                .chunkerName("recursive-character")
                .build();

        SingleSourceCrawlStarter.SingleSourceCrawlResult result = starter.start("Add text source", source);

        ArgumentCaptor<UnifiedCrawlRequest> requestCaptor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(crawlService).startJob(requestCaptor.capture());
        UnifiedCrawlRequest request = requestCaptor.getValue();

        assertEquals("job-1", result.jobId());
        assertEquals("PENDING", result.status());
        assertEquals(1, result.sourceCount());
        assertTrue(result.graphExtractionEnabled());
        assertTrue(result.vectorIndexEnabled());
        assertEquals("Add text source", request.getName());
        assertEquals(1, request.getSources().size());
        assertEquals("Pasted text", request.getSources().get(0).getLabel());
        assertNotNull(request.getGraphExtraction());
        assertTrue(request.getGraphExtraction().isEnabled());
        assertNotNull(request.getVectorIndex());
        assertTrue(request.getVectorIndex().isEnabled());
        assertEquals("recursive-character", request.getVectorIndex().getChunkerName());
    }

    @Test
    void startCopiesGlobalExtractionDefaults() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        GraphExtractionConfigService graphConfigService = mock(GraphExtractionConfigService.class);
        GraphExtractionConfigService.GraphExtractionConfig appConfig = GraphExtractionConfigService.GraphExtractionConfig.defaults();
        appConfig.activeSchemaPresetId = "finance-v1";
        appConfig.entityTypes = List.of("PRODUCT", "REGION");
        appConfig.relationshipTypes = List.of("SOLD_IN");
        appConfig.extractionModelProvider = "opencode";
        appConfig.extractionModelName = "deepseek-v4-pro";
        appConfig.extractionTemperature = 0.2;
        appConfig.extractionMaxTokens = 2048;
        appConfig.customExtractionPrompt = "extract concise facts";
        appConfig.schemaEnforcement = "STRICT";
        appConfig.deduplicationEnabled = true;
        appConfig.similarityThreshold = 0.91;
        appConfig.validationPolicy = GraphExtractionValidationPolicy.builder()
                .failureMode(FailureMode.WARN)
                .enabledValidators(List.of(GraphExtractionValidationPolicy.TYPE_NAME_FORMAT))
                .maxErrorsInRetryPrompt(4)
                .build();
        GraphSchemaPresetService presetService = mock(GraphSchemaPresetService.class);
        GraphSchema presetSchema = new GraphSchema(
                List.of(
                        new NodeType("PRODUCT", "A product",
                                List.of(new PropertyType("sku", "String"))),
                        new NodeType("REGION", "A region", null)),
                List.of(new RelationshipType(
                        "SOLD_IN", "Product sold in region", null, List.of("available_in"))),
                List.of("(PRODUCT)-[:SOLD_IN]->(REGION)"));
        when(presetService.getSchema("finance-v1")).thenReturn(Optional.of(presetSchema));
        when(graphConfigService.getConfig()).thenReturn(appConfig);
        when(crawlService.startJob(any())).thenReturn(UnifiedCrawlJob.builder()
                .jobId("job-2")
                .build());

        SingleSourceCrawlStarter starter = new SingleSourceCrawlStarter(
                crawlService,
                null,
                presetService,
                null,
                graphConfigService,
                null);
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .label("URL note")
                .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/tmp/url.md")
                .build();

        starter.start("Add URL source", source);

        ArgumentCaptor<UnifiedCrawlRequest> requestCaptor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(crawlService).startJob(requestCaptor.capture());
        GraphExtractionConfig graphConfig = requestCaptor.getValue().getGraphExtraction();

        assertTrue(graphConfig.isEnabled());
        assertEquals("finance-v1", graphConfig.getSchemaPresetId());
        assertEquals(List.of("PRODUCT", "REGION"), graphConfig.getEntityTypes());
        assertEquals(List.of("SOLD_IN"), graphConfig.getRelationshipTypes());
        assertNotNull(graphConfig.getStandardizedSchema());
        assertEquals("sku", graphConfig.getStandardizedSchema().getNodeTypes().get(0)
                .getProperties().get(0).getName());
        assertEquals(List.of("available_in"), graphConfig.getStandardizedSchema()
                .getRelationshipTypes().get(0).getAliases());
        assertEquals("opencode", graphConfig.getLlmProvider());
        assertEquals("deepseek-v4-pro", graphConfig.getModelName());
        assertEquals(0.2, graphConfig.getTemperature());
        assertEquals(2048, graphConfig.getMaxTokens());
        assertEquals("extract concise facts", graphConfig.getCustomPrompt());
        assertEquals(SchemaEnforcementMode.STRICT, graphConfig.getSchemaMode());
        assertTrue(graphConfig.isEntityResolution());
        assertEquals(0.91, graphConfig.getEntityResolutionSimilarityThreshold());
        assertNotNull(graphConfig.getValidationPolicy());
        assertEquals(FailureMode.WARN, graphConfig.getValidationPolicy().effectiveFailureMode());
        assertEquals(List.of(GraphExtractionValidationPolicy.TYPE_NAME_FORMAT),
                graphConfig.getValidationPolicy().effectiveEnabledValidators());
        assertEquals(List.of("(PRODUCT)-[:SOLD_IN]->(REGION)"),
                graphConfig.getValidationPolicy().effectiveRelationPatterns());
        assertEquals(4, graphConfig.getValidationPolicy().effectiveMaxErrorsInRetryPrompt());
    }

    @Test
    void persistedCanonicalSchemaReachesCrawlWithoutPresetLookup() {
        GraphExtractionConfigService configService = mock(GraphExtractionConfigService.class);
        GraphExtractionConfigService.GraphExtractionConfig appConfig =
                GraphExtractionConfigService.GraphExtractionConfig.defaults();
        appConfig.standardizedSchema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person",
                                List.of(new PropertyType("role", "String"))),
                        new NodeType("ROLE", "A business role", null)),
                List.of(new RelationshipType("HAS_ROLE", "Person has role", null,
                        List.of("serves_as"))),
                List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"));
        when(configService.getConfig()).thenReturn(appConfig);

        SingleSourceCrawlStarter starter = new SingleSourceCrawlStarter(
                mock(UnifiedCrawlService.class), null, null, null, configService, null);

        GraphExtractionConfig graphConfig = starter.defaultGraphExtractionConfig();

        assertNotNull(graphConfig.getStandardizedSchema());
        assertEquals("role", graphConfig.getStandardizedSchema().getNodeTypes().get(0)
                .getProperties().get(0).getName());
        assertEquals(List.of("serves_as"), graphConfig.getStandardizedSchema()
                .getRelationshipTypes().get(0).getAliases());
    }

    // ---- Flexible options entry point ----

    private static SingleSourceCrawlStarter starterWith(UnifiedCrawlService crawlService) {
        return new SingleSourceCrawlStarter(crawlService, null, null, null, null, null);
    }

    private static UnifiedCrawlSource fileSource() {
        return UnifiedCrawlSource.builder()
                .label("Doc")
                .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/tmp/doc.md")
                .maxDocuments(1)
                .build();
    }

    @Test
    void legacyStart_leavesStepSelectionUntouched() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenReturn(UnifiedCrawlJob.builder().jobId("job-l").build());

        SingleSourceCrawlStarter.SingleSourceCrawlResult result =
                starterWith(crawlService).start("Legacy", fileSource());

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(crawlService).startJob(captor.capture());
        UnifiedCrawlRequest request = captor.getValue();

        assertTrue(request.getEnabledSteps().isEmpty());
        assertNull(request.getStrictSteps());
        assertNotNull(request.getVectorIndex());
        assertTrue(request.getVectorIndex().isEnabled());
        assertNull(result.completed(), "legacy path never waits");
        assertEquals(Boolean.TRUE, result.persisted());
        assertEquals("RUN", result.stepsPlanned().get("GRAPH_EXTRACTION"));
    }

    @Test
    void optionsSteps_mapToStrictSelectionAndReportResolvedPlan() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenReturn(UnifiedCrawlJob.builder().jobId("job-s").build());

        SingleSourceCrawlStarter.SingleSourceCrawlResult result = starterWith(crawlService).start(
                "Steps",
                fileSource(),
                SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                        .steps(List.of("edge_computation"))
                        .build());

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(crawlService).startJob(captor.capture());
        UnifiedCrawlRequest request = captor.getValue();

        assertEquals(List.of("EDGE_COMPUTATION"), request.getEnabledSteps());
        assertEquals(Boolean.TRUE, request.getStrictSteps());
        // The resolved plan surfaces the dependencies the closure auto-added — and what got skipped.
        assertEquals("RUN", result.stepsPlanned().get("EDGE_COMPUTATION"));
        assertEquals("RUN", result.stepsPlanned().get("ENTITY_RESOLUTION"));
        assertEquals("RUN", result.stepsPlanned().get("GRAPH_EXTRACTION"));
        assertEquals("SKIP", result.stepsPlanned().get("ENRICHMENT"));
        assertEquals("SKIP", result.stepsPlanned().get("VECTOR_INDEXING"));
    }

    @Test
    void optionsUnknownStep_throwsListingValidIds() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> starterWith(crawlService).start(
                        "Bad",
                        fileSource(),
                        SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                                .steps(List.of("BOGUS_STEP"))
                                .build()));

        assertTrue(e.getMessage().contains("BOGUS_STEP"));
        assertTrue(e.getMessage().contains("Valid steps"));
        assertTrue(e.getMessage().contains("GRAPH_EXTRACTION"));
        verify(crawlService, never()).startJob(any());
    }

    @Test
    void selectingVectorIndexing_forcesVectorConfigOnDespiteToggle() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenReturn(UnifiedCrawlJob.builder().jobId("job-v").build());

        SingleSourceCrawlStarter.SingleSourceCrawlResult result = starterWith(crawlService).start(
                "Embeddings only",
                fileSource(),
                SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                        .steps(List.of("VECTOR_INDEXING"))
                        .vectorIndex(false)
                        .build());

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(crawlService).startJob(captor.capture());
        assertNotNull(captor.getValue().getVectorIndex());
        assertTrue(captor.getValue().getVectorIndex().isEnabled(),
                "selecting the step must force the dual-gated vector config on");
        assertEquals("RUN", result.stepsPlanned().get("VECTOR_INDEXING"));
        assertEquals("SKIP", result.stepsPlanned().get("GRAPH_EXTRACTION"),
                "strict selection must not seed the graph spine");
    }

    @Test
    void vectorIndexFalseWithoutStepSelection_disablesIndexing() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenReturn(UnifiedCrawlJob.builder().jobId("job-nv").build());

        SingleSourceCrawlStarter.SingleSourceCrawlResult result = starterWith(crawlService).start(
                "No vectors",
                fileSource(),
                SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                        .vectorIndex(false)
                        .build());

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(crawlService).startJob(captor.capture());
        assertNull(captor.getValue().getVectorIndex());
        assertFalse(result.vectorIndexEnabled());
        assertEquals("SKIP", result.stepsPlanned().get("VECTOR_INDEXING"));
    }

    @Test
    void modelOverrides_applyOnTopOfGlobalDefaults() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenReturn(UnifiedCrawlJob.builder().jobId("job-m").build());

        starterWith(crawlService).start(
                "Model override",
                fileSource(),
                SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                        .modelName("qwen3-coder")
                        .llmProvider("opencode")
                        .build());

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(crawlService).startJob(captor.capture());
        GraphExtractionConfig graphConfig = captor.getValue().getGraphExtraction();
        assertEquals("qwen3-coder", graphConfig.getModelName());
        assertEquals("opencode", graphConfig.getLlmProvider());
    }

    @Test
    void factSheetIdOption_targetsSheetDirectly() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenReturn(UnifiedCrawlJob.builder().jobId("job-f").build());

        SingleSourceCrawlStarter.SingleSourceCrawlResult result = starterWith(crawlService).start(
                "Sheet 42",
                fileSource(),
                SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                        .factSheetId(42L)
                        .build());

        assertEquals(42L, result.factSheetId());
    }

    @Test
    void factSheetNameOption_resolvesExactSheetBeforeStarting() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        FactSheetService factSheetService = mock(FactSheetService.class);
        when(factSheetService.getSheetByName("FP&A")).thenReturn(Optional.of(
                FactSheet.builder().id(73L).name("FP&A").build()));
        when(crawlService.startJob(any())).thenReturn(
                UnifiedCrawlJob.builder().jobId("job-name").build());
        SingleSourceCrawlStarter starter = new SingleSourceCrawlStarter(
                crawlService, factSheetService, null, null, null, null);

        starter.start("Named sheet", fileSource(),
                SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                        .factSheetName("FP&A")
                        .build());

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(crawlService).startJob(captor.capture());
        assertEquals(73L, captor.getValue().getFactSheetId());
    }

    @Test
    void unknownFactSheetName_failsWithoutStartingOrUsingActiveSheet() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        FactSheetService factSheetService = mock(FactSheetService.class);
        when(factSheetService.getSheetByName("FP&A typo")).thenReturn(Optional.empty());
        SingleSourceCrawlStarter starter = new SingleSourceCrawlStarter(
                crawlService, factSheetService, null, null, null, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> starter.start("Bad sheet", fileSource(),
                        SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                                .factSheetName("FP&A typo")
                                .build()));

        assertTrue(error.getMessage().contains("does not exist"));
        verify(crawlService, never()).startJob(any());
        verify(factSheetService, never()).getActiveSheet();
    }

    @Test
    void waitForCompletion_reportsTerminalState() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        UnifiedCrawlJob job = UnifiedCrawlJob.builder().jobId("job-w").build();
        job.getStatus().set(UnifiedCrawlJob.Status.COMPLETED);
        when(crawlService.startJob(any())).thenReturn(job);

        SingleSourceCrawlStarter.SingleSourceCrawlResult result = starterWith(crawlService).start(
                "Wait",
                fileSource(),
                SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                        .waitForCompletion(true)
                        .waitTimeoutMs(5_000L)
                        .build());

        assertEquals(Boolean.TRUE, result.completed());
        assertEquals("COMPLETED", result.status());
    }

    @Test
    void waitForCompletion_pendingEmbeddingCountsAsTerminal() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        UnifiedCrawlJob job = UnifiedCrawlJob.builder().jobId("job-pe").build();
        job.getStatus().set(UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING);
        when(crawlService.startJob(any())).thenReturn(job);

        SingleSourceCrawlStarter.SingleSourceCrawlResult result = starterWith(crawlService).start(
                "Wait pending",
                fileSource(),
                SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                        .waitForCompletion(true)
                        .waitTimeoutMs(5_000L)
                        .build());

        assertEquals(Boolean.TRUE, result.completed());
        assertEquals("COMPLETED_PENDING_EMBEDDING", result.status());
    }

    @Test
    void waitForCompletion_timeoutReportsIncompleteWithJobId() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenReturn(UnifiedCrawlJob.builder().jobId("job-t").build());

        SingleSourceCrawlStarter.SingleSourceCrawlResult result = starterWith(crawlService).start(
                "Timeout",
                fileSource(),
                SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                        .waitForCompletion(true)
                        .waitTimeoutMs(1L)
                        .build());

        assertEquals(Boolean.FALSE, result.completed());
        assertEquals("job-t", result.jobId());
        assertEquals("PENDING", result.status());
    }

    @Test
    void queueFull_propagatesIllegalState() {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any()))
                .thenThrow(new IllegalStateException("Unified crawl queue is full; try again later"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> starterWith(crawlService).start(
                        "Full",
                        fileSource(),
                        SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                                .waitForCompletion(true)
                                .build()));
        assertTrue(e.getMessage().contains("queue is full"));
    }
}
