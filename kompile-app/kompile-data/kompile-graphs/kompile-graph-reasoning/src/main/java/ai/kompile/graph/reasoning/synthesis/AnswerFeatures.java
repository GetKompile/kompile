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
package ai.kompile.graph.reasoning.synthesis;

import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.CandidateSignal;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.SignalGroup;

import java.util.List;

/**
 * Turns a candidate's calibrated signals into a fixed-length numeric feature vector — the bridge from
 * the subjective-logic signals to a <b>small learned scorer</b> ({@link LogisticAnswerScorer}). The idea:
 * the same six signals that the algebraic fold combines with hand-coded operators become the FEATURES a
 * model learns to weight from labeled (question, answer, correct?) data. Improving a signal improves a
 * feature; the model then learns how much to trust each.
 *
 * <p>Schema (per signal group, in a stable order): mean projected probability, mean uncertainty, and a
 * present/absent flag — so a missing signal (e.g. no KB verification for this query) is a first-class
 * input the model can key on, rather than a silent zero.</p>
 */
public final class AnswerFeatures {

    private AnswerFeatures() {
    }

    /** Groups in the fixed feature order. */
    private static final SignalGroup[] GROUPS = {
            SignalGroup.RETRIEVAL, SignalGroup.ENGINE, SignalGroup.TYPE, SignalGroup.CONSISTENCY
    };

    /** Feature names, aligned with {@link #extract}. */
    public static final List<String> NAMES = List.of(
            "retrieval_e", "retrieval_u", "retrieval_present",
            "engine_e", "engine_u", "engine_present",
            "type_e", "type_u", "type_present",
            "cons_e", "cons_u", "cons_present");

    public static final int DIM = 12;

    /** Extract the feature vector for one candidate's signals. Absent group → (E=0, u=1, present=0). */
    public static double[] extract(List<CandidateSignal> signals) {
        double[] f = new double[DIM];
        for (int g = 0; g < GROUPS.length; g++) {
            double sumE = 0.0;
            double sumU = 0.0;
            int n = 0;
            if (signals != null) {
                for (CandidateSignal s : signals) {
                    if (s.group() == GROUPS[g]) {
                        sumE += s.opinion().expectation();
                        sumU += s.opinion().uncertainty();
                        n++;
                    }
                }
            }
            int base = g * 3;
            if (n > 0) {
                f[base] = sumE / n;
                f[base + 1] = sumU / n;
                f[base + 2] = 1.0;
            } else {
                f[base] = 0.0;
                f[base + 1] = 1.0; // no evidence → maximal uncertainty
                f[base + 2] = 0.0;
            }
        }
        return f;
    }
}
