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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.core.graphrag.conformance.OntologyAxiom;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 (ontology axioms → PSL rule injection): compiles a list of {@link OntologyAxiom}s into
 * weighted PSL rule strings that can be fed directly to
 * {@link ai.kompile.graph.reasoning.psl.PslProgram#addRule(String)}.
 *
 * <h3>Axiom → rule mapping</h3>
 * <p>Both DOMAIN and RANGE axioms produce a binary (2-arg) PSL rule that propagates the edge
 * predicate into an entity-type membership atom for the constrained side:
 *
 * <pre>
 *   DOMAIN(works_at, Person):
 *     → "0.8: works_at(?X, ?Y) -> has_type_Person(?X) ^2"
 *       "If X works_at Y then X should be a Person."
 *
 *   RANGE(works_at, Organization):
 *     → "0.8: works_at(?X, ?Y) -> has_type_Organization(?Y) ^2"
 *       "If X works_at Y then Y should be an Organization."
 * </pre>
 *
 * <p>The head atom {@code has_type_<Type>(arg)} uses a normalised predicate name derived from the
 * entity-type label (spaces replaced by {@code _}, lower-cased, prefixed with {@code has_type_}).
 * This is consistent with the propagation atoms the live program generates for entity-type facts
 * that the graph projector asserts (e.g. {@code has_type_person(entity_id)} from node labels).</p>
 *
 * <p>Rules are SOFT (weighted, squared hinge), never hard constraints. This preserves PSL
 * semantics: the MAP solver penalises violations but does not enforce them absolutely, which is
 * the correct behaviour for schema-level ontology guidance when extracted data may be imperfect.</p>
 *
 * <h3>Design constraints</h3>
 * <ul>
 *   <li>Pure, infra-free: no Spring, no JPA, no I/O — unit-testable in isolation.</li>
 *   <li>Rule weight must NOT be hardcoded: it is a constructor parameter (set by the caller
 *       from {@code KbConfig.ontologyRuleWeight} or a fallback).</li>
 *   <li>Null or blank predicate/entityType → that axiom is silently skipped (defensive; the
 *       SPI contract already forbids nulls in the list but callers should not crash).</li>
 * </ul>
 */
public class OntologyToPslRuleCompiler {

    /**
     * Weight applied to every compiled ontology rule.
     *
     * <p>Sourced from {@code KbConfig.ontologyRuleWeight} (a field the lead must add to
     * {@link ai.kompile.knowledgegraph.confidence.KbConfig}; suggested default 0.8).  Until that
     * field exists, callers fall back to {@code kbCfg().pslDefaultRuleWeight}.</p>
     */
    private final double ruleWeight;

    /**
     * Construct a compiler with an explicit rule weight.
     *
     * @param ruleWeight the PSL soft-rule weight to assign to every compiled ontology rule;
     *                   must be positive (typically 0.5–1.0)
     */
    public OntologyToPslRuleCompiler(double ruleWeight) {
        if (ruleWeight <= 0.0) {
            throw new IllegalArgumentException("ruleWeight must be positive; got: " + ruleWeight);
        }
        this.ruleWeight = ruleWeight;
    }

    /**
     * Compile a list of {@link OntologyAxiom}s into PSL rule strings.
     *
     * <p>Each axiom produces exactly one rule string in the format accepted by
     * {@link ai.kompile.graph.reasoning.psl.PslProgram#addRule(String)}.  Axioms with null or
     * blank {@code predicate} or {@code entityType} are silently skipped.</p>
     *
     * @param axioms the ontology axioms to compile; must not be null; may be empty
     * @return an ordered list of PSL rule strings (one per valid axiom), never null
     */
    public List<String> compile(List<OntologyAxiom> axioms) {
        if (axioms == null || axioms.isEmpty()) {
            return List.of();
        }
        List<String> rules = new ArrayList<>(axioms.size());
        for (OntologyAxiom axiom : axioms) {
            if (axiom == null) continue;
            String predicate = axiom.predicate();
            String entityType = axiom.entityType();
            OntologyAxiom.Kind kind = axiom.kind();

            if (predicate == null || predicate.isBlank()) continue;
            if (entityType == null || entityType.isBlank()) continue;
            if (kind == null) continue;

            String normalizedPred = normalizeName(predicate);
            String typeAtom = "has_type_" + normalizeName(entityType);

            String rule = switch (kind) {
                // DOMAIN: subject (?X) of predicate must be of entityType
                case DOMAIN ->
                    ruleWeight + ": " + normalizedPred + "(?X, ?Y) -> " + typeAtom + "(?X) ^2";
                // RANGE: object (?Y) of predicate must be of entityType
                case RANGE ->
                    ruleWeight + ": " + normalizedPred + "(?X, ?Y) -> " + typeAtom + "(?Y) ^2";
            };
            rules.add(rule);
        }
        return rules;
    }

    /**
     * Normalise a predicate or entity-type name into a PSL-safe atom name.
     *
     * <p>Converts to lower case, replaces spaces and hyphens with underscores, and strips any
     * characters that are not alphanumeric or underscore. This is consistent with the atom-name
     * convention used by {@link IncrementalReasoningOrchestrator#buildProgramFromFactStore} where
     * predicate names come directly from atomKey strings extracted from the FactStore.</p>
     */
    static String normalizeName(String raw) {
        return raw.trim()
                  .toLowerCase()
                  .replace(' ', '_')
                  .replace('-', '_')
                  .replaceAll("[^a-z0-9_]", "_");
    }

    /** Return the rule weight this compiler was constructed with (useful for testing). */
    public double ruleWeight() {
        return ruleWeight;
    }
}
