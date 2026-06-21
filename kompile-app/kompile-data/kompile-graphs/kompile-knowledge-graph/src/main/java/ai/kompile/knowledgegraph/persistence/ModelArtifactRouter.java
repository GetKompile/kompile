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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Routes {@link ModelArtifactRef} operations to the highest-priority
 * {@link ModelArtifactBackend} that supports the ref's {@link ModelArtifactType}.
 *
 * <p>Backends are sorted by {@link ModelArtifactBackend#priority()} descending at
 * construction time, so the first matching backend always wins.</p>
 */
@Service
public class ModelArtifactRouter {

    private final List<ModelArtifactBackend> backends;

    @Autowired
    public ModelArtifactRouter(List<ModelArtifactBackend> backends) {
        this.backends = backends.stream()
                .sorted(Comparator.comparingInt(ModelArtifactBackend::priority).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Store the content of {@code sourceFile} under {@code ref}, using the first backend
     * that supports the ref's type.
     *
     * @param ref        the artifact reference
     * @param sourceFile source file containing the serialized artifact
     * @throws IOException              if the backend cannot write the artifact
     * @throws IllegalStateException    if no backend supports the artifact type
     */
    public void store(ModelArtifactRef ref, Path sourceFile) throws IOException {
        backendFor(ref.type()).store(ref, sourceFile);
    }

    /**
     * Retrieve the artifact identified by {@code ref} into {@code targetFile}, using the
     * first backend that supports the ref's type.
     *
     * @param ref        the artifact reference
     * @param targetFile path at which the artifact will be written
     * @return {@code targetFile} on success
     * @throws IOException              if the artifact is not found or cannot be read
     * @throws IllegalStateException    if no backend supports the artifact type
     */
    public Path retrieve(ModelArtifactRef ref, Path targetFile) throws IOException {
        return backendFor(ref.type()).retrieve(ref, targetFile);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ModelArtifactBackend backendFor(ModelArtifactType type) {
        return backends.stream()
                .filter(b -> b.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No backend for type: " + type));
    }
}
