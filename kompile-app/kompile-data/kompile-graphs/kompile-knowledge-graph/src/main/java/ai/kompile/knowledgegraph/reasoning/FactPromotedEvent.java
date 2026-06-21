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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.confidence.StrengthBand;
import org.springframework.context.ApplicationEvent;

/**
 * Spring event published when a fact transitions to a higher {@link StrengthBand}
 * across successive cascade corroboration runs.
 *
 * <p>Published by {@link FactPromotionTracker} whenever
 * {@code newBand.ordinal() > oldBand.ordinal()}.
 * Consumers can listen via {@code @EventListener} to trigger downstream actions such
 * as surfacing promotions in the UI, triggering re-export, or alerting on ESTABLISHED facts.</p>
 */
public class FactPromotedEvent extends ApplicationEvent {

    private final long factSheetId;
    private final String atomKey;
    private final StrengthBand oldBand;
    private final StrengthBand newBand;
    private final String runId;
    private final int corroborationCount;

    public FactPromotedEvent(Object source, long factSheetId, String atomKey,
                              StrengthBand oldBand, StrengthBand newBand,
                              String runId, int corroborationCount) {
        super(source);
        this.factSheetId = factSheetId;
        this.atomKey = atomKey;
        this.oldBand = oldBand;
        this.newBand = newBand;
        this.runId = runId;
        this.corroborationCount = corroborationCount;
    }

    public long getFactSheetId() { return factSheetId; }
    public String getAtomKey() { return atomKey; }
    public StrengthBand getOldBand() { return oldBand; }
    public StrengthBand getNewBand() { return newBand; }
    public String getRunId() { return runId; }
    public int getCorroborationCount() { return corroborationCount; }

    @Override
    public String toString() {
        return "FactPromotedEvent{factSheetId=" + factSheetId +
               ", atomKey='" + atomKey + '\'' +
               ", " + oldBand + " -> " + newBand +
               ", runId='" + runId + '\'' +
               ", corroborationCount=" + corroborationCount + '}';
    }
}
