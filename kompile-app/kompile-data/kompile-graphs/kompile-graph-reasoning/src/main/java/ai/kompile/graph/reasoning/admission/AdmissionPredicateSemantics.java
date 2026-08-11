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

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;

import java.util.Objects;

/**
 * Classifies a relation from the perspective of one entity on an admission evidence path.
 *
 * <p>The structural reasoner remains domain-neutral. Implementations provide the predicate meaning
 * needed to turn its graph paths into compact evidence suitable for policy code or a small model.</p>
 */
@FunctionalInterface
public interface AdmissionPredicateSemantics {

    Assessment assess(GraphRelation relation,
                      GraphEntity source,
                      GraphEntity target,
                      String focusEntityId);

    static AdmissionPredicateSemantics canonical() {
        return new CanonicalAdmissionPredicateSemantics();
    }

    /** Stable classification and explanation of one relation. */
    record Assessment(AdmissionEvidence.Kind kind, String ruleId, String summary) {
        public Assessment {
            Objects.requireNonNull(kind, "kind");
            ruleId = requireText(ruleId, "ruleId");
            summary = requireText(summary, "summary");
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value.trim();
        }
    }
}
