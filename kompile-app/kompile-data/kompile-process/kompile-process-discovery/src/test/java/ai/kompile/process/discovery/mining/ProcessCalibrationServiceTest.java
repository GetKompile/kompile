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

import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.process.discovery.ProcessSuggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accept/dismiss learning loop: outcomes refit the Platt calibrator (once enough labels
 * exist) and the fitted parameters survive a reload — replacing the identity sigmoid the miner
 * previously recreated on every run.
 */
class ProcessCalibrationServiceTest {

    private static ProcessSuggestion mined(String id, double rawScore) {
        return ProcessSuggestion.builder()
                .id(id)
                .discoverySource("PROCESS_MINING")
                .rawConformanceScore(rawScore)
                .build();
    }

    private static ProcessCalibrationService service(Path dataDir) {
        ProcessCalibrationService svc = new ProcessCalibrationService();
        ReflectionTestUtils.setField(svc, "dataDir", dataDir.toString());
        return svc;
    }

    @Test
    void outcomes_refitCalibrator_andParamsSurviveReload(@TempDir Path dataDir) throws Exception {
        ProcessCalibrationService svc = service(dataDir);

        // 3 accepted high-conformance + 3 dismissed low-conformance suggestions.
        for (int i = 0; i < 3; i++) {
            svc.recordOutcome(mined("good-" + i, 0.9), true);
            svc.recordOutcome(mined("bad-" + i, 0.15), false);
        }

        Path params = dataDir.resolve("rules").resolve("process-calibration.json");
        assertTrue(Files.exists(params), "refit must persist fitted params once MIN_OUTCOMES reached");

        PlattCalibrator fitted = svc.loadCalibrator();
        double[] wb = fitted.getParams(ProcessCalibrationService.SIGNAL);
        assertFalse(wb[0] == 1.0 && wb[1] == 0.0, "fitted params must differ from the identity sigmoid");

        VerifyResult supported = VerifyResult.supported(0.9, List.of("observed:test"));
        double high = fitted.calibrate(0.9, ProcessCalibrationService.SIGNAL, supported);
        double low = fitted.calibrate(0.15, ProcessCalibrationService.SIGNAL, supported);
        assertTrue(high > low, "fitted calibration must separate accepted-like from dismissed-like scores");
    }

    @Test
    void belowMinOutcomes_keepsIdentity(@TempDir Path dataDir) {
        ProcessCalibrationService svc = service(dataDir);
        svc.recordOutcome(mined("one", 0.8), true);
        svc.recordOutcome(mined("two", 0.2), false);

        assertFalse(Files.exists(dataDir.resolve("rules").resolve("process-calibration.json")),
                "no refit below MIN_OUTCOMES — Platt on two points is noise");
        double[] wb = svc.loadCalibrator().getParams(ProcessCalibrationService.SIGNAL);
        assertEquals(1.0, wb[0], 1e-9);
        assertEquals(0.0, wb[1], 1e-9);
    }

    private static ProcessSuggestion richMined(String id, double rawScore, double confidence) {
        ProcessSuggestion s = mined(id, rawScore);
        s.setConfidence(confidence);
        s.setStructuredEvidence(new java.util.ArrayList<>(List.of(
                ProcessSuggestion.StructuredEvidence.builder()
                        .type("ENTAILED").description("x").score(confidence).build())));
        return s;
    }

    @Test
    void outcomes_fitRanker_thatSeparatesAcceptLikeFromDismissLike(@TempDir Path dataDir) {
        ProcessCalibrationService svc = service(dataDir);
        for (int i = 0; i < 3; i++) {
            svc.recordOutcome(richMined("good-" + i, 0.9, 0.85), true);
            svc.recordOutcome(richMined("bad-" + i, 0.15, 0.1), false);
        }

        assertTrue(Files.exists(dataDir.resolve("rules").resolve("process-ranker.json")),
                "both classes present at MIN_OUTCOMES — the ranker must fit and persist");
        Double acceptLike = svc.scoreSuggestion(richMined("probe-good", 0.9, 0.85));
        Double dismissLike = svc.scoreSuggestion(richMined("probe-bad", 0.15, 0.1));
        assertTrue(acceptLike != null && dismissLike != null);
        assertTrue(acceptLike > dismissLike,
                "the learned ranker must separate the profiles: " + acceptLike + " vs " + dismissLike);
    }

    @Test
    void oneClassHistory_neverFitsARanker(@TempDir Path dataDir) {
        ProcessCalibrationService svc = service(dataDir);
        for (int i = 0; i < 6; i++) {
            svc.recordOutcome(richMined("only-accepts-" + i, 0.8, 0.7), true);
        }
        assertFalse(Files.exists(dataDir.resolve("rules").resolve("process-ranker.json")),
                "a one-class fit ranks nothing — gate requires both classes");
        assertEquals(null, svc.scoreSuggestion(richMined("probe", 0.8, 0.7)));
    }

    @Test
    void staleFeatureSpace_isIgnoredOnLoad(@TempDir Path dataDir) throws Exception {
        ProcessCalibrationService svc = service(dataDir);
        Path rankerFile = dataDir.resolve("rules").resolve("process-ranker.json");
        Files.createDirectories(rankerFile.getParent());
        Files.writeString(rankerFile,
                "{\"featureNames\":[\"oldFeature\"],\"weights\":[2.0],\"bias\":0.5}");
        assertEquals(null, svc.scoreSuggestion(richMined("probe", 0.9, 0.9)),
                "a model fitted on a different feature space must never be applied");
    }

    @Test
    void nonMinedOrUnscoredSuggestions_areIgnored(@TempDir Path dataDir) {
        ProcessCalibrationService svc = service(dataDir);
        svc.recordOutcome(ProcessSuggestion.builder().id("legacy")
                .discoverySource("EMAIL_FLOW").rawConformanceScore(0.9).build(), true);
        svc.recordOutcome(ProcessSuggestion.builder().id("no-raw")
                .discoverySource("PROCESS_MINING").build(), true);
        svc.recordOutcome(null, true);

        assertFalse(Files.exists(dataDir.resolve("rules").resolve("process-calibration-outcomes.jsonl")),
                "only mined suggestions with a raw score are calibration signal");
    }
}
