/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.mebn;

import java.util.*;

/**
 * A parameterized random variable in the MEBN framework.
 *
 * <p>Unlike ordinary BN variables, MEBN random variables are parameterized by
 * entity arguments. For example:</p>
 * <ul>
 *   <li>{@code isActive(person)} — a Boolean RV parameterized by a Person entity</li>
 *   <li>{@code influences(event1, event2)} — an RV over pairs of Event entities</li>
 *   <li>{@code riskLevel(process)} — an RV with states {LOW, MEDIUM, HIGH, CRITICAL}</li>
 * </ul>
 *
 * <p>When grounded with specific entity instances, each substitution produces
 * a concrete BN node: {@code isActive(alice)}, {@code isActive(bob)}, etc.</p>
 */
public class RandomVariable {

    /**
     * Role of this random variable in its home MFrag.
     */
    public enum NodeRole {
        /** Has its CPT defined in this MFrag. */
        RESIDENT,
        /** CPT defined in another MFrag; used as parent input. */
        INPUT,
        /** Boolean constraint that gates MFrag applicability. */
        CONTEXT
    }

    private final String name;
    private final List<EntityType> argumentTypes;
    private final List<String> argVars;
    private final List<String> states;
    private final NodeRole role;

    /**
     * Create a binary random variable with custom argument types.
     *
     * <p>Default arg-var names are {@code TypeName_i} for each argument position {@code i},
     * matching the keys computed by {@link SSBNGenerator} — so all existing theories
     * behave identically.</p>
     */
    public RandomVariable(String name, List<EntityType> argumentTypes, NodeRole role) {
        this(name, argumentTypes, List.of("FALSE", "TRUE"), role);
    }

    /**
     * Create a random variable with custom states and argument types.
     *
     * <p>Default arg-var names are {@code TypeName_i} for each argument position {@code i}.</p>
     */
    public RandomVariable(String name, List<EntityType> argumentTypes,
                          List<String> states, NodeRole role) {
        this.name = name;
        this.argumentTypes = List.copyOf(argumentTypes);
        this.states = List.copyOf(states);
        this.role = role;
        // Default arg-var key: TypeName_i — must equal the key SSBNGenerator computed historically
        List<String> vars = new ArrayList<>();
        for (int i = 0; i < argumentTypes.size(); i++) {
            vars.add(argumentTypes.get(i).getTypeName() + "_" + i);
        }
        this.argVars = List.copyOf(vars);
    }

    /**
     * Internal constructor that accepts explicit logical-variable names per argument.
     * Used by the named factory methods (e.g. {@link #unary(String, String, EntityType, NodeRole)}).
     */
    private RandomVariable(String name, List<EntityType> argumentTypes,
                           List<String> argVars, List<String> states, NodeRole role) {
        this.name = name;
        this.argumentTypes = List.copyOf(argumentTypes);
        this.argVars = List.copyOf(argVars);
        this.states = List.copyOf(states);
        this.role = role;
    }

    /**
     * Create a zero-argument (propositional) random variable.
     */
    public static RandomVariable propositional(String name, NodeRole role) {
        return new RandomVariable(name, List.of(), role);
    }

    /**
     * Create a unary random variable parameterized by one entity type.
     *
     * <p>The default arg-var name is {@code TypeName_0}, preserving backward compatibility.</p>
     */
    public static RandomVariable unary(String name, EntityType argType, NodeRole role) {
        return new RandomVariable(name, List.of(argType), role);
    }

    /**
     * Create a unary random variable with an explicit logical-variable name for its argument.
     *
     * <p>Use this factory when building relational MFrags where the argument variable must match
     * a named logical variable in context constraints (e.g., {@code "X"} or {@code "Y"}).
     * Unlike {@link #unary(String, EntityType, NodeRole)}, the arg-var name stored here is
     * {@code argVar}, so {@link SSBNGenerator} will key Cartesian-product dimensions by
     * {@code argVar} rather than the default {@code TypeName_0}.</p>
     *
     * @param name    the random-variable name (e.g., {@code "isActive"})
     * @param argVar  the logical-variable name for the sole argument (e.g., {@code "X"} or {@code "Y"})
     * @param argType the entity type the argument ranges over
     * @param role    the role of this RV in its home MFrag
     */
    public static RandomVariable unary(String name, String argVar, EntityType argType, NodeRole role) {
        return new RandomVariable(name, List.of(argType), List.of(argVar),
                List.of("FALSE", "TRUE"), role);
    }

    /**
     * Create a binary-argument random variable (e.g., relationship between two entities).
     */
    public static RandomVariable binary(String name, EntityType argType1,
                                         EntityType argType2, NodeRole role) {
        return new RandomVariable(name, List.of(argType1, argType2), role);
    }

    public String getName() {
        return name;
    }

    public List<EntityType> getArgumentTypes() {
        return argumentTypes;
    }

    /**
     * Return the logical-variable name for each argument position.
     *
     * <p>For RVs created with the default constructors, position {@code i} has name
     * {@code TypeName_i} (e.g., {@code "Person_0"}), which is byte-identical to the key
     * that {@link SSBNGenerator} historically computed — so all existing theories behave
     * identically. For RVs created via
     * {@link #unary(String, String, EntityType, NodeRole)}, the explicit name supplied there
     * is used (e.g., {@code "X"} or {@code "Y"}).</p>
     *
     * @return immutable list of one logical-variable name per argument, in order
     */
    public List<String> getArgVars() {
        return argVars;
    }

    public int getArity() {
        return argumentTypes.size();
    }

    public List<String> getStates() {
        return states;
    }

    public int getCardinality() {
        return states.size();
    }

    public NodeRole getRole() {
        return role;
    }

    public boolean isResident() {
        return role == NodeRole.RESIDENT;
    }

    public boolean isInput() {
        return role == NodeRole.INPUT;
    }

    public boolean isContext() {
        return role == NodeRole.CONTEXT;
    }

    /**
     * Ground this random variable with specific entity IDs, producing a unique
     * variable name for the BN.
     *
     * @param entityIds entity IDs substituted for each argument position
     * @return grounded variable name, e.g. "isActive(node_123)"
     */
    public String ground(List<String> entityIds) {
        if (entityIds.size() != argumentTypes.size()) {
            throw new IllegalArgumentException(
                    "Expected " + argumentTypes.size() + " arguments for " + name +
                            ", got " + entityIds.size());
        }
        if (entityIds.isEmpty()) {
            return name;
        }
        return name + "(" + String.join(",", entityIds) + ")";
    }

    /**
     * Generate all possible groundings for this RV given the registered entities.
     *
     * @return list of entity ID tuples (one per grounding)
     */
    public List<List<String>> allGroundings() {
        if (argumentTypes.isEmpty()) {
            return List.of(List.of());
        }

        // Cartesian product of entity sets
        List<List<String>> result = new ArrayList<>();
        result.add(new ArrayList<>());

        for (EntityType argType : argumentTypes) {
            List<List<String>> newResult = new ArrayList<>();
            for (List<String> partial : result) {
                for (String entityId : argType.getEntityIds()) {
                    List<String> extended = new ArrayList<>(partial);
                    extended.add(entityId);
                    newResult.add(extended);
                }
            }
            result = newResult;
        }

        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RandomVariable that)) return false;
        return name.equals(that.name) && argumentTypes.equals(that.argumentTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, argumentTypes);
    }

    @Override
    public String toString() {
        if (argumentTypes.isEmpty()) return name;
        return name + "(" + String.join(", ",
                argumentTypes.stream().map(EntityType::getTypeName).toList()) + ")";
    }
}
