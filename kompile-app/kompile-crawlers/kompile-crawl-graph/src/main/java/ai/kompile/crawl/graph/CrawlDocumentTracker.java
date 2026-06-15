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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Tracks per-document progress and provides document key/name utilities.
 * Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 */
@Component
class CrawlDocumentTracker {

    private static final int MAX_RECENT_EVENTS = 120;

    // Pre-allocated key arrays for stringMeta — avoids varargs String[] allocation per call
    static final String[] META_KEYS_SOURCE_TYPE = {GraphConstants.META_SOURCE_TYPE, "source_type"};
    static final String[] META_KEYS_CONTENT_TYPE = {GraphConstants.META_CONTENT_TYPE, "content_type", GraphConstants.META_DOCUMENT_TYPE};
    static final String[] META_KEYS_CONTENT_TYPE_SHORT = {GraphConstants.META_CONTENT_TYPE, GraphConstants.META_DOCUMENT_TYPE};
    static final String[] META_KEYS_LOADER = {GraphConstants.META_LOADER, "loader_name"};
    static final String[] META_KEYS_SOURCE_PATH = {GraphConstants.META_SOURCE_PATH, GraphConstants.META_SOURCE, "source_url", "path"};
    static final String[] META_KEYS_FILE_NAME = {"source_filename", GraphConstants.META_FILE_NAME, "file_name", "title"};
    static final String[] META_KEYS_GRAPH_SOURCE_PATH = {GraphConstants.META_SOURCE_PATH, GraphConstants.META_SOURCE,
            "source_path", "sourcePath", "source_url", "documentPath", "path"};

    static final String[] SOURCE_DOC_ID_KEYS = {"sourceDocumentId", "source_document_id", "documentId", "document_id"};

    void recordEvent(UnifiedCrawlJob job, String phase, String level, String message, String details) {
        if (job == null) return;
        List<UnifiedCrawlJob.StageEvent> events = job.getRecentEvents();
        UnifiedCrawlJob.StageEvent event = UnifiedCrawlJob.StageEvent.builder()
                .timestamp(Instant.now())
                .phase(phase)
                .level(level)
                .message(message)
                .details(details)
                .progressPercent(job.getProgressPercent().get())
                .build();
        events.add(event);
        // Bulk-trim instead of per-element remove(0) — CopyOnWriteArrayList.remove(0)
        // copies the entire backing array on each call, making the old while-loop O(N²).
        // Trimming 20% below cap in one shot amortizes the cost.
        int size = events.size();
        if (size > MAX_RECENT_EVENTS + 20) {
            int trimTo = MAX_RECENT_EVENTS;
            try {
                events.subList(0, size - trimTo).clear();
            } catch (Exception ignored) {
                // Concurrent modification — safe to skip, next caller will trim
            }
        }
    }

    void recordDocumentProgress(UnifiedCrawlJob job,
                                Document doc,
                                String phase,
                                String status,
                                int chunksDelta,
                                int entitiesDelta,
                                int relationshipsDelta,
                                String message,
                                String errorMessage,
                                List<String> extractors,
                                boolean publishEvent) {
        Map<String, Object> metadata = doc != null ? doc.getMetadata() : null;
        String id = doc != null ? doc.getId() : null;
        String sourcePath = documentSourcePath(metadata, id);
        recordDocumentProgress(job,
                documentKeyFromSourcePath(sourcePath, id),
                documentFileNameWithSourcePath(metadata, id, sourcePath),
                sourcePath,
                stringMeta(metadata, META_KEYS_SOURCE_TYPE),
                stringMeta(metadata, META_KEYS_CONTENT_TYPE),
                stringMeta(metadata, META_KEYS_LOADER),
                phase, status, chunksDelta, entitiesDelta, relationshipsDelta,
                message, errorMessage, extractors, publishEvent);
    }

    void recordDocumentProgress(UnifiedCrawlJob job,
                                RetrievedDoc doc,
                                String phase,
                                String status,
                                int chunksDelta,
                                int entitiesDelta,
                                int relationshipsDelta,
                                String message,
                                String errorMessage,
                                List<String> extractors,
                                boolean publishEvent) {
        Map<String, Object> metadata = doc != null ? doc.getMetadata() : null;
        String id = doc != null ? doc.getId() : null;
        String sourcePath = documentSourcePath(metadata, id);
        recordDocumentProgress(job,
                documentKeyFromSourcePath(sourcePath, id),
                documentFileNameWithSourcePath(metadata, id, sourcePath),
                sourcePath,
                stringMeta(metadata, META_KEYS_SOURCE_TYPE),
                stringMeta(metadata, META_KEYS_CONTENT_TYPE),
                stringMeta(metadata, META_KEYS_LOADER),
                phase, status, chunksDelta, entitiesDelta, relationshipsDelta,
                message, errorMessage, extractors, publishEvent);
    }

