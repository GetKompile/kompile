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

package ai.kompile.core.crawl.graph.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads and writes archived pipeline-step artifacts on disk: chunked documents as line-delimited JSON
 * ({@code chunks.jsonl}) and a step's configuration as JSON ({@code step-config.json}).
 *
 * <p>Mirrors the JSONL approach already used by {@code IngestCheckpointService} but operates on Spring
 * AI {@link Document}s via {@link ArchivedDocumentDto}. It is a plain, dependency-light class — the
 * caller supplies a configured {@link ObjectMapper} (typically {@code JsonUtils.standardMapper()}).</p>
 *
 * <p>Writes are atomic (temp file + {@code ATOMIC_MOVE}) so a crash mid-write never leaves a partially
 * written file that would be silently read back as a truncated chunk set.</p>
 */
@Slf4j
public class DocumentArchiveStore {

    public static final String CHUNKS_FILE = "chunks.jsonl";
    public static final String STEP_CONFIG_FILE = "step-config.json";

    private final ObjectMapper mapper;

    public DocumentArchiveStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Write all documents to {@code stepDir/chunks.jsonl}, atomically replacing any existing file.
     * Media / empty documents are skipped (chunk archives are text only).
     *
     * @return the number of documents actually written
     */
    public int writeChunks(Path stepDir, List<Document> docs) throws IOException {
        Files.createDirectories(stepDir);
        Path target = stepDir.resolve(CHUNKS_FILE);
        Path tmp = stepDir.resolve(CHUNKS_FILE + ".tmp");
        int written = 0;
        try (BufferedWriter w = Files.newBufferedWriter(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Document doc : docs) {
                if (doc == null || doc.getText() == null || doc.getText().isEmpty()) {
                    continue;
                }
                w.write(mapper.writeValueAsString(ArchivedDocumentDto.of(doc)));
                w.newLine();
                written++;
            }
        }
        atomicMove(tmp, target);
        return written;
    }

    /**
     * Read {@code stepDir/chunks.jsonl} back into Spring AI {@link Document}s. Corrupt lines are skipped
     * with a warning rather than failing the whole load. Returns an empty list if the file is absent.
     */
    public List<Document> readChunks(Path stepDir) throws IOException {
        Path file = stepDir.resolve(CHUNKS_FILE);
        List<Document> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        try (Stream<String> lines = Files.lines(file)) {
            lines.forEach(line -> {
                if (line == null || line.isBlank()) {
                    return;
                }
                try {
                    ArchivedDocumentDto dto = mapper.readValue(line, ArchivedDocumentDto.class);
                    if (dto.getText() != null && !dto.getText().isEmpty()) {
                        out.add(dto.toDocument());
                    }
                } catch (Exception e) {
                    log.warn("Skipping corrupt archived chunk line in {}: {}", file, e.getMessage());
                }
            });
        }
        return out;
    }

    /** Count the chunk lines without materializing the documents. */
    public int countChunks(Path stepDir) throws IOException {
        Path file = stepDir.resolve(CHUNKS_FILE);
        if (!Files.exists(file)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(file)) {
            return (int) lines.filter(l -> l != null && !l.isBlank()).count();
        }
    }

    /** Serialize a step config object to {@code stepDir/step-config.json} (atomic). */
    public void writeStepConfig(Path stepDir, Object config) throws IOException {
        Files.createDirectories(stepDir);
        Path target = stepDir.resolve(STEP_CONFIG_FILE);
        Path tmp = stepDir.resolve(STEP_CONFIG_FILE + ".tmp");
        String json = (config == null) ? "null" : mapper.writeValueAsString(config);
        Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        atomicMove(tmp, target);
    }

    /** Read the raw step-config JSON, or {@code null} if absent. */
    public String readStepConfigJson(Path stepDir) throws IOException {
        Path file = stepDir.resolve(STEP_CONFIG_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        return Files.readString(file);
    }

    /** Read and deserialize the step config to the given type, or {@code null} if absent/empty. */
    public <T> T readStepConfig(Path stepDir, Class<T> type) throws IOException {
        String json = readStepConfigJson(stepDir);
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return null;
        }
        return mapper.readValue(json, type);
    }

    private static void atomicMove(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
