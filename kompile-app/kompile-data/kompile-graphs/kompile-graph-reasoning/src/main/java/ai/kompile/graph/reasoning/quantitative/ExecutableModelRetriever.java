/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.model.ReasoningGraph;

/**
 * Retrieves a ranked, executable dependency closure from graph-resident facts and rules.
 *
 * <p>Implementations may build transient indices, but the supplied graph remains the sole
 * information source.</p>
 */
@FunctionalInterface
public interface ExecutableModelRetriever {

    ModelRetrieval retrieve(ReasoningGraph graph, QuantitativeQuery query);
}
