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

/**
 * Immutable reference to a reasoning model artifact.  Implementations of
 * {@link ModelArtifactBackend} use this as the storage key.
 *
 * @param factSheetId the fact sheet the artifact belongs to (string form to avoid
 *                    coupling to the JPA entity primary key type)
 * @param type        the artifact type discriminator
 * @param artifactId  a within-type identifier — e.g. the PSL programId, {@code "global"},
 *                    or {@code "rotate-v1"} for a KGE pointer
 * @param algorithm   optional algorithm label (e.g. {@code "RotatE"}, {@code "SGNS"});
 *                    may be {@code null}
 */
public record ModelArtifactRef(
        String factSheetId,
        ModelArtifactType type,
        String artifactId,
        String algorithm
) {}
