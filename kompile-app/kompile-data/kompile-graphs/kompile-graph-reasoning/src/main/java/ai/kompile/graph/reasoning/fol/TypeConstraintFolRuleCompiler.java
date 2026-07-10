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
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.mebn.logic.Constraints;
import ai.kompile.graph.reasoning.mebn.type.AttributeDefinition;
import ai.kompile.graph.reasoning.mebn.type.TypeConstraint;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.graph.reasoning.mebn.type.TypeNode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles structural type-schema declarations into hard first-order rules.
 *
 * <p>This bridge keeps the schema and ontology layer declarative while giving the existing
 * FOL/PSL path executable constraints. It intentionally handles only constraints that fit the
 * current pairwise FOL grounding model: relation domain/range checks and required attributes.
 * Cardinality remains represented on {@link TypeConstraint.CardinalityConstraint} for validators
 * and future counting-aware grounders.</p>
 */
public final class TypeConstraintFolRuleCompiler {

    private static final double HARD_WEIGHT = Double.POSITIVE_INFINITY;

    private TypeConstraintFolRuleCompiler() {
    }

    /**
     * Compile a hierarchy into a named FOL rule set.
     *
     * @param hierarchy type hierarchy carrying declared schemas and constraints
     * @return hard rules enforcing relation domain/range and required attributes
     */
    public static FolRuleSet compile(TypeHierarchy hierarchy) {
        return compile("type-constraints", hierarchy);
    }

    /**
     * Compile a hierarchy into a named FOL rule set.
     *
     * @param name      output rule set name
     * @param hierarchy type hierarchy carrying declared schemas and constraints
     * @return hard rules enforcing relation domain/range and required attributes
     */
    public static FolRuleSet compile(String name, TypeHierarchy hierarchy) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(hierarchy, "hierarchy");

        FolRuleSet.Builder rules = FolRuleSet.named(name);
        Set<String> emitted = new LinkedHashSet<>();
        for (TypeNode node : hierarchy.allTypes()) {
            compileRequiredAttributes(node, rules, emitted);
            compileDeclaredConstraints(node, rules, emitted);
        }
        return rules.build();
    }

    private static void compileRequiredAttributes(TypeNode node, FolRuleSet.Builder rules, Set<String> emitted) {
        for (AttributeDefinition attribute : node.getAttributeSchema().requiredAttributes()) {
            if (!hasText(attribute.getName())) {
                continue;
            }
            String key = "REQ|" + node.getTypeName().toLowerCase(Locale.ROOT)
                    + "|" + attribute.getName().toLowerCase(Locale.ROOT);
            if (!emitted.add(key)) {
                continue;
            }
            addRequiredAttributeRule(node.getTypeName(), attribute.getName(), rules);
        }
    }

    private static void compileDeclaredConstraints(TypeNode node, FolRuleSet.Builder rules, Set<String> emitted) {
        for (TypeConstraint constraint : node.getConstraints()) {
            if (constraint instanceof TypeConstraint.RelationConstraint relation) {
                compileRelationConstraint(relation, rules, emitted);
            } else if (constraint instanceof TypeConstraint.AttributeRequiredConstraint required) {
                if (!hasText(required.attributeName())) {
                    continue;
                }
                String key = "REQ|" + node.getTypeName().toLowerCase(Locale.ROOT)
                        + "|" + required.attributeName().toLowerCase(Locale.ROOT);
                if (emitted.add(key)) {
                    addRequiredAttributeRule(node.getTypeName(), required.attributeName(), rules);
                }
            }
        }
    }

    private static void compileRelationConstraint(TypeConstraint.RelationConstraint relation,
                                                  FolRuleSet.Builder rules,
                                                  Set<String> emitted) {
        if (!hasText(relation.relationLabel())
                || !hasText(relation.domainType())
                || !hasText(relation.rangeType())) {
            return;
        }
        String key = "REL|" + relation.relationLabel().toLowerCase(Locale.ROOT)
                + "|" + relation.domainType().toLowerCase(Locale.ROOT)
                + "|" + relation.rangeType().toLowerCase(Locale.ROOT);
        if (!emitted.add(key)) {
            return;
        }
        String name = "schema_relation_" + safe(relation.relationLabel())
                + "_" + safe(relation.domainType())
                + "_" + safe(relation.rangeType());
        rules.add(FolRule.builder(name)
                .weight(HARD_WEIGHT)
                .antecedent(Constraints.edgeOfType("X", "Y", relation.relationLabel()))
                .consequent(Constraints.and(
                        Constraints.hasType("X", relation.domainType()),
                        Constraints.hasType("Y", relation.rangeType())))
                .build());
    }

    private static void addRequiredAttributeRule(String typeName, String attributeName, FolRuleSet.Builder rules) {
        String name = "schema_required_" + safe(typeName) + "_" + safe(attributeName);
        rules.add(FolRule.builder(name)
                .weight(HARD_WEIGHT)
                .antecedent(Constraints.hasType("X", typeName))
                .consequent(Constraints.metadataExists("X", attributeName))
                .build());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "_");
    }
}
