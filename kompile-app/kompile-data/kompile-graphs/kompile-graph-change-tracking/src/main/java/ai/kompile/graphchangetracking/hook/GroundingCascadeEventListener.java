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
package ai.kompile.graphchangetracking.hook;

import ai.kompile.gateway.core.gateway.channel.ChannelMessageReceivedEvent;
import ai.kompile.graphchangetracking.event.EdgeMutationEvent;
import ai.kompile.graphchangetracking.event.GraphBatchMutationEvent;
import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import ai.kompile.graphchangetracking.event.NodeMutationEvent;
import ai.kompile.knowledgegraph.grounding.AgentFactAssertedEvent;
import ai.kompile.knowledgegraph.grounding.AgentFactRetractedEvent;
import ai.kompile.knowledgegraph.grounding.GroundingProgressEvent;
import ai.kompile.knowledgegraph.grounding.GroundingResetPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Spring event listener that drives the grounding cascade in response to graph-mutation,
 * channel-message, and agent-fact-assertion events.
 *
 * <h3>Why this class is separate from {@link GroundingCascadeHook}</h3>
 * <p>{@link GroundingCascadeHook} implements {@link GroundingResetPort}. When Spring's
 * {@code AsyncAnnotationBeanPostProcessor} runs with {@code proxyTargetClass=false} (the
 * default), it wraps any bean that implements at least one interface as a <em>JDK dynamic
 * proxy</em>. JDK proxies only expose interface methods, so {@code @EventListener} methods
 * that live on the concrete class but not on the interface become invisible to
 * {@code EventListenerMethodProcessor}, causing a {@code BeanInitializationException} at
 * startup.</p>
 *
 * <p>This class intentionally implements <strong>no interface</strong>. When
 * {@code AsyncAnnotationBeanPostProcessor} encounters a bean with no interface it always
 * falls back to CGLIB subclass proxying, regardless of the {@code proxyTargetClass} flag.
 * CGLIB proxies expose all public methods of the concrete class, so the three
 * {@code @EventListener} methods below are always discoverable by
 * {@code EventListenerMethodProcessor}.</p>
 *
 * <p>The actual cascade logic lives in {@link GroundingCascadeHook}; this class merely
 * receives the events and delegates via the {@link GroundingResetPort} interface, keeping
 * the grounding cascade operational when the subprocess toggle is on or off.</p>
 */
@Component
@Slf4j
public class GroundingCascadeEventListener {

    private final GroundingResetPort groundingResetPort;

    @Autowired
    public GroundingCascadeEventListener(@Nullable GroundingResetPort groundingResetPort) {
        this.groundingResetPort = groundingResetPort;
    }

    // ── Event listeners ─────────────────────────────────────────────────────────

    /**
     * React to a completed graph changeset (crawl / channel extraction).
     *
     * <p>Schedules a full re-ground of the affected fact sheet IMMEDIATELY (no debounce) because
     * a changeset-completed event signals the end of a discrete write phase.</p>
     *
     * @param event the changeset event carrying the affected factSheetId
     */
    @EventListener
    @Async
    public void onChangesetCompleted(GraphChangesetCompletedEvent event) {
        if (groundingResetPort == null) {
            return;
        }
        Long factSheetId = event.getFactSheetId();
        if (factSheetId == null) {
            log.debug("GroundingCascadeEventListener: changeset {} has no factSheetId — skipping cascade",
                    event.getChangesetId());
            return;
        }
        log.info("GroundingCascadeEventListener: scheduling cascade for factSheet={} on changeset {} "
                        + "(+{}n +{}e)",
                factSheetId, event.getChangesetId(),
                event.getNodesCreated(), event.getEdgesCreated());
        groundingResetPort.schedule(factSheetId, "changeset:" + event.getChangesetId(),
                GroundingProgressEvent.TRIGGER_CRAWL);
    }

    /**
     * React to a single-item node mutation (created / updated / deleted via tool or API).
     *
     * <p>Routes through the debounced cascade so a rapid sequence of manual mutations
     * (e.g. graph_create_node called in a loop) does not thrash the reasoner.</p>
     *
     * @param event the node mutation event
     */
    @EventListener
    @Async
    public void onNodeMutation(NodeMutationEvent event) {
        if (groundingResetPort == null) {
            return;
        }
        Long factSheetId = event.getFactSheetId();
        if (factSheetId == null) {
            log.debug("GroundingCascadeEventListener: node mutation {} has no factSheetId — skipping debounce",
                    event.getEntityId());
            return;
        }
        log.debug("GroundingCascadeEventListener: debouncing cascade for factSheet={} on node mutation {} type={}",
                factSheetId, event.getEntityId(), event.getMutationType());
        if (groundingResetPort instanceof GroundingCascadeHook hook) {
            hook.scheduleDebounced(factSheetId,
                    event.getMutationType() + ":" + event.getEntityId(),
                    GroundingProgressEvent.TRIGGER_CASCADE);
        } else {
            // Fallback for non-hook implementations: immediate schedule
            groundingResetPort.schedule(factSheetId,
                    event.getMutationType() + ":" + event.getEntityId(),
                    GroundingProgressEvent.TRIGGER_CASCADE);
        }
    }

