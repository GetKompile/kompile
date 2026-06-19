/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.event.observation.service;

import ai.kompile.event.observation.config.OpportunityModel;

/**
 * Turns raw graph signals into a Binomial {@link Observation} according to the configured
 * {@link OpportunityModel} — i.e. it decides the Beta-Binomial denominator ("what counts as a
 * non-occurrence"). Pure and unit-testable.
 */
public final class OpportunityCalculator {

    /** Pseudo-count scale used to turn a [0,1] connection strength into integer Binomial counts. */
    static final long CONNECTION_SCALE = 10L;

    private OpportunityCalculator() {
    }

    /**
     * Entity occurrence — "entities are events in the number of times they occur".
     *
     * @param model          the configured opportunity model
     * @param entityOccur    this entity's raw occurrence count this scan (mentions / references)
     * @param docsScanned    number of documents scanned (presence denominator)
     * @param totalOccur     total entity occurrences across the scope (relative-frequency denominator)
     * @param baselineOccur  typical per-entity occurrence (decayed-rate saturation constant)
     */
    public static Observation forEntity(OpportunityModel model, long entityOccur, long docsScanned,
                                        long totalOccur, long baselineOccur) {
        long occ = Math.max(0, entityOccur);
        return switch (model == null ? OpportunityModel.PRESENCE : model) {
            // P(entity appears in a document): cap successes at the number of documents.
            case PRESENCE -> new Observation(Math.min(occ, Math.max(1, docsScanned)), Math.max(1, docsScanned));
            // Share of all entity occurrences in the scope.
            case RELATIVE_FREQUENCY -> new Observation(occ, Math.max(occ, totalOccur));
            // Saturating intensity: occ / (occ + baseline) — at baseline rate ⇒ ~0.5.
            case DECAYED_RATE -> new Observation(occ, occ + Math.max(1, baselineOccur));
        };
    }

    /**
     * Connection occurrence — "connections produce observed events". The per-observation strength is
     * the edge's {@code weight * confidence}; repeated observation across crawls reinforces it and
     * decay forgets stale connections.
     *
     * @param model            the configured opportunity model
     * @param strength         edge weight * confidence in [0,1]
     * @param totalConnections total connection edges in scope (relative-frequency denominator)
     */
    public static Observation forConnection(OpportunityModel model, double strength, long totalConnections) {
        double s = Math.max(0.0, Math.min(1.0, strength));
        return switch (model == null ? OpportunityModel.PRESENCE : model) {
            case PRESENCE, DECAYED_RATE -> new Observation(Math.round(s * CONNECTION_SCALE), CONNECTION_SCALE);
            // Share of all connections in the scope (rare connection types ⇒ lower probability).
            case RELATIVE_FREQUENCY -> new Observation(1, Math.max(1, totalConnections));
        };
    }
}
