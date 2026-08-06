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
 *  limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Durable completed-chunk checkpoint store for graph extraction.
 *
 * <p>This is deliberately separate from {@link DocumentHashStore}: document hashes are file-level
 * incremental crawl state, while this store is chunk-level progress inside the graph-extraction phase.
 * A chunk is marked complete only after its semantic graph batch is persisted, so a restarted crawl can
 * skip already-written chunks without trusting transient in-memory job progress.</p>
 */
@Slf4j
@Component
class GraphExtractionCheckpointStore {

    private static final String DATA_GRAPH_SUBDIR = "data/graph";
    private static final String CHECKPOINT_FILE_NAME = "graph-extraction-checkpoints.json";
    private static final TypeReference<Map<String, CheckpointEntry>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    Set<String> completedChunkKeys(Long factSheetId, GraphExtractionConfig config) {
        String fingerprint = configFingerprint(config);
        return loadStore(factSheetId).entrySet().stream()
                .filter(e -> fingerprint.equals(e.getValue().getConfigFingerprint()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    String chunkKey(RetrievedDoc doc) {
        if (doc == null) {
            return null;
        }
        Map<String, Object> metadata = doc.getMetadata() != null ? doc.getMetadata() : Map.of();
        List<String> parts = new ArrayList<>();
        String source = firstString(metadata,
                GraphConstants.META_SOURCE_PATH,
                GraphConstants.META_SOURCE,
                GraphConstants.META_SOURCE_ID,
                "sourcePath",
                "path",
                "fileName");
        String chunk = firstString(metadata,
                "chunk_id",
                "chunkId",
                "chunk_index",
                "chunkIndex",
                "chunk_start",
                "chunkStart",
                "page",
                "sheetName");
        parts.add("source=" + source);
        parts.add("chunk=" + chunk);
        if (source == null && chunk == null && doc.getId() != null && !doc.getId().isBlank()) {
            parts.add("id=" + doc.getId());
        }
        parts.add("text=" + DocumentHashStore.sha256Hex(doc.getText()));
        return DocumentHashStore.sha256Hex(String.join("\n", parts));
    }

    void recordCompletedBatch(Long factSheetId,
                              GraphExtractionConfig config,
                              Collection<RetrievedDoc> docs,
                              String crawlRunId,
                              int entities,
                              int relationships) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        synchronized (this) {
            try {
                Path file = checkpointFilePath(factSheetId);
                Files.createDirectories(file.getParent());
                Map<String, CheckpointEntry> store = loadStore(factSheetId);
                String fingerprint = configFingerprint(config);
                String now = Instant.now().toString();
                for (RetrievedDoc doc : docs) {
                    String key = chunkKey(doc);
                    if (key == null) {
                        continue;
                    }
                    Map<String, Object> metadata = doc.getMetadata() != null ? doc.getMetadata() : Map.of();
                    store.put(key, CheckpointEntry.builder()
                            .configFingerprint(fingerprint)
                            .source(firstString(metadata,
                                    GraphConstants.META_SOURCE_PATH,
                                    GraphConstants.META_SOURCE,
                                    GraphConstants.META_SOURCE_ID,
                                    "sourcePath",
                                    "path",
                                    "fileName"))
                            .documentId(doc.getId())
                            .textHash(DocumentHashStore.sha256Hex(doc.getText()))
                            .lastCrawlRunId(crawlRunId)
                            .lastCompletedAt(now)
                            .entities(entities)
                            .relationships(relationships)
                            .build());
                }
                writeStoreAtomic(file, store);
            } catch (Exception e) {
                log.warn("Failed to persist graph extraction checkpoint for factSheet={}: {}",
                        factSheetId, e.getMessage());
            }
        }
    }

    synchronized void clearFactSheet(Long factSheetId) {
        Path file = checkpointFilePath(factSheetId);
        try {
            if (Files.deleteIfExists(file)) {
                log.info("Cleared graph extraction checkpoints for factSheet={} at {}", factSheetId, file);
            } else {
                log.debug("No graph extraction checkpoints to clear for factSheet={} at {}", factSheetId, file);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to clear graph extraction checkpoints for factSheet=" + factSheetId + " at " + file, e);
        }
    }

    String configFingerprint(GraphExtractionConfig config) {
        try {
            Map<String, Object> stable = new TreeMap<>();
            if (config != null) {
                stable.put("schemaPresetId", config.getSchemaPresetId());
                stable.put("standardizedSchema", config.getStandardizedSchema());
                stable.put("validationPolicy", config.getValidationPolicy());
                stable.put("entityTypes", config.getEntityTypes());
                stable.put("relationshipTypes", config.getRelationshipTypes());
                stable.put("llmProvider", config.getLlmProvider());
                stable.put("modelName", config.getModelName());
                stable.put("temperature", config.getTemperature());
                stable.put("maxTokens", config.getMaxTokens());
                stable.put("customPromptHash", DocumentHashStore.sha256Hex(config.getCustomPrompt()));
                stable.put("schemaMode", config.getSchemaMode() != null ? config.getSchemaMode().name() : null);
                stable.put("minConfidence", config.getMinConfidence());
                stable.put("resolvedGraphAdditionCalibration", config.getResolvedGraphAdditionCalibration());
            }
            return DocumentHashStore.sha256Hex(mapper.writeValueAsString(stable));
        } catch (Exception e) {
            return DocumentHashStore.sha256Hex(String.valueOf(config));
        }
    }

    private synchronized Map<String, CheckpointEntry> loadStore(Long factSheetId) {
        Path file = checkpointFilePath(factSheetId);
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(file.toFile(), MAP_TYPE);
        } catch (Exception e) {
            log.debug("Could not read graph extraction checkpoint store at {}: {} — treating as empty",
                    file, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private Path checkpointFilePath(Long factSheetId) {
        String scope = factSheetId != null ? String.valueOf(factSheetId) : "global";
        return KompileHome.resolvedProjectDirectory().toPath()
                .resolve(DATA_GRAPH_SUBDIR)
                .resolve(scope)
                .resolve(CHECKPOINT_FILE_NAME);
    }

    private void writeStoreAtomic(Path target, Map<String, CheckpointEntry> store) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = parent != null
                ? Files.createTempFile(parent, ".gec-", ".tmp")
                : Files.createTempFile(".gec-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), store);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static String firstString(Map<String, Object> metadata, String... keys) {
        if (metadata == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null) {
                String s = String.valueOf(value);
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CheckpointEntry {
        private String configFingerprint;
        private String source;
        private String documentId;
        private String textHash;
        private String lastCrawlRunId;
        private String lastCompletedAt;
        private int entities;
        private int relationships;
    }
}
