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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Persists and loads {@link KgeModelRef} pointer manifests for trained KGE models.
 *
 * <p>The manifest is a small JSON file ({@code kge-model-ref.json}) written alongside the
 * other reasoning artifacts at
 * {@code <dataDir>/data/graph/reasoning/<factSheetId>/kge-model-ref.json}.
 * Heavy checkpoint binaries are routed through {@link ModelArtifactRouter}.</p>
 */
@Slf4j
@Service
public class EmbeddingModelPersistenceService {

    @Value("${kompile.data.dir:}")
    private String dataDir;

    @Value("${kompile.staging.url:http://localhost:8090}")
    private String stagingUrl;

    private final ObjectMapper objectMapper;
    private final ModelArtifactRouter router;

    @Autowired
    public EmbeddingModelPersistenceService(ObjectMapper objectMapper, ModelArtifactRouter router) {
        this.objectMapper = objectMapper;
        this.router = router;
    }

    /**
     * Build a {@link KgeModelRef} from the supplied training metadata and write it to
     * {@code <reasoningDir>/<factSheetId>/kge-model-ref.json}.
     *
     * @param factSheetId the fact sheet identifier
     * @param modelId     unique model identifier
     * @param algorithm   KGE algorithm name (e.g. {@code "RotatE"})
     * @param dim         embedding dimension
     * @param snapshotId  optional checkpoint/snapshot identifier in the staging registry
     * @throws IOException if the manifest file cannot be written
     */
    public void persistKgePointer(long factSheetId, String modelId, String algorithm,
                                  int dim, String snapshotId) throws IOException {
        String nd4jVersion = resolveNd4jVersion();
        KgeModelRef ref = KgeModelRef.builder()
                .modelId(modelId)
                .stagingUrl(stagingUrl != null && !stagingUrl.isBlank() ? stagingUrl : null)
                .algorithm(algorithm)
                .dim(dim)
                .snapshotId(snapshotId)
                .trainedAt(Instant.now().toString())
                .origin("LOCAL")
                .nd4jVersion(nd4jVersion)
                .build();

        Path target = reasoningDir(factSheetId).resolve("kge-model-ref.json");
        Files.createDirectories(target.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), ref);
        log.debug("EmbeddingModelPersistenceService: persisted KGE pointer for factSheet {} → {}", factSheetId, target);
    }

    /**
     * Load the {@link KgeModelRef} manifest for {@code factSheetId} if one exists.
     *
     * @param factSheetId the fact sheet identifier
     * @return the manifest, or {@link Optional#empty()} if not found
     * @throws IOException if the file exists but cannot be read or parsed
     */
    public Optional<KgeModelRef> loadKgePointer(long factSheetId) throws IOException {
        Path source = reasoningDir(factSheetId).resolve("kge-model-ref.json");
        if (!Files.exists(source)) {
            log.debug("EmbeddingModelPersistenceService: no KGE pointer file for factSheet {}", factSheetId);
            return Optional.empty();
        }
        KgeModelRef ref = objectMapper.readValue(source.toFile(), KgeModelRef.class);
        log.debug("EmbeddingModelPersistenceService: loaded KGE pointer for factSheet {} from {}", factSheetId, source);
        return Optional.of(ref);
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

    private static String resolveNd4jVersion() {
        try {
            Package pkg = Nd4j.class.getPackage();
            String version = (pkg != null) ? pkg.getImplementationVersion() : null;
            return (version != null) ? version : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
