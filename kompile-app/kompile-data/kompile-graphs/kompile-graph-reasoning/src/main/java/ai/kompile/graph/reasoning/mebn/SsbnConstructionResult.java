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

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;

/**
 * Result of an SSBN generation pass that includes a construction log.
 *
 * <p>The {@link #network()} field contains the grounded Bayesian network as usual.
 * The {@link #log()} field contains a per-node record of every construction
 * decision (MFrag, OV substitution, context-constraint outcomes,
 * {@link SSBNGenerator.DistributionMode}) plus any nodes removed by
 * Bayes-Ball pruning.</p>
 *
 * @param network the grounded Bayesian network ready for inference
 * @param log     the detailed construction log for diagnostic/trace purposes
 */
public record SsbnConstructionResult(BayesianNetwork network, SsbnConstructionLog log) {}
