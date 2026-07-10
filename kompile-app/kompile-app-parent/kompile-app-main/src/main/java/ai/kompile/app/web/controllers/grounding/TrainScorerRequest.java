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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Request body for POST /api/kb-grounding/train-scorer: train the answer-scorer from a golden QA set.
 * Each {@link GoldenItem} names the correct answer for a query; the pipeline synthesizes candidate
 * features, labels the matching candidate positive, and fits + held-out-evaluates the model.
 * {@code savePath} (optional) writes the trained model JSON so the live re-rank picks it up.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrainScorerRequest(
        List<GoldenItem> golden,
        Double heldOutFraction,
        String savePath
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoldenItem(String query, String correctAnswer, Long factSheetId, String expectedType) {
    }
}
