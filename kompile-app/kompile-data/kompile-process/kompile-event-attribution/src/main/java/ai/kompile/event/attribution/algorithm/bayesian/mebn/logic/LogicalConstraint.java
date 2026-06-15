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

import java.util.Map;
import java.util.Set;

/**
 * A first-order logical constraint used as a context node in MEBN MFrags.
 *
 * <p>Context constraints determine whether an MFrag's local distribution applies
 * to a particular set of entity bindings. They are evaluated against a
 * {@link KnowledgeBase} (the KG state) during SSBN generation.</p>
 *
 * <p>Supported constraint types form a first-order logic over the knowledge graph:</p>
 * <ul>
 *   <li><b>Predicates</b>: atomic tests on entity properties and relationships</li>
 *   <li><b>Connectives</b>: AND, OR, NOT, IMPLIES</li>
 *   <li><b>Quantifiers</b>: FOR_ALL, EXISTS over entity types</li>
 * </ul>
 *
 * <p>Entity bindings are provided as a map from argument position names to
 * concrete entity IDs. For example, evaluating {@code hasEdge(x, y)} with
 * bindings {x: "node_1", y: "node_2"} checks if an edge exists between
 * those two KG nodes.</p>
 */
public interface LogicalConstraint {

    /**
     * Evaluate this constraint against the knowledge base with the given entity bindings.
     *
     * @param kb       the knowledge base (KG state)
     * @param bindings map from variable names to concrete entity IDs
     * @return TRUE if the constraint is satisfied, FALSE otherwise
     */
    boolean evaluate(KnowledgeBase kb, Map<String, String> bindings);

    /**
     * Get the free variables in this constraint (those that need bindings).
     */
    Set<String> getFreeVariables();

    /**
     * Human-readable description of this constraint for debugging.
     */
    String describe();
}
