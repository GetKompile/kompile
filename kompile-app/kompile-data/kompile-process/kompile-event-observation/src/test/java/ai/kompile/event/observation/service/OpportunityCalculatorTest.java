/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.observation.service;

import ai.kompile.event.observation.config.OpportunityModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpportunityCalculatorTest {

    @Test
    void presenceEntityCapsSuccessesAtDocsScanned() {
        Observation o = OpportunityCalculator.forEntity(OpportunityModel.PRESENCE, 100, 10, 500, 5);
        assertEquals(10, o.opportunities());
        assertEquals(10, o.occurrences()); // capped at docs scanned
    }

    @Test
    void relativeFrequencyEntityIsShareOfTotal() {
        Observation o = OpportunityCalculator.forEntity(OpportunityModel.RELATIVE_FREQUENCY, 20, 10, 100, 5);
        assertEquals(20, o.occurrences());
        assertEquals(100, o.opportunities());
    }

    @Test
    void decayedRateEntityIsHalfAtBaseline() {
        Observation o = OpportunityCalculator.forEntity(OpportunityModel.DECAYED_RATE, 5, 10, 100, 5);
        assertEquals(5, o.occurrences());
        assertEquals(10, o.opportunities()); // 5 + baseline 5
        assertEquals(0.5, (double) o.occurrences() / o.opportunities(), 1e-9);
    }

    @Test
    void presenceConnectionScalesByStrength() {
        Observation o = OpportunityCalculator.forConnection(OpportunityModel.PRESENCE, 0.8, 100);
        assertEquals(OpportunityCalculator.CONNECTION_SCALE, o.opportunities());
        assertEquals(8, o.occurrences()); // round(0.8 * 10)
    }

    @Test
    void relativeFrequencyConnectionIsShareOfAllConnections() {
        Observation o = OpportunityCalculator.forConnection(OpportunityModel.RELATIVE_FREQUENCY, 0.8, 50);
        assertEquals(1, o.occurrences());
        assertEquals(50, o.opportunities());
    }

    @Test
    void observationEnforcesOccurrencesLeqOpportunities() {
        Observation o = new Observation(10, 3);
        assertEquals(10, o.occurrences());
        assertEquals(10, o.opportunities());
    }
}