    /**
     * React to a single-item edge mutation (created / updated / deleted via tool or API).
     *
     * <p>Routes through the debounced cascade — same rationale as {@link #onNodeMutation}.</p>
     *
     * @param event the edge mutation event
     */
    @EventListener
    @Async
    public void onEdgeMutation(EdgeMutationEvent event) {
        if (groundingResetPort == null) {
            return;
        }
        Long factSheetId = event.getFactSheetId();
        if (factSheetId == null) {
            log.debug("GroundingCascadeEventListener: edge mutation {} has no factSheetId — skipping debounce",
                    event.getEntityId());
            return;
        }
        log.debug("GroundingCascadeEventListener: debouncing cascade for factSheet={} on edge mutation {} type={}",
                factSheetId, event.getEntityId(), event.getMutationType());
        if (groundingResetPort instanceof GroundingCascadeHook hook) {
            hook.scheduleDebounced(factSheetId,
                    event.getMutationType() + ":" + event.getEntityId(),
                    GroundingProgressEvent.TRIGGER_CASCADE);
        } else {
            groundingResetPort.schedule(factSheetId,
                    event.getMutationType() + ":" + event.getEntityId(),
                    GroundingProgressEvent.TRIGGER_CASCADE);
        }
    }

    /**
     * React to a batch graph mutation (createNodesBatch, createEdgesBatch, etc.).
     *
     * <p>Batch writes arrive in bursts during a crawl. Routes through the debounced cascade
     * so thousands of batch events collapse into a single reground after the crawl quiets down.</p>
     *
     * @param event the batch mutation event carrying factSheetId and item count
     */
    @EventListener
    @Async
    public void onBatchMutation(GraphBatchMutationEvent event) {
        if (groundingResetPort == null) {
            return;
        }
        Long factSheetId = event.getFactSheetId();
        if (factSheetId == null) {
            log.debug("GroundingCascadeEventListener: batch mutation {} has no factSheetId — skipping debounce",
                    event.getBatchType());
            return;
        }
        log.debug("GroundingCascadeEventListener: debouncing cascade for factSheet={} on batch {} count={}",
                factSheetId, event.getBatchType(), event.getItemCount());
        if (groundingResetPort instanceof GroundingCascadeHook hook) {
            hook.scheduleDebounced(factSheetId,
                    "batch:" + event.getBatchType(),
                    GroundingProgressEvent.TRIGGER_CRAWL);
        } else {
            groundingResetPort.schedule(factSheetId,
                    "batch:" + event.getBatchType(),
                    GroundingProgressEvent.TRIGGER_CRAWL);
        }
    }

    /**
     * React to an incoming channel/email message.
     *
     * <p>Channel messages without an explicit factSheetId in their metadata are
     * handled solely via the downstream {@link GraphChangesetCompletedEvent}.</p>
     *
     * @param event the channel message event
     */
    @EventListener
    @Async
    public void onChannelMessage(ChannelMessageReceivedEvent event) {
        // Channel messages do not carry a factSheetId directly in this event type.
        // The grounding cascade for channel events is driven by the downstream
        // GraphChangesetCompletedEvent emitted by the extraction pipeline.
        // This listener is retained as a hook point for future enhancement (e.g.,
        // when ChannelMessageReceivedEvent is extended to carry a targetFactSheetId).
        log.debug("GroundingCascadeEventListener: channel message from '{}' on channel '{}' — "
                        + "cascade deferred to changeset event",
                event.getMessage() != null ? event.getMessage().userId() : "unknown",
                event.getChannelName());
    }

    /**
     * React to an agent asserting a fact.
     *
     * <p>Schedules a full re-ground of the affected fact sheet IMMEDIATELY (no debounce) because
     * an agent assertion is an explicit, discrete action with low latency requirements.</p>
     *
     * @param event the agent fact assertion event carrying factSheetId and atomKey
     */
    @EventListener
    @Async
    public void onAgentFactAsserted(AgentFactAssertedEvent event) {
        if (groundingResetPort == null) {
            return;
        }
        long factSheetId = event.getFactSheetId();
        log.info("GroundingCascadeEventListener: scheduling cascade for factSheet={} on agent assert '{}' "
                        + "value={} session={}",
                factSheetId, event.getAtomKey(), event.getValue(), event.getSessionId());
        groundingResetPort.schedule(factSheetId, "agentAssert:" + event.getAtomKey(),
                GroundingProgressEvent.TRIGGER_ASSERT);
    }

    /**
     * React to an agent (or REST endpoint) performing a true TMS retraction.
     *
     * <p>Schedules a full re-ground of the affected fact sheet IMMEDIATELY (no debounce) — a
     * retraction is a discrete, complete write, just like an assertion. The cascade re-evaluates
     * dependent atoms whose support may have changed due to the retraction.</p>
     *
     * @param event the retraction event carrying factSheetId, atomKey, and dependency counts
     */
    @EventListener
    @Async
    public void onAgentFactRetracted(AgentFactRetractedEvent event) {
        if (groundingResetPort == null) {
            return;
        }
        long factSheetId = event.getFactSheetId();
        log.info("GroundingCascadeEventListener: scheduling cascade for factSheet={} on TMS retract '{}'",
                factSheetId, event.getAtomKey());
        groundingResetPort.schedule(factSheetId, "agentRetract:" + event.getAtomKey(),
                GroundingProgressEvent.TRIGGER_ASSERT);
    }
}
