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

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.domain.IndexedDocument.OverallIndexStatus;
import ai.kompile.app.ingest.domain.IndexedPassage;
import ai.kompile.app.services.CrossIndexTrackingService;
import ai.kompile.app.services.CrossIndexTrackingService.CrossIndexStatistics;
import ai.kompile.app.services.CrossIndexTrackingService.CrossIndexSummary;
import ai.kompile.app.services.IndexSyncService;
import ai.kompile.app.services.IndexSyncService.AutoSyncConfig;
import ai.kompile.app.services.IndexSyncService.SyncResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.web.config.SpringDataJacksonConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossIndexControllerTest {

    @Mock
    private CrossIndexTrackingService trackingService;

    @Mock
    private IndexSyncService syncService;

    @Mock
    private FactSheetService factSheetService;

    private CrossIndexController controller;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new SpringDataJacksonConfiguration.PageModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private FactSheet activeSheet;
    private CrossIndexStatistics sampleStats;
    private CrossIndexSummary sampleSummary;

    @BeforeEach
    void setUp() {
        controller = new CrossIndexController(trackingService, syncService, factSheetService);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();

        activeSheet = FactSheet.builder().id(1L).name("Default Sheet").build();

        // CrossIndexStatistics: totalDocuments, fullyIndexed, partial, notIndexed, failed,
        //                        totalPassages, vectorIndexed, keywordIndexed, graphIndexed
        sampleStats = new CrossIndexStatistics(10L, 8L, 1L, 1L, 0L, 50L, 40L, 30L, 20L);
        // CrossIndexSummary: factSheetId, factSheetName, docsNeedingSync, passagesMissingVector,
        //                    passagesMissingGraph, lastSyncCheck, autoSyncEnabled
        sampleSummary = new CrossIndexSummary(1L, "Default Sheet", 2, 5, 3, Instant.now(), true);

        when(factSheetService.getActiveSheet()).thenReturn(activeSheet);
        when(trackingService.getStatistics(anyLong())).thenReturn(sampleStats);
        when(trackingService.getSummary(anyLong(), anyString())).thenReturn(sampleSummary);
        when(factSheetService.getSheetById(1L)).thenReturn(Optional.of(activeSheet));
        when(factSheetService.getSheetById(999L)).thenReturn(Optional.empty());
    }

    @Test
    void getStatus_withActiveSheet_returnsStatusResponse() throws Exception {
        mockMvc.perform(get("/api/cross-index/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factSheetId").value(1));
    }

    @Test
    void getStatus_withNoActiveSheet_returnsNullFactSheetId() throws Exception {
        when(factSheetService.getActiveSheet()).thenReturn(null);

        mockMvc.perform(get("/api/cross-index/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factSheetId").isEmpty());
    }

    @Test
    void getStatusByFactSheet_found_returnsStatus() throws Exception {
        mockMvc.perform(get("/api/cross-index/status/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factSheetId").value(1));
    }

    @Test
    void getStatusByFactSheet_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/cross-index/status/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStatistics_returnsStatsForFactSheet() throws Exception {
        when(trackingService.getStatusDistribution(1L)).thenReturn(Map.of());

        mockMvc.perform(get("/api/cross-index/statistics/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factSheetId").value(1));
    }

    @Test
    void listDocuments_returnsPage() throws Exception {
        IndexedDocument doc = new IndexedDocument();
        doc.setId(1L);
        doc.setSourceId("source-1");
        doc.setFileName("test.pdf");
        doc.setFactId(1L);
        doc.setOverallStatus(OverallIndexStatus.FULLY_INDEXED);
        doc.setKeywordIndexStatus(IndexedDocument.IndexStatus.INDEXED);
        doc.setVectorStoreStatus(IndexedDocument.IndexStatus.INDEXED);
        doc.setGraphStatus(IndexedDocument.IndexStatus.NOT_INDEXED);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        when(trackingService.listDocuments(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(doc)));

        mockMvc.perform(get("/api/cross-index/documents").param("factSheetId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void getDocument_found_returnsDetail() throws Exception {
        IndexedDocument doc = new IndexedDocument();
        doc.setId(1L);
        doc.setSourceId("source-1");
        doc.setFileName("test.pdf");
        doc.setFactId(1L);
        doc.setOverallStatus(OverallIndexStatus.FULLY_INDEXED);
        doc.setKeywordIndexStatus(IndexedDocument.IndexStatus.INDEXED);
        doc.setVectorStoreStatus(IndexedDocument.IndexStatus.INDEXED);
        doc.setGraphStatus(IndexedDocument.IndexStatus.NOT_INDEXED);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        when(trackingService.findDocument(1L)).thenReturn(Optional.of(doc));
        when(trackingService.listPassagesOrdered(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/cross-index/documents/1"))
                .andExpect(status().isOk());
    }

    @Test
    void getDocument_notFound_returns404() throws Exception {
        when(trackingService.findDocument(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/cross-index/documents/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getDocumentsNeedingSync_returnsPage() throws Exception {
        when(trackingService.findDocumentsNeedingSync(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/cross-index/documents/needing-sync").param("factSheetId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void listPassages_returnsPage() throws Exception {
        when(trackingService.listPassages(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/cross-index/passages").param("documentId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void checkPassageStatus_returnsStatusMap() throws Exception {
        when(trackingService.checkVectorIndexStatus(anyList())).thenReturn(Map.of("chunk-1", true));
        when(trackingService.findPassage("chunk-1")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/cross-index/passages/check-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of("chunk-1"))))
                .andExpect(status().isOk());
    }

    @Test
    void syncToVectorStore_async_returnsAccepted() throws Exception {
        CompletableFuture<SyncResult> future = new CompletableFuture<>();
        when(syncService.syncToVectorStore(anyLong())).thenReturn(future);

        Map<String, Object> body = Map.of("factSheetId", 1L, "async", true);

        mockMvc.perform(post("/api/cross-index/sync/vector-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void syncToKnowledgeGraph_async_returnsAccepted() throws Exception {
        CompletableFuture<SyncResult> future = new CompletableFuture<>();
        when(syncService.syncToKnowledgeGraph(anyLong())).thenReturn(future);

        Map<String, Object> body = Map.of("factSheetId", 1L, "async", true);

        mockMvc.perform(post("/api/cross-index/sync/knowledge-graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());
    }

    @Test
    void syncAll_async_returnsAccepted() throws Exception {
        CompletableFuture<SyncResult> future = new CompletableFuture<>();
        when(syncService.syncAll(anyLong())).thenReturn(future);

        Map<String, Object> body = Map.of("factSheetId", 1L, "async", true);

        mockMvc.perform(post("/api/cross-index/sync/all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());
    }

    @Test
    void getSyncJobStatus_found_returnsStatus() throws Exception {
        IndexSyncService.SyncJobStatus jobStatus = new IndexSyncService.SyncJobStatus(
                "job-1", IndexSyncService.SyncStatus.RUNNING, 5, 10, 20, 50, 25, List.of(),
                Instant.now(), null);
        when(syncService.getJobStatus("job-1")).thenReturn(Optional.of(jobStatus));

        mockMvc.perform(get("/api/cross-index/sync/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"));
    }

    @Test
    void getSyncJobStatus_notFound_returns404() throws Exception {
        when(syncService.getJobStatus("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/cross-index/sync/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelSyncJob_found_returnsNoContent() throws Exception {
        when(syncService.cancelJob("job-1")).thenReturn(true);

        mockMvc.perform(delete("/api/cross-index/sync/job-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancelSyncJob_notFound_returns404() throws Exception {
        when(syncService.cancelJob("missing")).thenReturn(false);

        mockMvc.perform(delete("/api/cross-index/sync/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getActiveSyncJobs_returnsJobList() throws Exception {
        when(syncService.getActiveJobs()).thenReturn(List.of());

        mockMvc.perform(get("/api/cross-index/sync/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void markDocumentStale_withNoFreshnessService_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/cross-index/documents/1/mark-stale"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void scanFreshness_withNoFreshnessService_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/cross-index/fact-sheets/1/scan-freshness"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getAutoSyncConfig_returnsConfig() throws Exception {
        AutoSyncConfig config = new AutoSyncConfig(true, 100, Duration.ofSeconds(60), true, true);
        when(syncService.getAutoSyncConfig(anyLong())).thenReturn(config);

        mockMvc.perform(get("/api/cross-index/config/auto-sync").param("factSheetId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void updateAutoSyncConfig_updatesAndReturnsConfig() throws Exception {
        AutoSyncConfig config = new AutoSyncConfig(false, 50, Duration.ofSeconds(30), false, true);
        when(syncService.getAutoSyncConfig(anyLong())).thenReturn(config);
        doNothing().when(syncService).updateAutoSyncConfig(anyLong(), any());

        Map<String, Object> body = Map.of(
                "factSheetId", 1,
                "enabled", false,
                "maxPassagesPerSync", 50,
                "syncTimeoutSeconds", 30,
                "syncOnSearch", false,
                "syncOnIngest", true
        );

        mockMvc.perform(put("/api/cross-index/config/auto-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").exists());
    }
}
