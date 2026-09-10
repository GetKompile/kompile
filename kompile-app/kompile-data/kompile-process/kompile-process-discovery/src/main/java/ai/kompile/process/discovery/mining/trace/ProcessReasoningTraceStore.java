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

package ai.kompile.process.discovery.mining.trace;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTraceJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * File-backed store for mined-process reasoning traces.
 */
@Component
public class ProcessReasoningTraceStore {

    private static final Logger log = LoggerFactory.getLogger(ProcessReasoningTraceStore.class);

    public static final String TRACE_ID_PREFIX = "process-trace:";

    private final Path storageDir;

    public ProcessReasoningTraceStore() {
        this(Path.of(System.getProperty("user.home"), ".kompile", "processes", "traces"));
    }

    public ProcessReasoningTraceStore(Path storageDir) {
        this.storageDir = storageDir;
    }

    public static String traceId(String suggestionId) {
        return TRACE_ID_PREFIX + suggestionId;
    }

    public void save(String suggestionId, ReasoningTrace trace) {
        if (suggestionId == null || suggestionId.isBlank() || trace == null) {
            return;
        }
        try {
            Files.createDirectories(storageDir);
            Path target = fileForSuggestion(suggestionId);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            String json = ReasoningTraceJsonCodec.encode(traceId(suggestionId), suggestionId, trace);
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to persist process reasoning trace for suggestion " + suggestionId, e);
        }
    }

    public Optional<ReasoningTrace> get(String suggestionId) {
        if (suggestionId == null || suggestionId.isBlank()) {
            return Optional.empty();
        }
        Path file = fileForSuggestion(suggestionId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) > ReasoningTraceJsonCodec.MAX_BYTES) {
                throw new IllegalArgumentException("Process reasoning trace exceeds maximum size");
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(ReasoningTraceJsonCodec.decode(json, suggestionId));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Failed to load process reasoning trace for suggestion {}: {}",
                    suggestionId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Delete one durable trace; missing traces are an idempotent no-op. */
    public void delete(String suggestionId) {
        Path file = fileForSuggestion(suggestionId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete process reasoning trace " + suggestionId, e);
        }
    }

    private Path fileForSuggestion(String suggestionId) {
        validateSuggestionId(suggestionId);
        Path root = storageDir.toAbsolutePath().normalize();
        Path file = root.resolve(suggestionId + ".json").normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Trace path escapes storage directory");
        return file;
    }

    private static void validateSuggestionId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid process trace suggestion ID");
        }
    }
}