    void recordDocumentProgress(UnifiedCrawlJob job,
                                String documentKey,
                                String fileName,
                                String sourcePath,
                                String sourceType,
                                String contentType,
                                String loaderName,
                                String phase,
                                String status,
                                int chunksDelta,
                                int entitiesDelta,
                                int relationshipsDelta,
                                String message,
                                String errorMessage,
                                List<String> extractors,
                                boolean publishEvent) {
        if (job == null || documentKey == null || documentKey.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        UnifiedCrawlJob.DocumentProgress progress = job.getDocumentProgress().computeIfAbsent(documentKey, key ->
                UnifiedCrawlJob.DocumentProgress.builder()
                        .documentKey(key)
                        .fileName(fileName != null ? fileName : key)
                        .sourcePath(sourcePath)
                        .sourceType(sourceType)
                        .contentType(contentType)
                        .loaderName(loaderName)
                        .phase(phase)
                        .status(status)
                        .startedAt(now)
                        .updatedAt(now)
                        .build());

        synchronized (progress) {
            boolean samePhaseStatusRegression = isTerminalDocumentStatus(progress.getStatus())
                    && Objects.equals(progress.getPhase(), phase)
                    && !isTerminalDocumentStatus(status);
            if (samePhaseStatusRegression) {
                progress.setUpdatedAt(now);
                return;
            }
            if (fileName != null && !fileName.isBlank()) progress.setFileName(fileName);
            if (sourcePath != null && !sourcePath.isBlank()) progress.setSourcePath(sourcePath);
            if (sourceType != null && !sourceType.isBlank()) progress.setSourceType(sourceType);
            if (contentType != null && !contentType.isBlank()) progress.setContentType(contentType);
            if (loaderName != null && !loaderName.isBlank()) progress.setLoaderName(loaderName);
            if (phase != null && !phase.isBlank()) progress.setPhase(phase);
            if (status != null && !status.isBlank()) progress.setStatus(status);
            if (message != null && !message.isBlank()) progress.setMessage(message);
            if (errorMessage != null && !errorMessage.isBlank()) progress.setErrorMessage(errorMessage);
            progress.setChunksCreated(progress.getChunksCreated() + Math.max(0, chunksDelta));
            progress.setEntitiesExtracted(progress.getEntitiesExtracted() + Math.max(0, entitiesDelta));
            progress.setRelationshipsExtracted(progress.getRelationshipsExtracted() + Math.max(0, relationshipsDelta));
            progress.setGraphNodesCreated(progress.getGraphNodesCreated() + Math.max(0, entitiesDelta));
            progress.setGraphEdgesCreated(progress.getGraphEdgesCreated() + Math.max(0, relationshipsDelta));
            if (extractors != null && !extractors.isEmpty()) {
                List<String> existing = progress.getExtractors();
                LinkedHashSet<String> merged = new LinkedHashSet<>(existing != null ? existing : List.of());
                for (String extractor : extractors) {
                    if (extractor != null && !extractor.isBlank()) {
                        merged.add(extractor);
                    }
                }
                progress.setExtractors(new ArrayList<>(merged));
            }
            if (progress.getStartedAt() == null) progress.setStartedAt(now);
            progress.setUpdatedAt(now);
            if (isTerminalDocumentStatus(status)) {
                progress.setCompletedAt(now);
            }
        }

        if (publishEvent) {
            String displayName = progress.getFileName() != null ? progress.getFileName() : documentKey;
            String details = (entitiesDelta > 0 || relationshipsDelta > 0)
                    ? entitiesDelta + " entities, " + relationshipsDelta + " relationships"
                    : message;
            recordEvent(job, phase != null ? phase : "DOCUMENT", errorMessage != null ? "ERROR" : "INFO",
                    displayName + ": " + (message != null ? message : status), details);
        }
    }

    void recordDocumentVectorProgress(UnifiedCrawlJob job,
                                      Document doc,
                                      String status,
                                      int embeddedDelta,
                                      int indexedDelta,
                                      String message,
                                      String errorMessage,
                                      boolean publishEvent) {
        if (job == null || doc == null) {
            return;
        }
        Map<String, Object> metadata = doc.getMetadata();
        String id = doc.getId();
        String sourcePath = documentSourcePath(metadata, id);
        String docKey = documentKeyFromSourcePath(sourcePath, id);
        if (docKey == null || docKey.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        UnifiedCrawlJob.DocumentProgress progress = job.getDocumentProgress().computeIfAbsent(docKey, key ->
                UnifiedCrawlJob.DocumentProgress.builder()
                        .documentKey(key)
                        .fileName(documentFileNameWithSourcePath(metadata, id, sourcePath))
                        .sourcePath(sourcePath)
                        .sourceType(stringMeta(metadata, META_KEYS_SOURCE_TYPE))
                        .contentType(stringMeta(metadata, META_KEYS_CONTENT_TYPE_SHORT))
                        .loaderName(stringMeta(metadata, META_KEYS_LOADER))
                        .phase("VECTOR_INDEXING")
                        .status(status)
                        .startedAt(now)
                        .updatedAt(now)
                        .build());

        synchronized (progress) {
            progress.setPhase("VECTOR_INDEXING");
            progress.setStatus(status);
            progress.setUpdatedAt(now);
            if (progress.getStartedAt() == null) progress.setStartedAt(now);
            if (message != null && !message.isBlank()) progress.setMessage(message);
            if (errorMessage != null && !errorMessage.isBlank()) progress.setErrorMessage(errorMessage);
            progress.setChunksEmbedded(progress.getChunksEmbedded() + Math.max(0, embeddedDelta));
            progress.setChunksIndexed(progress.getChunksIndexed() + Math.max(0, indexedDelta));
            if (isTerminalDocumentStatus(status)) {
                progress.setCompletedAt(now);
            }
        }

        if (publishEvent) {
            String displayName = progress.getFileName() != null ? progress.getFileName() : docKey;
            recordEvent(job, "VECTOR_INDEXING", errorMessage != null ? "ERROR" : "INFO",
                    displayName + ": " + (message != null ? message : status),
                    "embedded=" + embeddedDelta + ", indexed=" + indexedDelta);
        }
    }

    boolean isTerminalDocumentStatus(String status) {
        if (status == null) return false;
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED", "FAILED", "SKIPPED", "CANCELLED", "LOADED" -> true;
            default -> false;
        };
    }

    String documentKey(Document doc) {
        if (doc == null) return null;
        return documentKey(doc.getMetadata(), doc.getId());
    }

    String documentKey(RetrievedDoc doc) {
        if (doc == null) return null;
        return documentKey(doc.getMetadata(), doc.getId());
    }

    String documentKey(Map<String, Object> metadata, String fallbackId) {
        String sourcePath = documentSourcePath(metadata, fallbackId);
        if (sourcePath != null && !sourcePath.isBlank()) return sourcePath;
        return fallbackId;
    }

    String documentKeyFromSourcePath(String sourcePath, String fallbackId) {
        if (sourcePath != null && !sourcePath.isBlank()) return sourcePath;
        return fallbackId;
    }

    String documentFileNameWithSourcePath(Map<String, Object> metadata, String fallbackId, String sourcePath) {
        String fileName = stringMeta(metadata, META_KEYS_FILE_NAME);
        if (fileName != null && !fileName.isBlank()) return fileName;
        if (sourcePath == null) return fallbackId;
        return shortName(sourcePath);
    }

    String documentSourcePath(Map<String, Object> metadata, String fallbackId) {
        String sourcePath = stringMeta(metadata, META_KEYS_SOURCE_PATH);
        return sourcePath != null && !sourcePath.isBlank() ? sourcePath : fallbackId;
    }

    String documentFileName(Map<String, Object> metadata, String fallbackId) {
        String fileName = stringMeta(metadata, META_KEYS_FILE_NAME);
        if (fileName != null && !fileName.isBlank()) return fileName;
        String sourcePath = documentSourcePath(metadata, fallbackId);
        if (sourcePath == null) return fallbackId;
        return shortName(sourcePath);
    }

    static String shortName(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        if (slash < 0) slash = path.lastIndexOf('\\');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    String stringMeta(Map<String, Object> metadata, String... keys) {
        if (metadata == null || keys == null) return null;
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    String sourceDocumentId(Map<String, Object> metadata) {
        if (metadata == null) return null;
        for (String key : SOURCE_DOC_ID_KEYS) {
            Object value = metadata.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
