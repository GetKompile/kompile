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

package ai.kompile.core.graphrag.partition.staging;

import ai.kompile.core.graphrag.model.Entity;

/**
 * Decides when two extractions are talking about the same thing.
 *
 * <p>This is the only judgement staging makes that it cannot take back — everything downstream,
 * including every merge and every conflict, follows from whether two mentions got the same key.
 * It is an interface rather than a constant so a corpus with real identifiers (a ticker, a
 * customer number, a barcode) can key on those instead of on a name, without staging having to
 * know that such identifiers exist.</p>
 */
public interface StagedKeys {

    /** Staged identity for an extracted entity. */
    String entityKey(Entity entity);

    /**
     * Staged identity for a bare reference to an entity, as relationships carry it.
     *
     * <p>Extractors name relationship endpoints inconsistently — sometimes the entity's id,
     * sometimes its title. Staging resolves ids through the chunk it came from and falls back to
     * this, so an edge whose endpoint was never extracted as an entity still lands on a stable
     * key.</p>
     */
    String referenceKey(String rawReference);

    /** Staged identity for an edge between two already-resolved endpoints. */
    default String relationshipKey(String sourceKey, String type, String targetKey) {
        return sourceKey + "|" + StagedText.normalisedKeyPart(type) + "|" + targetKey;
    }

    /**
     * Keys entities on their normalised name alone.
     *
     * <p>Type is deliberately <em>not</em> part of the key. One chunk calling Acme an
     * {@code ORGANIZATION} and another calling it a {@code COMPANY} is a disagreement about one
     * entity, and staging is built to record that as a {@link StagedConflict}. Folding type into
     * the key would turn it into two entities instead, which silently doubles the graph and hides
     * the disagreement that caused it.</p>
     */
    static StagedKeys byName() {
        return new StagedKeys() {
            @Override
            public String entityKey(Entity entity) {
                if (entity == null) {
                    return "";
                }
                return StagedText.normalisedKeyPart(
                        StagedText.firstNonBlank(entity.getTitle(), entity.getId()));
            }

            @Override
            public String referenceKey(String rawReference) {
                return StagedText.normalisedKeyPart(rawReference);
            }
        };
    }
}
