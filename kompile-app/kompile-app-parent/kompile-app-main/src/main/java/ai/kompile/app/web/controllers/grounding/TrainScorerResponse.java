/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import java.util.List;

/**
 * Response body for POST /api/kb-grounding/train-scorer. Reports the held-out evaluation — most
 * importantly {@code learnedMrr} vs {@code retrievalBaselineMrr} and {@code beatsBaseline} (the
 * ship-gate) — plus the learned per-feature weights so operators can see what the model relies on.
 */
public record TrainScorerResponse(
        int trainRows,
        int heldOutQueries,
        double trainLogLoss,
        double heldOutLogLoss,
        double heldOutAccuracy,
        double learnedMrr,
        double retrievalBaselineMrr,
        double algebraicFoldMrr,
        boolean beatsBaseline,
        boolean beatsFold,
        double learnedRecallAt1,
        double learnedRecallAt3,
        double learnedNdcgAt3,
        List<String> featureNames,
        double[] weights,
        double bias
) {}
