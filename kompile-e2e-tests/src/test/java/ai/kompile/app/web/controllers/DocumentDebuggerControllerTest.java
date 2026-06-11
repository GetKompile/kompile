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
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentDebuggerControllerTest {

    @Mock
    private AppDocumentSourceProperties appDocumentSourceProperties;

    @Mock
    private DocumentLoader documentLoader;

    @Mock
    private TextChunker textChunker;

    private DocumentDebuggerController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Use a non-existent path that is deterministic for tests
        when(appDocumentSourceProperties.getUploadsPath()).thenReturn("/tmp/test-kompile-uploads-debug");
        when(documentLoader.getName()).thenReturn("test-loader");
        when(textChunker.getName()).thenReturn("test-chunker");

        controller = new DocumentDebuggerController(
                appDocumentSourceProperties,
                List.of(documentLoader),
                List.of(textChunker)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getDebugStatus_returnsStatusInfo() throws Exception {
        mockMvc.perform(get("/api/documents/debug/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadsPathConfigured").exists())
                .andExpect(jsonPath("$.totalLoaders").value(1))
                .andExpect(jsonPath("$.totalChunkers").value(1));
    }

    @Test
    void getDebugStatus_withNoLoadersOrChunkers_returnsZeroCounts() throws Exception {
        when(appDocumentSourceProperties.getUploadsPath()).thenReturn("/tmp/test-debug-empty");
        DocumentDebuggerController emptyController = new DocumentDebuggerController(
                appDocumentSourceProperties, List.of(), List.of());
        MockMvc emptyMvc = MockMvcBuilders.standaloneSetup(emptyController).build();

        emptyMvc.perform(get("/api/documents/debug/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLoaders").value(0))
                .andExpect(jsonPath("$.totalChunkers").value(0));
    }

    @Test
    void analyzeFile_withUnconfiguredUploadsPath_returns500() throws Exception {
        when(appDocumentSourceProperties.getUploadsPath()).thenReturn(null);
        DocumentDebuggerController unconfiguredController = new DocumentDebuggerController(
                appDocumentSourceProperties, List.of(documentLoader), List.of(textChunker));
        MockMvc unconfiguredMvc = MockMvcBuilders.standaloneSetup(unconfiguredController).build();

        unconfiguredMvc.perform(multipart("/api/documents/debug/analyze-file")
                        .param("fileName", "test.pdf"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void analyzeFile_withNonExistentFile_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents/debug/analyze-file")
                        .param("fileName", "nonexistent-file.pdf"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void analyzeFile_withPathTraversal_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents/debug/analyze-file")
                        .param("fileName", "../../../etc/passwd"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testUpload_withUnconfiguredPath_returns500() throws Exception {
        when(appDocumentSourceProperties.getUploadsPath()).thenReturn(null);
        DocumentDebuggerController unconfiguredController = new DocumentDebuggerController(
                appDocumentSourceProperties, List.of(documentLoader), List.of(textChunker));
        MockMvc unconfiguredMvc = MockMvcBuilders.standaloneSetup(unconfiguredController).build();

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "PDF content".getBytes());

        unconfiguredMvc.perform(multipart("/api/documents/debug/test-upload")
                        .file(file))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void testUpload_withEmptyFile_returnsBadRequest() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/documents/debug/test-upload")
                        .file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void testRetrievedDoc_withUnconfiguredPath_returns500() throws Exception {
        when(appDocumentSourceProperties.getUploadsPath()).thenReturn(null);
        DocumentDebuggerController unconfiguredController = new DocumentDebuggerController(
                appDocumentSourceProperties, List.of(documentLoader), List.of(textChunker));
        MockMvc unconfiguredMvc = MockMvcBuilders.standaloneSetup(unconfiguredController).build();

        unconfiguredMvc.perform(post("/api/documents/debug/test-retrieved-doc")
                        .param("fileName", "test.pdf"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void testRetrievedDoc_withNonExistentFile_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents/debug/test-retrieved-doc")
                        .param("fileName", "nonexistent.pdf"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void compareDocTypes_withUnconfiguredPath_returns500() throws Exception {
        when(appDocumentSourceProperties.getUploadsPath()).thenReturn(null);
        DocumentDebuggerController unconfiguredController = new DocumentDebuggerController(
                appDocumentSourceProperties, List.of(documentLoader), List.of(textChunker));
        MockMvc unconfiguredMvc = MockMvcBuilders.standaloneSetup(unconfiguredController).build();

        unconfiguredMvc.perform(post("/api/documents/debug/compare-doc-types")
                        .param("fileName", "test.pdf"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void compareDocTypes_withNonExistentFile_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents/debug/compare-doc-types")
                        .param("fileName", "nonexistent.pdf"))
                .andExpect(status().isBadRequest());
    }
}
