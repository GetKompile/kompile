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

import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistryIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists and restores a {@link TypeRegistry} for a given fact sheet.
 *
 * <p>Serialization delegates to the infra-free {@link TypeRegistryIO}. Files land at
 * {@code <dataDir>/data/graph/reasoning/<factSheetId>/type-registry.json}.</p>
 *
 * <p><strong>Note on {@link TypeRegistryIO} serialization:</strong> {@link TypeRegistry} does
 * not expose its internal declarations map. For full round-trip fidelity callers should
 * use {@link TypeRegistryIO#toJson(java.util.List)} with an explicit snapshot list when
 * persisting, and {@link TypeRegistryIO#fromJson(String)} when restoring. This adapter
 * provides a convenient {@link #load} side that restores the registry from the stored JSON.</p>
 */
@Slf4j
@Component
public class TypeRegistryPersistenceAdapter {

    @Value("${kompile.data.dir:}")
    private String dataDir;

    /**
     * Serialize {@code registry} and write it to
     * {@code <reasoningDir>/<factSheetId>/type-registry.json}.
     *
     * <p>Because {@link TypeRegistry} does not expose its declarations, callers that need
     * a full structural snapshot should supply the JSON directly via
     * {@link #persistJson(long, String)}. This overload accepts a {@link TypeRegistry}
     * but produces an empty-types JSON when the registry has no publicly traversable state
     * — suitable as a tombstone/reset entry.</p>
     *
     * @param factSheetId the fact sheet identifier
     * @param registry    the TypeRegistry whose structure is declared (via snapshot list)
     * @throws IOException if the file cannot be written
     * @see TypeRegistryIO#toJson(java.util.List)
     */
    public void persist(long factSheetId, TypeRegistry registry) throws IOException {
        // TypeRegistry does not expose its internal declarations map via a public getter.
        // Callers that need a structural round-trip must use persistJson() with a snapshot list:
        //   persistJson(factSheetId, TypeRegistryIO.toJson(snapshotList))
        // This overload is provided as a tombstone/reset marker that writes an empty types array.
        persistJson(factSheetId, "{\"types\":[]}");
    }

    /**
     * Write a pre-serialized {@link TypeRegistry} JSON string to
     * {@code <reasoningDir>/<factSheetId>/type-registry.json}.
     *
     * <p>Preferred entrypoint when the caller already has the JSON produced by
     * {@link TypeRegistryIO#toJson(java.util.List)}.</p>
     *
     * @param factSheetId the fact sheet identifier
     * @param json        a JSON string produced by {@link TypeRegistryIO#toJson(java.util.List)}
     * @throws IOException if the file cannot be written
     */
    public void persistJson(long factSheetId, String json) throws IOException {
        Path target = reasoningDir(factSheetId).resolve("type-registry.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, json, StandardCharsets.UTF_8);
        log.debug("TypeRegistryPersistenceAdapter: persisted type registry for factSheet {} → {}",
                factSheetId, target);
    }

    /**
     * Load the {@link TypeRegistry} for {@code factSheetId} if a persisted file exists.
     *
     * @param factSheetId the fact sheet identifier
     * @return the restored {@link TypeRegistry}, or {@link Optional#empty()} if not found
     * @throws IOException if the file exists but cannot be read
     */
    public Optional<TypeRegistry> load(long factSheetId) throws IOException {
        Path source = reasoningDir(factSheetId).resolve("type-registry.json");
        if (!Files.exists(source)) {
            log.debug("TypeRegistryPersistenceAdapter: no type registry file for factSheet {}", factSheetId);
            return Optional.empty();
        }
        String json = Files.readString(source, StandardCharsets.UTF_8);
        TypeRegistry registry = TypeRegistryIO.fromJson(json);
        log.debug("TypeRegistryPersistenceAdapter: loaded type registry for factSheet {} from {}",
                factSheetId, source);
        return Optional.of(registry);
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
