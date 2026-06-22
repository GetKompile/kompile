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

import org.springframework.context.ApplicationEvent;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.Map;

/**
 * Fine-grained real-time progress event emitted by
 * {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator} at every
 * meaningful stage of the grounding cascade.
 *
 * <h3>Transport</h3>
 * <p>Published via Spring {@link org.springframework.context.ApplicationEventPublisher} (the same
 * publisher already held by the orchestrator). An app-main {@code @EventListener} (
 * {@code GroundingProgressStompBridge}) forwards every event to two STOMP topics:
 * <ul>
 *   <li>{@code /topic/grounding/{factSheetId}} — per-fact-sheet narrow subscription</li>
 *   <li>{@code /topic/grounding/all} — global fan-out for dashboards</li>
 * </ul>
 *
 * <h3>Stage values</h3>
 * <pre>
 * PROJECTION, PROGRAM_BUILD, ONTOLOGY_RULES, WEIGHT_RELOAD,
 * MAP_SOLVE, MATERIALIZE, PROMOTION, PSL_LEARNING,
 * JUSTIFICATION, CONTRADICTION, EPOCH, MEBN_LEARNING,
 * CONSENSUS, PRUNE_COMPACT, ONTOLOGY_CONFORMANCE, HEALTH, COMPLETE
 * </pre>
 *
 * <h3>Trigger values</h3>
 * <pre>CRAWL | CHANNEL | ASSERT | MANUAL | CASCADE</pre>
 *
 * <h3>Status values</h3>
 * <pre>STARTED | RUNNING | DONE | ERROR</pre>
 *
 * <h3>Module placement</h3>
 * <p>Lives in {@code kompile-knowledge-graph} (same module as the orchestrator) so that
 * app-main's bridge can depend on it without introducing a circular dependency.</p>
 */
public class GroundingProgressEvent extends ApplicationEvent {

    /** Stage constants — map 1:1 to the cascade steps in the orchestrator. */
    public static final String STAGE_PROJECTION             = "PROJECTION";
    public static final String STAGE_PROGRAM_BUILD          = "PROGRAM_BUILD";
    public static final String STAGE_ONTOLOGY_RULES         = "ONTOLOGY_RULES";
    public static final String STAGE_WEIGHT_RELOAD          = "WEIGHT_RELOAD";
    public static final String STAGE_MAP_SOLVE              = "MAP_SOLVE";
    public static final String STAGE_MATERIALIZE            = "MATERIALIZE";
    public static final String STAGE_PROMOTION              = "PROMOTION";
    public static final String STAGE_PSL_LEARNING           = "PSL_LEARNING";
    public static final String STAGE_JUSTIFICATION          = "JUSTIFICATION";
    public static final String STAGE_CONTRADICTION          = "CONTRADICTION";
    public static final String STAGE_EPOCH                  = "EPOCH";
    public static final String STAGE_MEBN_LEARNING          = "MEBN_LEARNING";
    public static final String STAGE_CONSENSUS              = "CONSENSUS";
    public static final String STAGE_PRUNE_COMPACT          = "PRUNE_COMPACT";
    public static final String STAGE_ONTOLOGY_CONFORMANCE   = "ONTOLOGY_CONFORMANCE";
    public static final String STAGE_HEALTH                 = "HEALTH";
    public static final String STAGE_COMPLETE               = "COMPLETE";

    /** Status constants. */
    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE    = "DONE";
    public static final String STATUS_ERROR   = "ERROR";

    /** Trigger constants. */
    public static final String TRIGGER_CRAWL    = "CRAWL";
    public static final String TRIGGER_CHANNEL  = "CHANNEL";
    public static final String TRIGGER_ASSERT   = "ASSERT";
    public static final String TRIGGER_MANUAL   = "MANUAL";
    public static final String TRIGGER_CASCADE  = "CASCADE";

    private final long factSheetId;
    private final String cascadeId;
    private final String trigger;
    private final String stage;
    private final String status;
    private final String message;
    private final int stepIndex;
    private final int totalSteps;
    @Nullable
    private final Map<String, Object> data;
    /**
     * Epoch-ms timestamp captured at event construction. Named {@code eventTimestamp} to avoid
     * shadowing the {@code final} {@link org.springframework.context.ApplicationEvent#getTimestamp()}
     * method (which returns the same epoch-ms value from the base class, but is final).
     */
    private final long eventTimestamp;

    /**
     * Full constructor.
     *
     * @param source      the event publisher (must not be null)
     * @param factSheetId the fact sheet being re-grounded
     * @param cascadeId   run ID / cascade ID (UUID string from the orchestrator's runId)
     * @param trigger     what triggered the cascade — one of the TRIGGER_* constants
     * @param stage       current stage name — one of the STAGE_* constants
     * @param status      stage status — one of the STATUS_* constants
     * @param message     human-readable progress message
     * @param stepIndex   zero-based index of this step in the cascade sequence
     * @param totalSteps  total number of steps in the cascade sequence
     * @param data        optional structured data (counts, deltas, etc.); may be null
     */
    public GroundingProgressEvent(Object source,
                                  long factSheetId,
                                  String cascadeId,
                                  String trigger,
                                  String stage,
                                  String status,
                                  String message,
                                  int stepIndex,
                                  int totalSteps,
                                  @Nullable Map<String, Object> data) {
        super(source);
        this.factSheetId    = factSheetId;
        this.cascadeId      = cascadeId;
        this.trigger        = trigger;
        this.stage          = stage;
        this.status         = status;
        this.message        = message;
        this.stepIndex      = stepIndex;
        this.totalSteps     = totalSteps;
        this.data           = (data != null) ? Collections.unmodifiableMap(data) : null;
        this.eventTimestamp = System.currentTimeMillis();
    }

    /** Convenience overload — no structured data payload. */
    public GroundingProgressEvent(Object source,
                                  long factSheetId,
                                  String cascadeId,
                                  String trigger,
                                  String stage,
                                  String status,
                                  String message,
                                  int stepIndex,
                                  int totalSteps) {
        this(source, factSheetId, cascadeId, trigger, stage, status, message, stepIndex, totalSteps, null);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public long getFactSheetId()  { return factSheetId; }
    public String getCascadeId()  { return cascadeId; }
    public String getTrigger()    { return trigger; }
    public String getStage()      { return stage; }
    public String getStatus()     { return status; }
    public String getMessage()    { return message; }
    public int getStepIndex()     { return stepIndex; }
    public int getTotalSteps()    { return totalSteps; }
    @Nullable
    public Map<String, Object> getData() { return data; }
    /** Epoch-ms timestamp at which this event was constructed. */
    public long getEventTimestamp()      { return eventTimestamp; }

    @Override
    public String toString() {
        return "GroundingProgressEvent{factSheet=" + factSheetId
                + ", cascadeId='" + cascadeId + '\''
                + ", trigger='" + trigger + '\''
                + ", stage='" + stage + '\''
                + ", status='" + status + '\''
                + ", step=" + stepIndex + "/" + totalSteps
                + '}';
    }
}
