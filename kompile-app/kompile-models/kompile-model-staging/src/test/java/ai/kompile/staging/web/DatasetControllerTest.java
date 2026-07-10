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

package ai.kompile.staging.web;

import ai.kompile.staging.training.DatasetService;
import ai.kompile.staging.training.StandardDatasetCatalog;
import ai.kompile.staging.web.dto.DatasetDownloadStatus;
import ai.kompile.staging.web.dto.EvalSuitePresetInfo;
import ai.kompile.staging.web.dto.StandardDatasetInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasetControllerTest {

    @Mock
    private DatasetService datasetService;

    @Mock
    private StandardDatasetCatalog standardDatasetCatalog;

    private DatasetController controller;

    @BeforeEach
    void setUp() {
        controller = new DatasetController(datasetService, standardDatasetCatalog);
    }

    @Test
    void listStandardCatalogDelegatesCategoryFilter() {
        List<StandardDatasetInfo> expected = List.of(StandardDatasetInfo.builder()
                .id("arc_challenge")
                .category("reasoning")
                .build());
        when(standardDatasetCatalog.listByCategory("reasoning")).thenReturn(expected);

        ResponseEntity<List<StandardDatasetInfo>> response = controller.listStandardCatalog("reasoning");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
    }

    @Test
    void listStandardCatalogPresetsReturnsCatalogPresets() {
        List<EvalSuitePresetInfo> expected = List.of(EvalSuitePresetInfo.builder()
                .id("quick")
                .benchmarks(List.of("arc_easy"))
                .build());
        when(standardDatasetCatalog.listPresets()).thenReturn(expected);

        ResponseEntity<List<EvalSuitePresetInfo>> response = controller.listStandardCatalogPresets();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
    }

    @Test
    void startStandardDatasetDownloadReturnsAcceptedForQueuedDownload() {
        DatasetDownloadStatus queued = DatasetDownloadStatus.builder()
                .datasetId("arc_easy")
                .phase("QUEUED")
                .progressPercent(0)
                .build();
        when(standardDatasetCatalog.startAsyncDownload("arc_easy", datasetService)).thenReturn(queued);

        ResponseEntity<DatasetDownloadStatus> response = controller.startStandardDatasetDownload("arc_easy");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertSame(queued, response.getBody());
    }

    @Test
    void startStandardDatasetDownloadReturnsBadRequestForFailedLookup() {
        DatasetDownloadStatus failed = DatasetDownloadStatus.builder()
                .datasetId("missing")
                .phase("FAILED")
                .error("Dataset not found")
                .build();
        when(standardDatasetCatalog.startAsyncDownload("missing", datasetService)).thenReturn(failed);

        ResponseEntity<DatasetDownloadStatus> response = controller.startStandardDatasetDownload("missing");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertSame(failed, response.getBody());
    }

    @Test
    void getStandardDatasetDownloadStatusReturnsNotFoundWhenUntracked() {
        when(standardDatasetCatalog.getDownloadStatus("arc_easy")).thenReturn(null);

        ResponseEntity<DatasetDownloadStatus> response = controller.getStandardDatasetDownloadStatus("arc_easy");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void listStandardDatasetDownloadStatusesReturnsActiveStatuses() {
        DatasetDownloadStatus status = DatasetDownloadStatus.builder()
                .datasetId("arc_easy")
                .phase("DOWNLOADING")
                .progressPercent(25)
                .build();
        Map<String, DatasetDownloadStatus> expected = Map.of("arc_easy", status);
        when(standardDatasetCatalog.getAllDownloadStatuses()).thenReturn(expected);

        ResponseEntity<Map<String, DatasetDownloadStatus>> response = controller.listStandardDatasetDownloadStatuses();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
    }
}
