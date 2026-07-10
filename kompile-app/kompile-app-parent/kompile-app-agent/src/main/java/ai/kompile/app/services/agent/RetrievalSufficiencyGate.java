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
package ai.kompile.app.services.agent;

import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Evidence-sufficiency gate for the grounded answer path (grounded-RAG lit-gap rec 2 — the
 * fabrication failure mode). Scores the retrieved evidence set; when the score is below
 * {@code kbEvidenceSufficiencyThreshold} the answer path should ABSTAIN ("insufficient evidence",
 * surfacing what was found via the sources panel) instead of letting the LLM generate ungrounded.
 * Opt-in via {@code kbEvidenceSufficiencyEnabled} (default false).
 *
 * <p>The score blends three cheap, always-available signals in [0,1]:
 * <ul>
 *   <li><b>strength</b> — the best (max) retrieval score: is there any strong evidence at all;</li>
 *   <li><b>consistency</b> — the mean retrieval score: is the set broadly on-topic;</li>
 *   <li><b>breadth</b> — {@code min(1, count / minSources)}: is there enough evidence.</li>
 * </ul>
 * {@code sufficiency = (strength + consistency + breadth) / 3}; zero sources → 0. Deliberately a
 * transparent heuristic (not an LLM judge — F3's "small grader" lesson); the threshold is the knob.</p>
 */
@Component
public class RetrievalSufficiencyGate {

    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    /** Outcome of a sufficiency assessment. */
    public record SufficiencyResult(boolean enabled, boolean sufficient, double score,
                                    double threshold, String summary) {}

    /** Assess whether the retrieved evidence is sufficient to answer, reading thresholds from managed config. */
    public SufficiencyResult assess(String query, List<RetrievedDoc> sources) {
        KbConfig c = kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
        double threshold = c.getEvidenceSufficiencyThreshold();
        double score = score(sources, c.getEvidenceSufficiencyMinSources());
        boolean sufficient = score >= threshold;
        return new SufficiencyResult(c.isEvidenceSufficiencyEnabled(), sufficient, score, threshold,
                buildSummary(sources, score));
    }

    /**
     * Pure sufficiency score in [0,1] over the retrieval set. Package-private + static so it is
     * unit-testable without Spring.
     */
    static double score(List<RetrievedDoc> sources, int minSources) {
        if (sources == null || sources.isEmpty()) {
            return 0.0;
        }
        double top = 0.0;
        double sum = 0.0;
        for (RetrievedDoc d : sources) {
            double s = clamp01(d.getScore() != null ? d.getScore() : 0.0);
            top = Math.max(top, s);
            sum += s;
        }
        double consistency = sum / sources.size();
        double breadth = Math.min(1.0, (double) sources.size() / Math.max(1, minSources));
        return clamp01((top + consistency + breadth) / 3.0);
    }

    /**
     * The abstention message. Does NOT enumerate raw source ids (the "sources" SSE panel already
     * renders them as cards — and raw ids must never be primary text; see Part X).
     */
    private static String buildSummary(List<RetrievedDoc> sources, double score) {
        int n = sources == null ? 0 : sources.size();
        String found = n == 0
                ? "No supporting sources were retrieved for this question."
                : "Retrieved " + n + (n == 1 ? " source" : " sources")
                        + ", but the match to your question was weak (see the sources panel for what was found).";
        return String.format(Locale.ROOT,
                "I don't have enough grounded evidence to answer this confidently (evidence sufficiency "
                        + "%.2f). %s Rather than guess, I'm flagging this as unanswerable from the current "
                        + "knowledge base.",
                score, found);
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
