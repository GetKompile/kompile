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

package ai.kompile.process.discovery.mining.trace;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import org.springframework.context.ApplicationEvent;

/**
 * Published when process mining builds a walkable reasoning trace for a suggestion.
 */
public class ProcessReasoningTraceEvent extends ApplicationEvent {

    private final String suggestionId;
    private final Long factSheetId;
    private final ReasoningTrace trace;

    public ProcessReasoningTraceEvent(Object source, String suggestionId, Long factSheetId,
                                      ReasoningTrace trace) {
        super(source);
        this.suggestionId = suggestionId;
        this.factSheetId = factSheetId;
        this.trace = trace;
    }

    public String getSuggestionId() {
        return suggestionId;
    }

    public Long getFactSheetId() {
        return factSheetId;
    }

    public ReasoningTrace getTrace() {
        return trace;
    }
}
