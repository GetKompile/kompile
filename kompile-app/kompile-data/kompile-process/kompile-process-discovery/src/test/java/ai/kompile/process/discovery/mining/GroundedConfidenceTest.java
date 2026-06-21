/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining;

import ai.kompile.graph.reasoning.fol.grounding.GroundedElement;
import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;
import ai.kompile.process.discovery.mining.convert.ProcessTreeToSuggestion;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that grounded conversion produces calibrated confidences per step and that the
 * aggregate confidence is wired correctly on the suggestion.
 */
class GroundedConfidenceTest {

    private static EventLog buildLog(String... traces) {
        List<Trace> ts = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 9, 0);
        int caseId = 0;
        for (String trace : traces) {
            String cid = "c" + caseId++;
            List<Event> events = new ArrayList<>();
            int i = 0;
            for (String a : trace.trim().split("\\s+")) {
                events.add(Event.of(cid, a, base.plusMinutes(i), cid + "-n" + i));
                i++;
            }
            ts.add(new Trace(cid, events));
        }
        return new EventLog(ts);
    }

    @Test
    void convertGrounded_withNullKb_fallsBackToUngroundedConfidence() {
        EventLog log = buildLog("a b c", "a b c", "a b c");
        ProcessTree tree = new InductiveMiner().mine(log);

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convertGrounded(
                tree, log, "Test", null, null, null);

        assertNotNull(suggestion);
        assertTrue(suggestion.getConfidence() >= 0.1, "confidence must be at least floor 0.1");
        assertTrue(suggestion.getGroundedSteps().isEmpty(),
                "no grounded steps when KB is null");
    }

    @Test
    void convertGrounded_withSupportedKb_producesGroundedStepsWithCalibratedConfidence() {
        EventLog log = buildLog("a b c", "a b c");
        ProcessTree tree = new InductiveMiner().mine(log);

        // Stub KbGroundingService: everything SUPPORTED with confidence 0.8
        KbGroundingService stubKb = new KbGroundingService() {
            @Override
            public VerifyResult verify(long factSheetId, String atomKey) {
                return VerifyResult.supported(0.8, List.of("observed: " + atomKey));
            }
        };
        PlattCalibrator calibrator = new PlattCalibrator();

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convertGrounded(
                tree, log, "Grounded", stubKb, calibrator, 42L);

        assertNotNull(suggestion);
        assertFalse(suggestion.getGroundedSteps().isEmpty(),
                "grounded steps must be populated when KB is wired");
        for (GroundedElement<SuggestedStep> ge : suggestion.getGroundedSteps()) {
            assertNotNull(ge.verifyResult(), "each grounded step must have a verify result");
            assertTrue(ge.calibratedConfidence() >= 0.0 && ge.calibratedConfidence() <= 1.0,
                    "calibrated confidence must be in [0,1]");
            assertNotNull(ge.band(), "band must not be null");
            assertTrue(ge.isVerified(), "SUPPORTED result must be verified");
            assertFalse(ge.atomKey().isBlank(), "atom key must be set");
        }
        // Aggregate confidence must exceed the floor
        assertTrue(suggestion.getConfidence() > 0.1,
                "SUPPORTED KB entries should yield confidence above floor");
    }

    @Test
    void convertGrounded_withRefutedKb_yieldsZeroConfidence() {
        EventLog log = buildLog("a b c");
        ProcessTree tree = new InductiveMiner().mine(log);

        KbGroundingService refutingKb = new KbGroundingService() {
            @Override
            public VerifyResult verify(long factSheetId, String atomKey) {
                return VerifyResult.refuted(0.9, List.of("refuted: " + atomKey));
            }
        };

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convertGrounded(
                tree, log, "Refuted", refutingKb, new PlattCalibrator(), 1L);

        assertEquals(0.0, suggestion.getConfidence(), 1e-9,
                "any REFUTED step must drive aggregate confidence to 0.0");
    }

    @Test
    void convertGrounded_withUnknownKb_cappedAtFloor() {
        EventLog log = buildLog("x y z");
        ProcessTree tree = new InductiveMiner().mine(log);

        KbGroundingService unknownKb = new KbGroundingService() {
            @Override
            public VerifyResult verify(long factSheetId, String atomKey) {
                return VerifyResult.unknown();
            }
        };

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convertGrounded(
                tree, log, "Unknown", unknownKb, new PlattCalibrator(), 1L);

        // All UNKNOWN → calibrate returns min(raw, 0.3), but aggregate = 0.1 (floor, no SUPPORTED)
        assertEquals(0.1, suggestion.getConfidence(), 1e-9,
                "all-UNKNOWN KB should fall back to the 0.1 floor");
    }
}
