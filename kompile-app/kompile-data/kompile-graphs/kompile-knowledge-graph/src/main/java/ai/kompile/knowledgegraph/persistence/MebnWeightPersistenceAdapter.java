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

import ai.kompile.graph.reasoning.learning.MebnWeightSerializer;
import ai.kompile.graph.reasoning.mebn.MTheory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Persists and restores MEBN edge strengths for a given fact sheet.
 *
 * <p>Serialization delegates to the infra-free {@link MebnWeightSerializer}. Files land
 * at {@code <dataDir>/data/graph/reasoning/<factSheetId>/mebn-weights.json}.</p>
 */
@Slf4j
@Component
public class MebnWeightPersistenceAdapter {

    public static final String THEORY_ARTIFACT_FILE = "mebn-theory.v1.json";

    @Value("${kompile.data.dir:}")
    private String dataDir;

    /**
     * Serialize the edge strengths of {@code theory} and write them to
     * {@code <reasoningDir>/<factSheetId>/mebn-weights.json}.
     *
     * @param factSheetId the fact sheet identifier
     * @param theory      the MTheory whose edge strengths to persist
     * @throws IOException if the file cannot be written
     */
    public void persist(long factSheetId, MTheory theory) throws IOException {
        String json = MebnWeightSerializer.strengthsToJson(theory);
        Path target = reasoningDir(factSheetId).resolve("mebn-weights.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, json, StandardCharsets.UTF_8);
        log.debug("MebnWeightPersistenceAdapter: persisted weights for factSheet {} → {}", factSheetId, target);
    }

    /**
     * Load persisted edge strengths for {@code factSheetId} and apply them to {@code theory}
     * via {@link MebnWeightSerializer#applyStrengths}.
     *
     * @param factSheetId the fact sheet identifier
     * @param theory      the MTheory to update in place
     * @return {@code true} if a weights file was found and applied; {@code false} otherwise
     * @throws IOException if the file exists but cannot be read
     */
    public boolean load(long factSheetId, MTheory theory) throws IOException {
        Path source = reasoningDir(factSheetId).resolve("mebn-weights.json");
        if (!Files.exists(source)) {
            log.debug("MebnWeightPersistenceAdapter: no weights file for factSheet {}", factSheetId);
            return false;
        }
        String json = Files.readString(source, StandardCharsets.UTF_8);
        MebnWeightSerializer.applyStrengths(theory, json);
        log.debug("MebnWeightPersistenceAdapter: loaded weights for factSheet {} from {}", factSheetId, source);
        return true;
    }

    /**
     * Returns the absolute {@link Path} of the MEBN weights file for a given fact sheet.
     * The path is {@code <dataDir>/data/graph/reasoning/<factSheetId>/mebn-weights.json}.
     *
     * <p>Used by {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator}
     * to supply an artifact path in {@link ai.kompile.knowledgegraph.staging.ModelTrainedEvent}
     * without requiring the caller to reconstruct the directory convention.</p>
     *
     * @param factSheetId the fact sheet identifier
     * @return absolute path to the MEBN weights JSON file (may not yet exist)
     */
    public Path mebnArtifactPath(long factSheetId) {
        return reasoningDir(factSheetId).resolve("mebn-weights.json");
    }

    /**
     * Read the persisted edge strengths for {@code factSheetId} and return them as a raw
     * {@code compositeKey → strength} map without applying them to any theory.
     *
     * <p>The composite key has the form {@code "<mfragName>|<parent>-><child>"}; callers can split
     * on {@code '|'} to extract the MFrag name and the edge description.</p>
     *
     * @param factSheetId the fact sheet identifier
     * @return the parsed strength map, or an empty map when no weights file exists
     * @throws IOException if the file exists but cannot be read
     */
    public Map<String, Double> readRawStrengths(long factSheetId) throws IOException {
        Path source = reasoningDir(factSheetId).resolve("mebn-weights.json");
        if (!Files.exists(source)) {
            log.debug("MebnWeightPersistenceAdapter.readRawStrengths: no file for factSheet {}", factSheetId);
            return Collections.emptyMap();
        }
        String json = Files.readString(source, StandardCharsets.UTF_8);
        return MebnWeightSerializer.parseStrengths(json);
    }

    public void persistTheoryArtifact(long factSheetId, String json) throws IOException {
        Path target = reasoningDir(factSheetId).resolve(THEORY_ARTIFACT_FILE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }

    public Optional<String> readTheoryArtifact(long factSheetId) throws IOException {
        Path source = reasoningDir(factSheetId).resolve(THEORY_ARTIFACT_FILE);
        return Files.isRegularFile(source)
                ? Optional.of(Files.readString(source, StandardCharsets.UTF_8)) : Optional.empty();
    }

    public void clearTheoryArtifacts(long factSheetId) throws IOException {
        Path directory = reasoningDir(factSheetId);
        Files.deleteIfExists(directory.resolve(THEORY_ARTIFACT_FILE));
        Files.deleteIfExists(directory.resolve("mebn-weights.json"));
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
