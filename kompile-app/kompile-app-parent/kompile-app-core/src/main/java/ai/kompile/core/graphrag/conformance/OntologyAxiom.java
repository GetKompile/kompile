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
package ai.kompile.core.graphrag.conformance;

/**
 * A lightweight, infra-free projection of a single ontology axiom — enough for the reasoning
 * pipeline (e.g. an ontology→PSL rule compiler) to constrain inference WITHOUT depending on
 * process-engine's {@code OntologySchema} model. Produced by {@link OntologyProjectionProvider}.
 *
 * <p>For a relationship type {@code works_at} from {@code Person} to {@code Organization} the
 * projection emits two axioms: {@code (DOMAIN, "works_at", "Person")} — the subject of a
 * {@code works_at} edge should be a {@code Person} — and {@code (RANGE, "works_at", "Organization")}.</p>
 *
 * @param kind       the axiom kind (which side of the predicate {@code entityType} constrains)
 * @param predicate  the relationship-type name the axiom is about
 * @param entityType the entity-type name the constrained side must have
 */
public record OntologyAxiom(Kind kind, String predicate, String entityType) {

    /** Which side of {@link #predicate} the {@link #entityType} constrains. */
    public enum Kind {
        /** The subject (source) of {@code predicate} should be of type {@code entityType}. */
        DOMAIN,
        /** The object (target) of {@code predicate} should be of type {@code entityType}. */
        RANGE
    }
}
