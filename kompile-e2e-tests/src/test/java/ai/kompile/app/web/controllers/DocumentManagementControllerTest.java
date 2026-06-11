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

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.services.DocumentIngestService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.services.VectorStorePopulationService;
import ai.kompile.app.services.YouTubeTranscriptService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentLoadingService;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentManagementControllerTest {

    @Mock
    private AppDocumentSourceProperties appDocumentSourceProperties;

    @Mock
    private DocumentLoader documentLoader;

    @Mock
    private TextChunker textChunker;

    @Mock
    private DocumentIngestService documentIngestService;

    @Mock
    private IngestProgressTracker progressTracker;

    @Mock
    private VectorStorePopulationService vectorStorePopulationService;

    @Mock
    private YouTubeTranscriptService youTubeTranscriptService;

    @Mock
    private DocumentLoadingService documentLoadingService;

    private DocumentManagementController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        when(appDocumentSourceProperties.getUploadsPath()).thenReturn("/tmp/test-doc-management-uploads");
        when(documentLoader.getName()).thenReturn("test-loader");
        when(textChunker.getName()).thenReturn("recursive-character");

        // All indexer services are null (no IndexerService available)
        controller = new DocumentManagementController(
                appDocumentSourceProperties,
                null,                          // restTemplate
                List.of(documentLoader),       // documentLoaders
                documentLoadingService,        // documentLoadingService
                List.of(textChunker),          // textChunkers
                null,                          // indexerService list
                documentIngestService,         // documentIngestService
                progressTracker,               // progressTracker
                vectorStorePopulationService,  // vectorStorePopulationService
                youTubeTranscriptService,      // youTubeTranscriptService
                null,                          // sourceDocumentStorageService
                null,                          // factSheetService
                null                           // jobHistoryRepository
        );

        // Manually call @PostConstruct - not called in unit tests
        // The constructor handles the uploads path setup

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getDocumentsApiInfo_returnsApiOverview() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Document Management API"))
                .andExpect(jsonPath("$.version").value("1.0"))
                .andExpect(jsonPath("$.endpoints").exists())
                .andExpect(jsonPath("$.loadersAvailable").value(1))
                .andExpect(jsonPath("$.chunkersAvailable").value(1));
    }

    @Test
    void listAvailableLoaders_returnsLoaderList() throws Exception {
        mockMvc.perform(get("/api/documents/loaders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("test-loader"));
    }

    @Test
    void listAvailableLoaders_withNoLoaders_returnsEmptyList() throws Exception {
        controller = new DocumentManagementController(
                appDocumentSourceProperties, null, List.of(), documentLoadingService,
                List.of(), null, null, null, null, null, null, null, null);
        MockMvc emptyMvc = MockMvcBuilders.standaloneSetup(controller).build();

        emptyMvc.perform(get("/api/documents/loaders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listAvailableChunkers_returnsChunkerList() throws Exception {
        mockMvc.perform(get("/api/documents/chunkers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("recursive-character"));
    }

    @Test
    void listAvailableChunkers_withNoChunkers_returnsEmptyList() throws Exception {
        controller = new DocumentManagementController(
                appDocumentSourceProperties, null, List.of(), documentLoadingService,
                List.of(), null, null, null, null, null, null, null, null);
        MockMvc emptyMvc = MockMvcBuilders.standaloneSetup(controller).build();

        emptyMvc.perform(get("/api/documents/chunkers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void debugChunkerResolution_returnsDebugInfo() throws Exception {
        mockMvc.perform(get("/api/documents/debug/chunker-resolution")
                        .param("name", "recursive-character"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedName").value("recursive-character"))
                .andExpect(jsonPath("$.availableChunkers").isArray())
                .andExpect(jsonPath("$.chunkerCount").value(1));
    }

    @Test
    void debugChunkerResolution_withNoName_returnsDebugInfoWithoutResolution() throws Exception {
        mockMvc.perform(get("/api/documents/debug/chunker-resolution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedName").doesNotExist())
                .andExpect(jsonPath("$.availableChunkers").isArray());
    }

    @Test
    void debugIngestChunkerResolution_withNoIngestService_returnsError() throws Exception {
        controller = new DocumentManagementController(
                appDocumentSourceProperties, null, List.of(documentLoader), documentLoadingService,
                List.of(textChunker), null, null, null, null, null, null, null, null);
        MockMvc noSvcMvc = MockMvcBuilders.standaloneSetup(controller).build();

        noSvcMvc.perform(get("/api/documents/debug/ingest-chunker-resolution")
                        .param("name", "recursive-character"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void addDocumentPath_withNullPath_returnsBadRequest() throws Exception {
        Map<String, Object> body = Map.of();  // missing "path"

        mockMvc.perform(post("/api/documents/add-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void addDocumentPath_withBlankPath_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents/add-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addDocumentPath_withNonExistentPath_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents/add-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/nonexistent/path/that/does/not/exist\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
