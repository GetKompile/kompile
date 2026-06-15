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

package ai.kompile.core.kgembedding;

import java.util.Objects;

/**
 * Represents a knowledge graph triple (head, relation, tail).
 * Also known as a fact or statement in knowledge graph terminology.
 *
 * <p>Example: ("Albert Einstein", "bornIn", "Germany")
 *
 * @param head The head entity (subject)
 * @param relation The relation type (predicate)
 * @param tail The tail entity (object)
 */
public record Triple(
        String head,
        String relation,
        String tail
) {
    /**
     * Creates a new triple with validation.
     */
    public Triple {
        Objects.requireNonNull(head, "head cannot be null");
        Objects.requireNonNull(relation, "relation cannot be null");
        Objects.requireNonNull(tail, "tail cannot be null");
    }

    /**
     * Creates a corrupted version of this triple by replacing the head entity.
     * Used for negative sampling during training.
     *
     * @param newHead The replacement head entity
     * @return A new triple with the head replaced
     */
    public Triple corruptHead(String newHead) {
        return new Triple(newHead, relation, tail);
    }

    /**
     * Creates a corrupted version of this triple by replacing the tail entity.
     * Used for negative sampling during training.
     *
     * @param newTail The replacement tail entity
     * @return A new triple with the tail replaced
     */
    public Triple corruptTail(String newTail) {
        return new Triple(head, relation, newTail);
    }

    /**
     * Creates a corrupted version of this triple by replacing the relation.
     * Used for negative sampling during training.
     *
     * @param newRelation The replacement relation
     * @return A new triple with the relation replaced
     */
    public Triple corruptRelation(String newRelation) {
        return new Triple(head, newRelation, tail);
    }

    /**
     * Returns a human-readable representation of this triple.
     */
    @Override
    public String toString() {
        return "(" + head + ", " + relation + ", " + tail + ")";
    }
}
