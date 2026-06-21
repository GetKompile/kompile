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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * SPI for a storage backend that can persist and retrieve reasoning model artifacts.
 *
 * <p>Multiple backends may coexist in the Spring context; the {@link ModelArtifactRouter}
 * selects the first one (by descending {@link #priority()}) that
 * {@link #supports(ModelArtifactType) supports} a given artifact type.</p>
 */
public interface ModelArtifactBackend {

    /**
     * Returns {@code true} if this backend handles the given artifact type.
     *
     * @param type the artifact type to test
     * @return {@code true} if this backend can store/retrieve artifacts of {@code type}
     */
    boolean supports(ModelArtifactType type);

    /**
     * Store the content from {@code sourceFile} under the given reference.
     *
     * @param ref        the artifact reference (fact sheet, type, id, algorithm)
     * @param sourceFile a regular, readable file containing the serialized artifact
     * @throws IOException if the backend cannot write the artifact
     */
    void store(ModelArtifactRef ref, Path sourceFile) throws IOException;

    /**
     * Retrieve the artifact identified by {@code ref} into {@code targetFile}.
     * The target file is created or overwritten on success.
     *
     * @param ref        the artifact reference
     * @param targetFile the path at which the artifact will be written
     * @return {@code targetFile} on success
     * @throws IOException if the artifact is not found or the backend is unavailable
     */
    Path retrieve(ModelArtifactRef ref, Path targetFile) throws IOException;

    /**
     * List all artifact refs stored for the given fact sheet id.
     *
     * <p>Implementations that cannot enumerate cheaply (e.g. remote registries) may
     * return an empty list.</p>
     *
     * @param factSheetId the fact sheet id to enumerate
     * @return a (possibly empty) list of artifact refs; never {@code null}
     */
    List<ModelArtifactRef> list(String factSheetId);

    /**
     * Backend selection priority. Higher values win when multiple backends support the same
     * artifact type. The {@link FileModelArtifactBackend} uses priority {@code 0} (lowest);
     * {@link StagingModelArtifactBackend} uses priority {@code 10}.
     *
     * @return integer priority; higher is preferred
     */
    int priority();
}
