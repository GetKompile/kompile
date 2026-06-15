/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn.logic;

import ai.kompile.knowledgegraph.domain.EdgeType;

import java.util.*;

/**
 * Factory and implementations for first-order logical constraints used in MEBN
 * context nodes.
 *
 * <p>Provides a fluent API for building constraints:</p>
 * <pre>
 * // Edge exists between two bound entities
 * Constraints.edgeExists("x", "y")
 *
 * // Entity has a specific type
 * Constraints.hasType("x", "ENTITY")
 *
 * // Conjunction: both must hold
 * Constraints.and(
 *     Constraints.edgeExists("x", "y"),
 *     Constraints.hasType("x", "ENTITY")
 * )
 *
 * // Universal: for all entities of type, constraint holds
 * Constraints.forAll("z", "Event", Constraints.edgeExists("x", "z"))
 *
 * // Implication: if A then B
 * Constraints.implies(
 *     Constraints.edgeExists("x", "y"),
 *     Constraints.weightAbove("x", "y", 0.5)
 * )
 * </pre>
 */
public final class Constraints {

    private Constraints() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // ATOMIC PREDICATES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check that an entity exists in the KG.
     */
    public static LogicalConstraint entityExists(String varName) {
        return new AtomicConstraint("entityExists(" + varName + ")",
                Set.of(varName),
                (kb, bindings) -> kb.entityExists(bindings.get(varName)));
    }

    /**
     * Check that a directed edge exists between two entities.
     */
    public static LogicalConstraint edgeExists(String sourceVar, String targetVar) {
        return new AtomicConstraint("edgeExists(" + sourceVar + ", " + targetVar + ")",
                Set.of(sourceVar, targetVar),
                (kb, bindings) -> kb.edgeExists(bindings.get(sourceVar), bindings.get(targetVar)));
    }

    /**
     * Check that an edge of a specific type exists.
     */
    public static LogicalConstraint edgeOfType(String sourceVar, String targetVar, EdgeType edgeType) {
        return new AtomicConstraint(
                "edgeOfType(" + sourceVar + ", " + targetVar + ", " + edgeType + ")",
                Set.of(sourceVar, targetVar),
                (kb, bindings) -> kb.edgeExistsOfType(
                        bindings.get(sourceVar), bindings.get(targetVar), edgeType));
    }

    /**
     * Check that an entity is of a specific type.
     */
    public static LogicalConstraint hasType(String varName, String typeName) {
        return new AtomicConstraint("hasType(" + varName + ", " + typeName + ")",
                Set.of(varName),
                (kb, bindings) -> {
                    Optional<String> type = kb.getEntityType(bindings.get(varName));
                    return type.isPresent() && type.get().equals(typeName);
                });
    }

    /**
     * Check that a metadata field equals a specific value.
     */
    public static LogicalConstraint metadataEquals(String varName, String key, String value) {
        return new AtomicConstraint(
                "metadata(" + varName + ", " + key + ") = " + value,
                Set.of(varName),
                (kb, bindings) -> {
                    Optional<String> val = kb.getMetadata(bindings.get(varName), key);
                    return val.isPresent() && val.get().equals(value);
                });
    }

    /**
     * Check that the edge weight between two entities is above a threshold.
     */
    public static LogicalConstraint weightAbove(String sourceVar, String targetVar, double threshold) {
        return new AtomicConstraint(
                "weight(" + sourceVar + ", " + targetVar + ") > " + threshold,
                Set.of(sourceVar, targetVar),
                (kb, bindings) -> {
                    Optional<Double> w = kb.getEdgeWeight(
                            bindings.get(sourceVar), bindings.get(targetVar));
                    return w.isPresent() && w.get() > threshold;
                });
    }

    /**
     * Check that two entities are different (inequality).
     */
    public static LogicalConstraint notEqual(String var1, String var2) {
        return new AtomicConstraint("(" + var1 + " != " + var2 + ")",
                Set.of(var1, var2),
                (kb, bindings) -> !bindings.get(var1).equals(bindings.get(var2)));
    }

