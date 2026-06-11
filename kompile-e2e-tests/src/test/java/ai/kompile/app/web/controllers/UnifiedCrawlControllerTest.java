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

package ai.kompile.app.web.controllers;

import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link UnifiedCrawlController} — verifies REST endpoint behavior,
 * request/response mapping, and error handling at the controller layer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnifiedCrawlControllerTest {

    @Mock
    private UnifiedCrawlService unifiedCrawlService;

    private UnifiedCrawlController controller;

    @BeforeEach
    void setUp() {
        controller = new UnifiedCrawlController(unifiedCrawlService);
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. START JOB
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /start with valid request returns 200 with job info")
    void startJob_validRequest() {
        UnifiedCrawlJob mockJob = buildMockJob("job-1", UnifiedCrawlJob.Status.PENDING);
        when(unifiedCrawlService.startJob(any())).thenReturn(mockJob);

        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("Test crawl")
                .sources(List.of(UnifiedCrawlSource.builder()
                        .label("docs")
                        .sourceType(DocumentSourceDescriptor.SourceType.DIRECTORY)
                        .pathOrUrl("/data/docs")
                        .build()))
                .graphExtraction(GraphExtractionConfig.builder().enabled(true).build())
                .vectorIndex(VectorIndexConfig.builder().enabled(true).build())
                .build();

        ResponseEntity<?> response = controller.startJob(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("job-1", body.get("jobId"));
        assertEquals("PENDING", body.get("status"));
        assertEquals(1, body.get("sourceCount"));
        assertEquals(true, body.get("graphExtractionEnabled"));
        assertEquals(true, body.get("vectorIndexEnabled"));
    }

    @Test
    @DisplayName("POST /start with empty sources returns 400")
    void startJob_emptySourcesReturns400() {
        when(unifiedCrawlService.startJob(any()))
                .thenThrow(new IllegalArgumentException("At least one source is required"));

        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("empty")
                .sources(List.of())
                .build();

        ResponseEntity<?> response = controller.startJob(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.get("error").toString().contains("At least one source"));
    }

    @Test
    @DisplayName("POST /start with null graph/vector configs shows disabled in response")
    void startJob_nullConfigsShowsDisabled() {
        UnifiedCrawlJob mockJob = buildMockJob("job-2", UnifiedCrawlJob.Status.PENDING);
        when(unifiedCrawlService.startJob(any())).thenReturn(mockJob);

        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("no configs")
                .sources(List.of(UnifiedCrawlSource.builder()
                        .label("f").sourceType(DocumentSourceDescriptor.SourceType.FILE).pathOrUrl("/f").build()))
                .build();

        ResponseEntity<?> response = controller.startJob(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(false, body.get("graphExtractionEnabled"));
        assertEquals(false, body.get("vectorIndexEnabled"));
    }

    @Test
    @DisplayName("POST /start when service throws RuntimeException returns 500")
    void startJob_serviceError() {
        when(unifiedCrawlService.startJob(any()))
                .thenThrow(new RuntimeException("Internal error"));

        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("failing")
                .sources(List.of(UnifiedCrawlSource.builder()
                        .label("f").sourceType(DocumentSourceDescriptor.SourceType.FILE).pathOrUrl("/f").build()))
                .build();

        ResponseEntity<?> response = controller.startJob(request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. LIST JOBS
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /jobs returns all jobs with summaries")
    void listJobs_returnsSummaries() {
        UnifiedCrawlJob j1 = buildMockJob("j1", UnifiedCrawlJob.Status.COMPLETED);
        j1.getEntitiesExtracted().set(10);
        j1.getRelationshipsExtracted().set(5);
        j1.setRequest(UnifiedCrawlRequest.builder().name("Job A")
                .sources(List.of(UnifiedCrawlSource.builder().label("a").build())).build());

        UnifiedCrawlJob j2 = buildMockJob("j2", UnifiedCrawlJob.Status.RUNNING);
        j2.setRequest(UnifiedCrawlRequest.builder().name("Job B")
                .sources(List.of(
                        UnifiedCrawlSource.builder().label("b1").build(),
                        UnifiedCrawlSource.builder().label("b2").build())).build());

        when(unifiedCrawlService.getAllJobs()).thenReturn(List.of(j1, j2));

        ResponseEntity<List<Map<String, Object>>> response = controller.listJobs();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("j1", response.getBody().get(0).get("jobId"));
        assertEquals("COMPLETED", response.getBody().get(0).get("status"));
        assertEquals(10, response.getBody().get(0).get("entitiesExtracted"));
        assertEquals(1, response.getBody().get(0).get("sourceCount"));
        assertEquals("j2", response.getBody().get(1).get("jobId"));
        assertEquals(2, response.getBody().get(1).get("sourceCount"));
    }

    @Test
    @DisplayName("GET /jobs when empty returns empty list")
    void listJobs_empty() {
        when(unifiedCrawlService.getAllJobs()).thenReturn(List.of());

        ResponseEntity<List<Map<String, Object>>> response = controller.listJobs();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isEmpty());
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. LIST ACTIVE JOBS
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /jobs/active returns only PENDING/RUNNING jobs")
    void listActiveJobs_filtersTerminal() {
        UnifiedCrawlJob active = buildMockJob("active-1", UnifiedCrawlJob.Status.RUNNING);
        active.setRequest(UnifiedCrawlRequest.builder().name("Active")
                .sources(List.of(UnifiedCrawlSource.builder().label("s").build())).build());

        when(unifiedCrawlService.getActiveJobs()).thenReturn(List.of(active));

        ResponseEntity<List<Map<String, Object>>> response = controller.listActiveJobs();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("RUNNING", response.getBody().get(0).get("status"));
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. GET JOB DETAIL
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /jobs/{id} for completed job includes graph summary")
    void getJob_completedWithGraph() {
        UnifiedCrawlJob job = buildMockJob("detail-1", UnifiedCrawlJob.Status.COMPLETED);
        job.getDocumentsLoaded().set(3);
        job.getEntitiesExtracted().set(5);
        job.getRelationshipsExtracted().set(4);
        job.getDocumentsIndexed().set(3);
        job.setStartedAt(Instant.now().minusSeconds(60));
        job.setCompletedAt(Instant.now());
        job.setRequest(UnifiedCrawlRequest.builder().name("Detail Test")
                .sources(List.of(UnifiedCrawlSource.builder().label("s").build())).build());

        // Build a result graph with typed entities
        Graph graph = new Graph();
        graph.setEntities(List.of(
                buildEntity("Alice", "PERSON"),
                buildEntity("Bob", "PERSON"),
                buildEntity("Google", "ORGANIZATION"),
                buildEntity("TensorFlow", "TECHNOLOGY"),
                buildEntity("Berlin", "LOCATION")
        ));
        graph.setRelationships(List.of(
                buildRelationship("WORKS_AT"),
                buildRelationship("USES"),
                buildRelationship("LOCATED_IN"),
                buildRelationship("DEPENDS_ON")
        ));
        job.setResultGraph(graph);

        job.setSourceProgress(List.of(
                UnifiedCrawlJob.SourceProgress.builder()
                        .label("docs").sourceType("DIRECTORY").pathOrUrl("/data/docs")
                        .status(UnifiedCrawlJob.Status.COMPLETED)
                        .documentsLoaded(3).entitiesExtracted(5).relationshipsExtracted(4)
                        .build()
        ));

        when(unifiedCrawlService.getJob("detail-1")).thenReturn(Optional.of(job));

        ResponseEntity<?> response = controller.getJob("detail-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("detail-1", body.get("jobId"));
        assertEquals("Detail Test", body.get("name"));
        assertEquals("COMPLETED", body.get("status"));
        assertEquals(3, body.get("documentsLoaded"));
        assertEquals(5, body.get("entitiesExtracted"));
        assertEquals(4, body.get("relationshipsExtracted"));
        assertEquals(3, body.get("documentsIndexed"));
        assertNotNull(body.get("createdAt"));
        assertNotNull(body.get("startedAt"));
        assertNotNull(body.get("completedAt"));

        // Graph summary
        @SuppressWarnings("unchecked")
        Map<String, Object> graphSummary = (Map<String, Object>) body.get("graph");
        assertNotNull(graphSummary);
        assertEquals(5L, graphSummary.get("entityCount"));
        assertEquals(4L, graphSummary.get("relationshipCount"));

        // Entity type counts
        @SuppressWarnings("unchecked")
        Map<String, Long> typeCounts = (Map<String, Long>) graphSummary.get("entityTypeCounts");
        assertNotNull(typeCounts);
        assertEquals(2L, typeCounts.get("PERSON"));
        assertEquals(1L, typeCounts.get("ORGANIZATION"));
        assertEquals(1L, typeCounts.get("TECHNOLOGY"));
        assertEquals(1L, typeCounts.get("LOCATION"));

        // Source progress
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) body.get("sources");
        assertEquals(1, sources.size());
        assertEquals("docs", sources.get(0).get("label"));
        assertEquals("DIRECTORY", sources.get(0).get("sourceType"));
        assertEquals("COMPLETED", sources.get(0).get("status"));
    }

    @Test
    @DisplayName("GET /jobs/{id} for unknown ID returns 404")
    void getJob_unknownReturns404() {
        when(unifiedCrawlService.getJob("unknown")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getJob("unknown");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("GET /jobs/{id} for running job shows no graph section")
    void getJob_runningNoGraph() {
        UnifiedCrawlJob job = buildMockJob("running-1", UnifiedCrawlJob.Status.RUNNING);
        job.getDocumentsLoaded().set(2);
        job.setRequest(UnifiedCrawlRequest.builder().name("Running")
                .sources(List.of(UnifiedCrawlSource.builder().label("s").build())).build());
        job.setSourceProgress(List.of());

        when(unifiedCrawlService.getJob("running-1")).thenReturn(Optional.of(job));

        ResponseEntity<?> response = controller.getJob("running-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("RUNNING", body.get("status"));
        // resultGraph is null → no "graph" key
        assertFalse(body.containsKey("graph"));
    }

    @Test
    @DisplayName("GET /jobs/{id} for failed job shows error message")
    void getJob_failedShowsError() {
        UnifiedCrawlJob job = buildMockJob("failed-1", UnifiedCrawlJob.Status.FAILED);
        job.setErrorMessage("Out of memory");
        job.setRequest(UnifiedCrawlRequest.builder().name("Failed")
                .sources(List.of(UnifiedCrawlSource.builder().label("s").build())).build());
        job.setSourceProgress(List.of());

        when(unifiedCrawlService.getJob("failed-1")).thenReturn(Optional.of(job));

        ResponseEntity<?> response = controller.getJob("failed-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("FAILED", body.get("status"));
        assertEquals("Out of memory", body.get("errorMessage"));
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. CANCEL JOB
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /jobs/{id}/cancel on running job returns 200")
    void cancelJob_success() {
        when(unifiedCrawlService.cancelJob("j-cancel")).thenReturn(true);

        ResponseEntity<?> response = controller.cancelJob("j-cancel");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Job cancelled", body.get("message"));
        assertEquals("j-cancel", body.get("jobId"));
    }

    @Test
    @DisplayName("POST /jobs/{id}/cancel on finished job returns 400")
    void cancelJob_finishedReturns400() {
        when(unifiedCrawlService.cancelJob("j-done")).thenReturn(false);

        ResponseEntity<?> response = controller.cancelJob("j-done");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. CLEANUP
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /jobs/cleanup returns count of removed jobs")
    void cleanupJobs_returnsCount() {
        when(unifiedCrawlService.cleanupJobs()).thenReturn(3);

        ResponseEntity<?> response = controller.cleanupJobs();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(3, body.get("removed"));
    }

    // ──────────────────────────────────────────────────────────────────
    // 7. SOURCE TYPES
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /source-types returns available source types list")
    void getSourceTypes_returnsAll() {
        List<UnifiedCrawlService.AvailableSourceType> types = List.of(
                new UnifiedCrawlService.AvailableSourceType("DIRECTORY", "Local Directory",
                        "Crawl local dir", true, List.of("pathOrUrl"), List.of("maxDepth")),
                new UnifiedCrawlService.AvailableSourceType("EMAIL", "Email",
                        "Crawl email", false, List.of("pathOrUrl"), List.of("host"))
        );
        when(unifiedCrawlService.getAvailableSourceTypes()).thenReturn(types);

        ResponseEntity<List<UnifiedCrawlService.AvailableSourceType>> response = controller.getSourceTypes();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("DIRECTORY", response.getBody().get(0).type());
        assertTrue(response.getBody().get(0).available());
        assertEquals("EMAIL", response.getBody().get(1).type());
        assertFalse(response.getBody().get(1).available());
    }

    // ──────────────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────────────

    private static UnifiedCrawlJob buildMockJob(String jobId, UnifiedCrawlJob.Status status) {
        return UnifiedCrawlJob.builder()
                .jobId(jobId)
                .status(new AtomicReference<>(status))
                .createdAt(Instant.now())
                .documentsDiscovered(new AtomicInteger(0))
                .documentsLoaded(new AtomicInteger(0))
                .chunksProcessed(new AtomicInteger(0))
                .documentsIndexed(new AtomicInteger(0))
                .entitiesExtracted(new AtomicInteger(0))
                .relationshipsExtracted(new AtomicInteger(0))
                .errorCount(new AtomicInteger(0))
                .sourceProgress(new ArrayList<>())
                .build();
    }

    private static Entity buildEntity(String title, String type) {
        Entity e = new Entity();
        e.setTitle(title);
        e.setType(type);
        return e;
    }

    private static Relationship buildRelationship(String type) {
        Relationship r = new Relationship();
        r.setType(type);
        return r;
    }
}
