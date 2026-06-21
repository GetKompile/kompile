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
 * Discriminator for the type of a reasoning model artifact that can be stored or retrieved
 * via {@link ModelArtifactBackend}.
 */
public enum ModelArtifactType {

    /** Learned PSL rule weights (a {@code Map<String, Double>} serialized to JSON). */
    PSL_WEIGHTS,

    /** Learned MEBN edge strengths (mfrag|parent->child → double, JSON). */
    MEBN_WEIGHTS,

    /** Entity embedding matrix (dim + entityIds + vectors[], JSON). */
    EMBEDDING_TABLE,

    /** Declared TypeRegistry structure (types/attributes/constraints, JSON). */
    TYPE_REGISTRY,

    /**
     * A SameDiff model checkpoint (binary flatbuffers). Typically large; routed to the
     * staging backend when a staging URL is configured.
     */
    SAMEDIFF_CHECKPOINT,

    /**
     * A pointer/manifest to a KGE model hosted elsewhere (e.g. staging registry).
     * Stored as a small JSON {@link KgeModelRef}.
     */
    KGE_POINTER
}
