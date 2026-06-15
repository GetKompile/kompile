/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn.resolution;

import ai.kompile.knowledgegraph.resolution.ProbabilisticScorer;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * MEBN-based implementation of {@link ProbabilisticScorer}.
 *
 * <p>Delegates to {@link ProbabilisticEntityResolution} which constructs
 * an entity resolution MTheory, generates an SSBN, and runs variable
 * elimination to compute P(isSameEntity = TRUE | observed signals).</p>
 *
 * <p>This bean is auto-discovered by Spring and injected into
 * {@code GraphCompactionService} as Signal 9 when the
 * {@code kompile-event-attribution} module is on the classpath.</p>
 */
@Component
public class MebnProbabilisticScorer implements ProbabilisticScorer {

    private final ProbabilisticEntityResolution resolution;

    @Autowired
    public MebnProbabilisticScorer(KnowledgeGraphService graphService) {
        this.resolution = new ProbabilisticEntityResolution(graphService);
    }

    @Override
    public Optional<Double> score(String nodeIdA, String nodeIdB,
                                   double nameSimilarity, double propertyScore,
                                   boolean typeCompatible) {
        return resolution.computeMatchProbability(
                nodeIdA, nodeIdB, nameSimilarity, propertyScore, typeCompatible);
    }
}
