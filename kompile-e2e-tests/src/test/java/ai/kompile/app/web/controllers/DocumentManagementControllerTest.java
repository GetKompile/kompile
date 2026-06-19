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
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

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
    private IndexerService indexerService;

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
                List.of(documentLoader),
                List.of(textChunker),
                null   // indexerService list
        );

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
                appDocumentSourceProperties, List.of(), List.of(), null);
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
                appDocumentSourceProperties, List.of(), List.of(), null);
        MockMvc emptyMvc = MockMvcBuilders.standaloneSetup(controller).build();

        emptyMvc.perform(get("/api/documents/chunkers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void processingStatus_returnsStatus() throws Exception {
        mockMvc.perform(get("/api/documents/processing-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexerAvailable").exists())
                .andExpect(jsonPath("$.availableLoaders").value(1))
                .andExpect(jsonPath("$.availableChunkers").value(1));
    }

    @Test
    void processingModes_returnsAvailableModes() throws Exception {
        mockMvc.perform(get("/api/documents/processing-modes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modes").isArray())
                .andExpect(jsonPath("$.default").value("auto"));
    }
}
