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

import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * CRAG-style corrective retrieval policy (grounded-RAG lit-gap rec 3). When the evidence-sufficiency
 * grade is below threshold, escalate the graph search mode ({@code LOCAL → HYBRID → GLOBAL}) and
 * re-retrieve before the answer path abstains — "act on the grade before giving up". The grade comes
 * from {@link RetrievalSufficiencyGate} (a small scorer, not an LLM judge — the literature's F3
 * lesson). Opt-in via {@code kbCorrectiveRetrievalEnabled}.
 */
@Component
public class CorrectiveRetrievalPolicy {

    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    public boolean isEnabled() {
        KbConfig c = kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
        return c.isCorrectiveRetrievalEnabled();
    }

    /**
     * The ordered graph search modes to try, broadest-last, given the current mode. Pure — unit-testable.
     * {@code LOCAL} widens to {@code HYBRID} then {@code GLOBAL}; {@code HYBRID} to {@code GLOBAL};
     * {@code GLOBAL} is already the broadest (empty). Unknown/blank/reasoning modes (e.g. CAUSAL) fall
     * back to trying {@code HYBRID} then {@code GLOBAL}.
     */
    public static List<String> escalationLadder(String currentSearchType) {
        String c = currentSearchType == null ? "" : currentSearchType.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "LOCAL" -> List.of("HYBRID", "GLOBAL");
            case "HYBRID" -> List.of("GLOBAL");
            case "GLOBAL" -> List.of();
            default -> List.of("HYBRID", "GLOBAL");
        };
    }
}
