/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.List;

/** A rebuildable rule projection over a reasoning graph. */
@FunctionalInterface
public interface QuantitativeRuleCatalog {

    List<QuantitativeRule> rules(ReasoningGraph graph);
}
