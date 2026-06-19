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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourceViewerControllerTest {

    private SourceViewerController controller;

    @BeforeEach
    void setUp() {
        // All dependencies are optional — pass null to use defaults
        controller = new SourceViewerController(null, null, null, null);
    }

    // ─── listSources ──────────────────────────────────────────────────────────

    @Test
    void listSources_noUploadsDir_returnsEmptyList() {
        ResponseEntity<SourceViewerController.SourceListResponse> resp = controller.listSources(100, 0);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SourceViewerController.SourceListResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.sources()).isNotNull();
    }

    // ─── getSourceByChecksum ──────────────────────────────────────────────────

    @Test
    void getSourceByChecksum_nonExistent_returnsNotFound() {
        ResponseEntity<?> resp = controller.getSourceByChecksum("nonexistentchecksum", false);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── getSourceByFileName ──────────────────────────────────────────────────

    @Test
    void getSourceByFileName_nonExistent_returnsNotFound() {
        ResponseEntity<?> resp = controller.getSourceByFileName("nonexistent_file_xyz.txt", false);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── getTextContent ───────────────────────────────────────────────────────

    @Test
    void getTextContent_nonExistent_returnsNotFound() {
        ResponseEntity<SourceViewerController.TextContentResponse> resp =
                controller.getTextContent("nonexistent_file_xyz.txt", 10000, "UTF-8");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── getSupportedTypes ────────────────────────────────────────────────────

    @Test
    void getSupportedTypes_returnsTypeCategories() {
        ResponseEntity<Map<String, Object>> resp = controller.getSupportedTypes();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsKey("textExtensions");
        assertThat(body).containsKey("imageExtensions");
        assertThat(body).containsKey("viewableExtensions");
        assertThat(body).containsKey("allSupported");
    }

    // ─── getSourceInfo ────────────────────────────────────────────────────────

    @Test
    void getSourceInfo_nonExistent_returnsNotFound() {
        ResponseEntity<SourceViewerController.SourceInfo> resp =
                controller.getSourceInfo("nonexistent_file_xyz.txt");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
