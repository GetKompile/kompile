/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.persistence;

import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTable;
import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTableIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists and restores {@link EmbeddingTable} artifacts for a given fact sheet.
 *
 * <p>Tables under the {@link #SIZE_THRESHOLD_BYTES 50 MB threshold} are written as JSON to
 * {@code <dataDir>/data/graph/reasoning/<factSheetId>/embedding-table.json}.
 * Larger tables are skipped with a WARN log — callers should route them to the staging
 * backend via {@link ModelArtifactRouter} with a {@link ModelArtifactType#SAMEDIFF_CHECKPOINT}
 * or equivalent large-artifact path.</p>
 */
@Slf4j
@Component
public class EmbeddingTablePersistenceAdapter {

    private static final long SIZE_THRESHOLD_BYTES = 50L * 1024 * 1024; // 50 MB

    @Value("${kompile.data.dir:}")
    private String dataDir;

    /**
     * Serialize {@code table} and write it to
     * {@code <reasoningDir>/<factSheetId>/embedding-table.json}, unless the JSON representation
     * exceeds the 50 MB size threshold.
     *
     * @param factSheetId the fact sheet identifier
     * @param table       the embedding table to persist
     * @throws IOException if the file cannot be written
     */
    public void persist(long factSheetId, EmbeddingTable table) throws IOException {
        String json = EmbeddingTableIO.toJson(table);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > SIZE_THRESHOLD_BYTES) {
            log.warn("EmbeddingTablePersistenceAdapter: Embedding table for factSheet {} exceeds 50 MB threshold; "
                    + "skipping file persistence. Wire model-staging for large tables.", factSheetId);
            return;
        }
        Path target = reasoningDir(factSheetId).resolve("embedding-table.json");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        log.debug("EmbeddingTablePersistenceAdapter: persisted embedding table for factSheet {} ({} bytes) → {}",
                factSheetId, bytes.length, target);
    }

    /**
     * Load the embedding table for {@code factSheetId} if a persisted file exists.
     *
     * @param factSheetId the fact sheet identifier
     * @return the restored {@link EmbeddingTable}, or {@link Optional#empty()} if not found
     * @throws IOException if the file exists but cannot be read or parsed
     */
    public Optional<EmbeddingTable> load(long factSheetId) throws IOException {
        Path source = reasoningDir(factSheetId).resolve("embedding-table.json");
        if (!Files.exists(source)) {
            log.debug("EmbeddingTablePersistenceAdapter: no embedding table file for factSheet {}", factSheetId);
            return Optional.empty();
        }
        String json = Files.readString(source, StandardCharsets.UTF_8);
        EmbeddingTable table = EmbeddingTableIO.fromJson(json);
        log.debug("EmbeddingTablePersistenceAdapter: loaded embedding table for factSheet {} from {}",
                factSheetId, source);
        return Optional.of(table);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path resolveBase() {
        return (dataDir == null || dataDir.isBlank())
                ? Path.of(System.getProperty("user.home"), ".kompile")
                : Path.of(dataDir);
    }

    private Path reasoningDir(long factSheetId) {
        return resolveBase().resolve("data").resolve("graph").resolve("reasoning")
                .resolve(String.valueOf(factSheetId));
    }
}
