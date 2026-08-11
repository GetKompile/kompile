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
package ai.kompile.graph.reasoning.admission;

import java.util.List;
import java.util.Objects;

/**
 * One deterministic, machine-readable fact used by the graph admission branch.
 *
 * <p>Entity, relation, and predicate paths are ordered from the matched candidate outward. This lets
 * a small model consume the trace without reconstructing graph traversal or parsing the human summary.</p>
 */
public record AdmissionEvidence(
        Kind kind,
        String ruleId,
        List<String> entityPath,
        List<String> relationPath,
        List<String> predicatePath,
        double strength,
        String summary) {

    /** Semantic role of an evidence item. */
    public enum Kind {
        IDENTITY_MATCH,
        STRUCTURAL_SCORE,
        COMPETITOR,
        AFFIRMING_RELATION,
        CAUTION,
        REQUIREMENT,
        OVERRIDE,
        CONFLICT,
        IDENTITY_CONSTRAINT,
        CONTEXT_RELATION,
        AMBIGUITY,
        NO_MATCH
    }

    public AdmissionEvidence {
        Objects.requireNonNull(kind, "kind");
        ruleId = requireText(ruleId, "ruleId");
        entityPath = immutablePath(entityPath, "entityPath", false);
        relationPath = immutablePath(relationPath, "relationPath", true);
        predicatePath = immutablePath(predicatePath, "predicatePath", true);
        if (relationPath.size() != predicatePath.size()) {
            throw new IllegalArgumentException("relationPath and predicatePath must have the same size");
        }
        if (!Double.isFinite(strength) || strength < 0.0 || strength > 1.0) {
            throw new IllegalArgumentException("strength must be finite and in [0,1]: " + strength);
        }
        summary = requireText(summary, "summary");
    }

    private static List<String> immutablePath(List<String> values,
                                              String name,
                                              boolean allowEmpty) {
        Objects.requireNonNull(values, name);
        List<String> copy = values.stream()
                .map(value -> requireText(value, name + " element"))
                .toList();
        if (!allowEmpty && copy.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return copy;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
