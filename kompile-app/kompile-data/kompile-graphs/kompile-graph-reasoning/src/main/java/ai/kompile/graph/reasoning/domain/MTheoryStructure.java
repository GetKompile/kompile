/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.domain;

import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;

import java.util.List;

/**
 * The structure of a MEBN theory: its fragments, their typed resident and input
 * variables, context constraints, and parent→child edges with learned strengths.
 *
 * <p>A theory built from the graph carries Java local distributions, so it cannot be
 * serialized; this is the plain-data view of it that can cross a process boundary.
 */
public record MTheoryStructure(String name, List<Fragment> fragments) {

    public static MTheoryStructure of(MTheory theory) {
        return new MTheoryStructure(
                theory.getName(),
                theory.getMFrags().stream().map(Fragment::of).toList());
    }

    /** One MEBN fragment: variables by role, context constraints, and parent→child edges. */
    public record Fragment(String name, List<Variable> residentNodes, List<Variable> inputNodes,
                           List<String> contexts, List<Edge> edges) {
        public static Fragment of(MFrag fragment) {
            return new Fragment(
                    fragment.getName(),
                    fragment.getResidentNodes().stream().map(Variable::of).toList(),
                    fragment.getInputNodes().stream().map(Variable::of).toList(),
                    fragment.getContextConstraints().stream().map(Object::toString).toList(),
                    fragment.getEdgeStrengths().entrySet().stream()
                            .map(e -> Edge.parse(e.getKey(), e.getValue())).toList());
        }
    }

    /** A parameterized random variable: name, entity-type signature, states, and MEBN role. */
    public record Variable(String name, String signature, List<String> states, String role) {
        public static Variable of(RandomVariable rv) {
            return new Variable(rv.getName(), rv.toString(), rv.getStates(), rv.getRole().name());
        }
    }

    /** A directed parent→child edge inside a fragment with its learned noisy-OR strength. */
    public record Edge(String parent, String child, double strength) {
        /** Parses an edge-strength key of the form {@code parent->child}. */
        public static Edge parse(String key, double strength) {
            int idx = key.indexOf("->");
            String parent = idx >= 0 ? key.substring(0, idx).trim() : key;
            String child = idx >= 0 ? key.substring(idx + 2).trim() : "";
            return new Edge(parent, child, strength);
        }
    }
}
