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
package ai.kompile.event.observation.config;

/**
 * How an event's probability (the Beta-Binomial denominator) is computed from observations.
 * Selected via {@code event-observation-config.json}; default {@link #PRESENCE}.
 */
public enum OpportunityModel {

    /**
     * Conditional/presence probability — a true bounded probability.
     * Entity: docs-containing / docs-scanned. Connection: edges-present / endpoint-co-occurrences.
     */
    PRESENCE,

    /** Share-of-total — this event's count / total counts across the fact sheet. */
    RELATIVE_FREQUENCY,

    /** Occurrences per decayed crawl window, squashed to [0,1] for CPT use. */
    DECAYED_RATE;

    public static OpportunityModel fromString(String s) {
        if (s == null || s.isBlank()) {
            return PRESENCE;
        }
        try {
            return OpportunityModel.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PRESENCE;
        }
    }
}
