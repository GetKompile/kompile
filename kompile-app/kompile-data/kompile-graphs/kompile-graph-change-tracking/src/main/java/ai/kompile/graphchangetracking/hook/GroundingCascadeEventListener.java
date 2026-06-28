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
import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import ai.kompile.knowledgegraph.grounding.AgentFactAssertedEvent;
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
     * <p>Schedules a full re-ground of the affected fact sheet.</p>
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
     * <p>Schedules a full re-ground of the affected fact sheet.</p>
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
}
