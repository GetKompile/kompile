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

package ai.kompile.app.tools;

import ai.kompile.app.services.CrossIndexTrackingService;
import ai.kompile.app.services.IndexSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CrossIndexTool {

    private static final Logger logger = LoggerFactory.getLogger(CrossIndexTool.class);

    private final CrossIndexTrackingService crossIndexTrackingService;
    private final IndexSyncService indexSyncService;

    @Autowired
    public CrossIndexTool(
            @Autowired(required = false) CrossIndexTrackingService crossIndexTrackingService,
            @Autowired(required = false) IndexSyncService indexSyncService) {
        this.crossIndexTrackingService = crossIndexTrackingService;
        this.indexSyncService = indexSyncService;
        logger.info("CrossIndexTool initialized");
    }

    public record GetDocumentStatusInput(String sourceId, Long factSheetId) {}
    public record TriggerSyncInput(Long factSheetId) {}
    public record GetSyncJobStatusInput(String jobId) {}

    @Tool(name = "get_cross_index_document_status",
            description = "Gets the cross-index status for a specific document by sourceId and factSheetId. " +
                    "Shows keyword index, vector store, and graph index status.")
    public Map<String, Object> getDocumentStatus(GetDocumentStatusInput input) {
        try {
            if (crossIndexTrackingService == null) return Map.of("status", "error", "error", "CrossIndexTrackingService not available");
            if (input.sourceId() == null || input.factSheetId() == null)
                return Map.of("status", "error", "error", "sourceId and factSheetId are required");
            var doc = crossIndexTrackingService.findDocumentBySourceId(input.sourceId(), input.factSheetId());
            if (doc.isEmpty()) return Map.of("status", "error", "error", "Document not found");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("document", doc.get());
            return result;
        } catch (Exception e) {
            logger.error("Error getting document status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "trigger_index_sync",
            description = "Triggers a manual index synchronization for a fact sheet. Ensures keyword and vector indexes are consistent.")
    public Map<String, Object> triggerSync(TriggerSyncInput input) {
        try {
            if (indexSyncService == null) return Map.of("status", "error", "error", "IndexSyncService not available");
            if (input.factSheetId() == null) return Map.of("status", "error", "error", "factSheetId is required");
            var syncFuture = indexSyncService.syncAll(input.factSheetId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Sync triggered for fact sheet " + input.factSheetId());
            return result;
        } catch (Exception e) {
            logger.error("Error triggering sync: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_sync_job_status",
            description = "Gets the status of an index synchronization job by its job ID.")
    public Map<String, Object> getSyncJobStatus(GetSyncJobStatusInput input) {
        try {
            if (indexSyncService == null) return Map.of("status", "error", "error", "IndexSyncService not available");
            if (input.jobId() == null) return Map.of("status", "error", "error", "jobId is required");
            var jobStatus = indexSyncService.getJobStatus(input.jobId());
            if (jobStatus.isEmpty()) return Map.of("status", "error", "error", "Job not found: " + input.jobId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("job", jobStatus.get());
            return result;
        } catch (Exception e) {
            logger.error("Error getting sync job status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
