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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
            try (ObjectOutputStream out = new ObjectOutputStream(
                    Files.newOutputStream(fileForSuggestion(suggestionId)))) {
                out.writeObject(trace);
            }
        } catch (IOException e) {
            log.warn("Failed to persist process reasoning trace for suggestion {}: {}",
                    suggestionId, e.getMessage());
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
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            Object value = in.readObject();
            return value instanceof ReasoningTrace trace ? Optional.of(trace) : Optional.empty();
        } catch (IOException | ClassNotFoundException e) {
            log.warn("Failed to load process reasoning trace for suggestion {}: {}",
                    suggestionId, e.getMessage());
            return Optional.empty();
        }
    }

    private Path fileForSuggestion(String suggestionId) {
        return storageDir.resolve(fileSafe(suggestionId) + ".ser");
    }

    private static String fileSafe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
