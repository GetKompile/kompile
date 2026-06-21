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
package ai.kompile.knowledgegraph.grounding;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.tms.ContradictionDetector;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * Spring event published by {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator}
 * at STEP 7 of the grounding cascade when contradicting fact pairs are detected via
 * {@link ContradictionDetector#findFactContradictions}.
 *
 * <p>Consumers can listen via {@code @EventListener} to trigger alerts, logging,
 * or higher-level conflict resolution workflows outside the orchestrator.</p>
 */
public class ContradictionDetectedEvent extends ApplicationEvent {

    private final long factSheetId;
    private final String runId;
    private final List<ContradictionDetector.Pair<Fact, Fact>> contradictions;

    public ContradictionDetectedEvent(Object source, long factSheetId, String runId,
                                       List<ContradictionDetector.Pair<Fact, Fact>> contradictions) {
        super(source);
        this.factSheetId = factSheetId;
        this.runId = runId;
        this.contradictions = List.copyOf(contradictions);
    }

    public long getFactSheetId() { return factSheetId; }
    public String getRunId() { return runId; }
    public List<ContradictionDetector.Pair<Fact, Fact>> getContradictions() { return contradictions; }

    @Override
    public String toString() {
        return "ContradictionDetectedEvent{factSheetId=" + factSheetId +
               ", runId='" + runId + '\'' +
               ", count=" + contradictions.size() + '}';
    }
}