    /**
     * Check that two entities share a specific property value.
     */
    public static LogicalConstraint shareProperty(String var1, String var2, String propertyKey) {
        return new AtomicConstraint(
                "shareProperty(" + var1 + ", " + var2 + ", " + propertyKey + ")",
                Set.of(var1, var2),
                (kb, bindings) -> kb.shareProperty(
                        bindings.get(var1), bindings.get(var2), propertyKey));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOGICAL CONNECTIVES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Logical conjunction: all sub-constraints must hold.
     */
    public static LogicalConstraint and(LogicalConstraint... constraints) {
        return new ConnectiveConstraint("AND", List.of(constraints), true);
    }

    /**
     * Logical disjunction: at least one sub-constraint must hold.
     */
    public static LogicalConstraint or(LogicalConstraint... constraints) {
        return new ConnectiveConstraint("OR", List.of(constraints), false);
    }

    /**
     * Logical negation.
     */
    public static LogicalConstraint not(LogicalConstraint constraint) {
        return new LogicalConstraint() {
            @Override
            public boolean evaluate(KnowledgeBase kb, Map<String, String> bindings) {
                return !constraint.evaluate(kb, bindings);
            }

            @Override
            public Set<String> getFreeVariables() {
                return constraint.getFreeVariables();
            }

            @Override
            public String describe() {
                return "NOT(" + constraint.describe() + ")";
            }
        };
    }

    /**
     * Logical implication: if antecedent then consequent.
     * Equivalent to OR(NOT(antecedent), consequent).
     */
    public static LogicalConstraint implies(LogicalConstraint antecedent,
                                             LogicalConstraint consequent) {
        return new LogicalConstraint() {
            @Override
            public boolean evaluate(KnowledgeBase kb, Map<String, String> bindings) {
                return !antecedent.evaluate(kb, bindings) || consequent.evaluate(kb, bindings);
            }

            @Override
            public Set<String> getFreeVariables() {
                Set<String> vars = new HashSet<>(antecedent.getFreeVariables());
                vars.addAll(consequent.getFreeVariables());
                return vars;
            }

            @Override
            public String describe() {
                return "(" + antecedent.describe() + " => " + consequent.describe() + ")";
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QUANTIFIERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Universal quantifier: for all entities of the given type, the constraint holds.
     *
     * @param boundVar   variable name to bind to each entity
     * @param entityType entity type name to iterate over
     * @param body       constraint to evaluate for each binding
     */
    public static LogicalConstraint forAll(String boundVar, String entityType,
                                            LogicalConstraint body) {
        return new QuantifiedConstraint("FORALL", boundVar, entityType, body, true);
    }

    /**
     * Existential quantifier: there exists an entity of the given type
     * for which the constraint holds.
     */
    public static LogicalConstraint exists(String boundVar, String entityType,
                                            LogicalConstraint body) {
        return new QuantifiedConstraint("EXISTS", boundVar, entityType, body, false);
    }

    /**
     * Constant TRUE constraint (always satisfied).
     */
    public static LogicalConstraint alwaysTrue() {
        return new AtomicConstraint("TRUE", Set.of(), (kb, bindings) -> true);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IMPLEMENTATION CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * An atomic predicate over the knowledge base.
     */
    private static class AtomicConstraint implements LogicalConstraint {
        private final String description;
        private final Set<String> freeVariables;
        private final java.util.function.BiFunction<KnowledgeBase, Map<String, String>, Boolean> predicate;

        AtomicConstraint(String description, Set<String> freeVariables,
                         java.util.function.BiFunction<KnowledgeBase, Map<String, String>, Boolean> predicate) {
            this.description = description;
            this.freeVariables = freeVariables;
            this.predicate = predicate;
        }

        @Override
        public boolean evaluate(KnowledgeBase kb, Map<String, String> bindings) {
            return predicate.apply(kb, bindings);
        }

        @Override
        public Set<String> getFreeVariables() {
            return freeVariables;
        }

        @Override
        public String describe() {
            return description;
        }
    }

    /**
     * AND / OR connective over sub-constraints.
     */
    private static class ConnectiveConstraint implements LogicalConstraint {
        private final String operator;
        private final List<LogicalConstraint> children;
        private final boolean isAnd;

        ConnectiveConstraint(String operator, List<LogicalConstraint> children, boolean isAnd) {
            this.operator = operator;
            this.children = children;
            this.isAnd = isAnd;
        }

        @Override
        public boolean evaluate(KnowledgeBase kb, Map<String, String> bindings) {
            for (LogicalConstraint child : children) {
                boolean result = child.evaluate(kb, bindings);
                if (isAnd && !result) return false;  // Short-circuit AND
                if (!isAnd && result) return true;    // Short-circuit OR
            }
            return isAnd; // AND: all passed; OR: none passed
        }

        @Override
        public Set<String> getFreeVariables() {
            Set<String> vars = new HashSet<>();
            for (LogicalConstraint child : children) {
                vars.addAll(child.getFreeVariables());
            }
            return vars;
        }

        @Override
        public String describe() {
            return "(" + String.join(" " + operator + " ",
                    children.stream().map(LogicalConstraint::describe).toList()) + ")";
        }
    }

    /**
     * FOR ALL / EXISTS quantifier over entities of a type.
     */
    private static class QuantifiedConstraint implements LogicalConstraint {
        private final String quantifier;
        private final String boundVar;
        private final String entityType;
        private final LogicalConstraint body;
        private final boolean isUniversal;

        QuantifiedConstraint(String quantifier, String boundVar, String entityType,
                             LogicalConstraint body, boolean isUniversal) {
            this.quantifier = quantifier;
            this.boundVar = boundVar;
            this.entityType = entityType;
            this.body = body;
            this.isUniversal = isUniversal;
        }

        @Override
        public boolean evaluate(KnowledgeBase kb, Map<String, String> bindings) {
            Set<String> entities = kb.getEntitiesOfType(entityType);
            if (entities.isEmpty()) return isUniversal; // Vacuous truth for FORALL

            for (String entityId : entities) {
                Map<String, String> extendedBindings = new HashMap<>(bindings);
                extendedBindings.put(boundVar, entityId);
                boolean result = body.evaluate(kb, extendedBindings);
                if (isUniversal && !result) return false;
                if (!isUniversal && result) return true;
            }
            return isUniversal;
        }

        @Override
        public Set<String> getFreeVariables() {
            Set<String> vars = new HashSet<>(body.getFreeVariables());
            vars.remove(boundVar); // Bound by this quantifier
            return vars;
        }

        @Override
        public String describe() {
            return quantifier + " " + boundVar + " in " + entityType +
                    ": " + body.describe();
        }
    }
}
