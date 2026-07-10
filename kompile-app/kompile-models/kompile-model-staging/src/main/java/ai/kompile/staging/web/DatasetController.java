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
import ai.kompile.staging.web.dto.BenchmarkInfo;
import ai.kompile.staging.web.dto.DatasetDownloadStatus;
import ai.kompile.staging.web.dto.DatasetInfo;
import ai.kompile.staging.web.dto.DatasetStats;
import ai.kompile.staging.web.dto.EvalSuitePresetInfo;
import ai.kompile.staging.web.dto.PreloadDatasetRequest;
import ai.kompile.staging.web.dto.StandardDatasetInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for dataset management.
 * Provides endpoints for uploading, listing, previewing, and deleting datasets.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/datasets")
@CrossOrigin(origins = "*")
public class DatasetController {

    private static final Logger log = LoggerFactory.getLogger(DatasetController.class);

    private final DatasetService datasetService;
    private final StandardDatasetCatalog standardDatasetCatalog;

    public DatasetController(DatasetService datasetService,
                             StandardDatasetCatalog standardDatasetCatalog) {
        this.datasetService = datasetService;
        this.standardDatasetCatalog = standardDatasetCatalog;
    }

    // ==================== Standard Catalog ====================

    /**
     * List standard datasets available for one-click import.
     */
    @GetMapping("/catalog")
    public ResponseEntity<List<StandardDatasetInfo>> listStandardCatalog(
            @RequestParam(required = false) String category) {
        try {
            return ResponseEntity.ok(standardDatasetCatalog.listByCategory(category));
        } catch (Exception e) {
            log.error("Failed to list standard dataset catalog", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * List standard dataset categories.
     */
    @GetMapping("/catalog/categories")
    public ResponseEntity<List<String>> listStandardCatalogCategories() {
        try {
            return ResponseEntity.ok(standardDatasetCatalog.listCategories());
        } catch (Exception e) {
            log.error("Failed to list standard dataset catalog categories", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * List curated evaluation suite presets.
     */
    @GetMapping("/catalog/presets")
    public ResponseEntity<List<EvalSuitePresetInfo>> listStandardCatalogPresets() {
        try {
            return ResponseEntity.ok(standardDatasetCatalog.listPresets());
        } catch (Exception e) {
            log.error("Failed to list standard dataset catalog presets", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get a standard dataset catalog entry by ID.
     */
    @GetMapping("/catalog/{id}")
    public ResponseEntity<StandardDatasetInfo> getStandardCatalogDataset(@PathVariable String id) {
        try {
            StandardDatasetInfo info = standardDatasetCatalog.get(id);
            if (info == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            log.error("Failed to get standard dataset catalog entry: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Start importing a standard dataset into managed dataset storage.
     */
    @PostMapping("/catalog/{id}/download")
    public ResponseEntity<DatasetDownloadStatus> startStandardDatasetDownload(@PathVariable String id) {
        try {
            DatasetDownloadStatus status = standardDatasetCatalog.startAsyncDownload(id, datasetService);
            if ("FAILED".equals(status.getPhase())) {
                return ResponseEntity.badRequest().body(status);
            }
            if ("COMPLETED".equals(status.getPhase())) {
                return ResponseEntity.ok(status);
            }
            return ResponseEntity.accepted().body(status);
        } catch (Exception e) {
            log.error("Failed to start standard dataset download: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get current import status for a standard dataset.
     */
    @GetMapping("/catalog/{id}/download")
    public ResponseEntity<DatasetDownloadStatus> getStandardDatasetDownloadStatus(@PathVariable String id) {
        try {
            DatasetDownloadStatus status = standardDatasetCatalog.getDownloadStatus(id);
            if (status == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to get standard dataset download status: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * List active standard dataset imports.
     */
    @GetMapping("/catalog/downloads")
    public ResponseEntity<Map<String, DatasetDownloadStatus>> listStandardDatasetDownloadStatuses() {
        try {
            return ResponseEntity.ok(standardDatasetCatalog.getAllDownloadStatuses());
        } catch (Exception e) {
            log.error("Failed to list standard dataset download statuses", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Upload ====================

    /**
     * Upload a dataset file with metadata.
     */
    @PostMapping("/upload")
    public ResponseEntity<DatasetInfo> uploadDataset(
            @RequestParam("file") MultipartFile file,
            @RequestParam String name,
            @RequestParam String format,
            @RequestParam String task,
            @RequestParam(required = false) String inputColumn,
            @RequestParam(required = false) String outputColumn,
            @RequestParam(required = false) String chosenColumn,
            @RequestParam(required = false) String rejectedColumn,
            @RequestParam(required = false, defaultValue = "0.9") double trainSplit) {
        try {
            log.info("Uploading dataset: name={}, format={}, task={}", name, format, task);
            DatasetInfo info = datasetService.uploadDataset(name, format, task,
                    inputColumn, outputColumn, chosenColumn, rejectedColumn, trainSplit, file);
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            log.error("Failed to upload dataset: {}", name, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== List ====================

    /**
     * List all available datasets.
     */
    @GetMapping
    public ResponseEntity<List<DatasetInfo>> listDatasets() {
        try {
            return ResponseEntity.ok(datasetService.listDatasets());
        } catch (Exception e) {
            log.error("Failed to list datasets", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Get ====================

    /**
     * Get details for a specific dataset.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DatasetInfo> getDataset(@PathVariable String id) {
        try {
            DatasetInfo info = datasetService.getDataset(id);
            if (info == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            log.error("Failed to get dataset: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Delete ====================

    /**
     * Delete a dataset by ID.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteDataset(@PathVariable String id) {
        try {
            log.info("Deleting dataset: {}", id);
            datasetService.deleteDataset(id);
            return ResponseEntity.ok(Map.of("status", "deleted", "datasetId", id));
        } catch (Exception e) {
            log.error("Failed to delete dataset: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Preview ====================

    /**
     * Preview rows from a dataset.
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<List<Map<String, Object>>> preview(
            @PathVariable String id,
            @RequestParam(defaultValue = "10") int rows) {
        try {
            return ResponseEntity.ok(datasetService.previewDataset(id, rows));
        } catch (Exception e) {
            log.error("Failed to preview dataset: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Stats ====================

    /**
     * Compute statistics for a dataset.
     */
    @GetMapping("/{id}/stats")
    public ResponseEntity<DatasetStats> computeStats(@PathVariable String id) {
        try {
            return ResponseEntity.ok(datasetService.computeStats(id));
        } catch (Exception e) {
            log.error("Failed to compute stats for dataset: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Preload ====================

    /**
     * Preload a dataset from a built-in benchmark or HuggingFace repository.
     */
    @PostMapping("/preload")
    public ResponseEntity<DatasetInfo> preloadDataset(@RequestBody PreloadDatasetRequest request) {
        try {
            log.info("Preloading dataset: source={}, benchmark={}, hfRepo={}",
                    request.getSource(), request.getBenchmarkName(), request.getHuggingfaceRepo());
            DatasetInfo info = datasetService.preloadDataset(request);
            return ResponseEntity.ok(info);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid preload request", e);
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to preload dataset", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Available Benchmarks ====================

    /**
     * List benchmark datasets available for preloading.
     */
    @GetMapping("/available-benchmarks")
    public ResponseEntity<List<BenchmarkInfo>> getAvailableBenchmarks() {
        try {
            return ResponseEntity.ok(datasetService.listAvailableBenchmarks());
        } catch (Exception e) {
            log.error("Failed to list available benchmarks", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
