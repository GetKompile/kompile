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

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published when an agent-visible KB fact is retracted (belief revision removed it or a caller
 * withdrew it) — the retraction counterpart of {@link AgentFactAssertedEvent}.
 *
 * <h3>Module placement</h3>
 * <p>Like {@link AgentFactAssertedEvent}, this event lives in {@code kompile-knowledge-graph}
 * rather than {@code kompile-graph-change-tracking} to avoid a circular dependency between the
 * two modules.</p>
 *
 * <h3>Consumers</h3>
 * <p>Subscription fan-out (SSE / MCP long-poll) surfaces the retraction to listening agents so
 * they can invalidate cached conclusions that depended on the atom.</p>
 */
@Getter
public class AgentFactRetractedEvent extends ApplicationEvent {

    private final long factSheetId;
    private final String atomKey;
    private final int retractedCount;
    private final int remainingCount;

    /**
     * Create a new {@code AgentFactRetractedEvent}.
     *
     * @param source      the event publisher (must not be null)
     * @param factSheetId the fact sheet id
     * @param atomKey     the canonical atom key that was retracted (e.g. {@code "trusts(Alice, Bob)"})
     */
    public AgentFactRetractedEvent(Object source, long factSheetId, String atomKey) {
        this(source, factSheetId, atomKey, 1, 0);
    }

    /**
     * Create a new {@code AgentFactRetractedEvent} with additional retraction context.
     *
     * @param source         the event publisher (must not be null)
     * @param factSheetId    the fact sheet id
     * @param atomKey        the canonical atom key that was retracted (e.g. {@code "trusts(Alice, Bob)"})
     * @param retractedCount number of atoms retracted in this operation
     * @param remainingCount number of atoms remaining after retraction
     */
    public AgentFactRetractedEvent(Object source, long factSheetId, String atomKey,
                                    int retractedCount, int remainingCount) {
        super(source);
        this.factSheetId = factSheetId;
        this.atomKey = atomKey;
        this.retractedCount = retractedCount;
        this.remainingCount = remainingCount;
    }
}
