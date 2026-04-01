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

import ai.kompile.app.services.DocumentIngestService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.services.YouTubeTranscriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Component
public class DocumentIngestionTool {

    private static final Logger logger = LoggerFactory.getLogger(DocumentIngestionTool.class);

    private final DocumentIngestService documentIngestService;
    private final IngestProgressTracker progressTracker;
    private final YouTubeTranscriptService youTubeTranscriptService;

    @Autowired
    public DocumentIngestionTool(
            @Autowired(required = false) DocumentIngestService documentIngestService,
            @Autowired(required = false) IngestProgressTracker progressTracker,
            @Autowired(required = false) YouTubeTranscriptService youTubeTranscriptService) {
        this.documentIngestService = documentIngestService;
        this.progressTracker = progressTracker;
        this.youTubeTranscriptService = youTubeTranscriptService;
        logger.info("DocumentIngestionTool initialized");
    }

    public record AddTextSourceInput(String text, String fileName, String chunkerName, Long factSheetId) {}
    public record GetIngestStatusInput(String taskId) {}
    public record CancelIngestTaskInput(String taskId) {}
    public record GetIngestDiagnosticsInput() {}
    public record AddYouTubeSourceInput(String url, String language) {}

    @Tool(name = "add_text_source",
            description = "Ingests raw text as a document source. Provide text content, a fileName for identification, " +
                    "optional chunkerName (e.g. 'recursive', 'sentence'), and optional factSheetId.")
    public Map<String, Object> addTextSource(AddTextSourceInput input) {
        try {
            if (documentIngestService == null) return Map.of("status", "error", "error", "DocumentIngestService not available");
            if (input.text() == null || input.text().isBlank()) return Map.of("status", "error", "error", "text is required");
            String fileName = input.fileName() != null ? input.fileName() : "text-input-" + System.currentTimeMillis() + ".txt";

            Path tempFile = Files.createTempFile("ingest-", "-" + fileName);
            Files.writeString(tempFile, input.text());

            String taskId = progressTracker != null ? progressTracker.generateTaskId() : UUID.randomUUID().toString();
            if (progressTracker != null) {
                progressTracker.startTask(taskId, fileName, input.factSheetId());
            }

            documentIngestService.processDocumentAsync(taskId, tempFile, null, input.chunkerName());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("taskId", taskId);
            result.put("fileName", fileName);
            result.put("message", "Text ingestion started");
            return result;
        } catch (Exception e) {
            logger.error("Error adding text source: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_ingest_status",
            description = "Gets the status of a document ingestion task by taskId. Returns phase, progress, and statistics.")
    public Map<String, Object> getIngestStatus(GetIngestStatusInput input) {
        try {
            if (progressTracker == null) return Map.of("status", "error", "error", "IngestProgressTracker not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "taskId is required");
            var status = progressTracker.getTaskStatus(input.taskId());
            if (status == null) return Map.of("status", "error", "error", "Task not found: " + input.taskId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("task", status);
            return result;
        } catch (Exception e) {
            logger.error("Error getting ingest status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "cancel_ingest_task",
            description = "Cancels a running ingestion task by taskId.")
    public Map<String, Object> cancelIngestTask(CancelIngestTaskInput input) {
        try {
            if (documentIngestService == null) return Map.of("status", "error", "error", "DocumentIngestService not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "taskId is required");
            boolean cancelled = documentIngestService.cancelTask(input.taskId());
            return Map.of("status", "success", "cancelled", cancelled, "taskId", input.taskId());
        } catch (Exception e) {
            logger.error("Error cancelling ingest task: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_ingest_diagnostics",
            description = "Gets diagnostics about active ingestion pipelines including thread usage and pipeline state.")
    public Map<String, Object> getIngestDiagnostics(GetIngestDiagnosticsInput input) {
        try {
            if (documentIngestService == null) return Map.of("status", "error", "error", "DocumentIngestService not available");
            var diag = documentIngestService.getActivePipelineDiagnostics();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(diag);
            return result;
        } catch (Exception e) {
            logger.error("Error getting ingest diagnostics: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "add_youtube_source",
            description = "Fetches a YouTube video transcript and ingests it as a document. Provide the video URL or ID, and optional language code.")
    public Map<String, Object> addYouTubeSource(AddYouTubeSourceInput input) {
        try {
            if (youTubeTranscriptService == null) return Map.of("status", "error", "error", "YouTubeTranscriptService not available");
            if (documentIngestService == null) return Map.of("status", "error", "error", "DocumentIngestService not available");
            if (input.url() == null || input.url().isBlank()) return Map.of("status", "error", "error", "url is required");

            String videoId = youTubeTranscriptService.extractVideoId(input.url());
            String lang = input.language() != null ? input.language() : "en";
            var transcript = youTubeTranscriptService.fetchTranscript(input.url(), lang);

            String text = transcript.getTranscript();
            Path tempFile = Files.createTempFile("youtube-", "-" + videoId + ".txt");
            Files.writeString(tempFile, text);

            String taskId = progressTracker != null ? progressTracker.generateTaskId() : UUID.randomUUID().toString();
            if (progressTracker != null) {
                progressTracker.startTask(taskId, "youtube-" + videoId + ".txt");
            }
            documentIngestService.processDocumentAsync(taskId, tempFile, null, null);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("taskId", taskId);
            result.put("videoId", videoId);
            result.put("message", "YouTube transcript ingestion started");
            return result;
        } catch (Exception e) {
            logger.error("Error adding YouTube source: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
