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
package ai.kompile.event.observation.listener;

import ai.kompile.event.observation.config.EventObservationConfig;
import ai.kompile.event.observation.config.EventObservationConfigService;
import ai.kompile.event.observation.domain.EventChannel;
import ai.kompile.event.observation.domain.EventSource;
import ai.kompile.event.observation.service.EventObservationService;
import ai.kompile.graphchangetracking.event.EdgeMutationEvent;
import ai.kompile.graphchangetracking.event.NodeMutationEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Fine-grained, online prior updates: applies a per-mutation Beta update for each node/edge
 * mutation so priors track individual crawl-update deltas, not just whole builds. Additive to the
 * coarse {@link CrawlEventObservationListener}; gated off by default ({@code fineGrainedMutationsEnabled})
 * so the two paths don't double-count during a full crawl.
 */
@Component
@ConditionalOnClass(name = "ai.kompile.graphchangetracking.event.NodeMutationEvent")
public class GraphMutationObservationListener {

    private static final Set<String> ENTITY_NODE_TYPES = Set.of("ENTITY", "TABLE");
    private static final Set<String> SKIP_EDGE_TYPES = Set.of("EMBEDDING_SIMILARITY", "HIERARCHICAL");

    private final EventObservationService observationService;
    private final EventObservationConfigService configService;

    public GraphMutationObservationListener(EventObservationService observationService,
                                            EventObservationConfigService configService) {
        this.observationService = observationService;
        this.configService = configService;
    }

    @Async
    @EventListener
    public void onNodeMutation(NodeMutationEvent event) {
        if (!active()) {
            return;
        }
        String type = event.getMutationType();
        if (!"NODE_CREATED".equals(type) && !"NODE_UPDATED".equals(type)) {
            return;
        }
        if (event.getNodeType() == null || !ENTITY_NODE_TYPES.contains(event.getNodeType()) || event.getEntityId() == null) {
            return;
        }
        observationService.observeEntity(event.getEntityId(), 1L, 1L,
                EventSource.MUTATION, event.getFactSheetId(), event.getChangesetId());
    }

    @Async
    @EventListener
    public void onEdgeMutation(EdgeMutationEvent event) {
        if (!active()) {
            return;
        }
        String type = event.getMutationType();
        if (!"EDGE_CREATED".equals(type) && !"EDGE_UPDATED".equals(type)) {
            return;
        }
        String edgeType = event.getEdgeType();
        if (edgeType == null || SKIP_EDGE_TYPES.contains(edgeType)
                || event.getSourceNodeId() == null || event.getTargetNodeId() == null) {
            return;
        }
        observationService.observeConnection(
                new EventChannel(event.getSourceNodeId(), edgeType, event.getTargetNodeId()),
                1L, 1L, EventSource.MUTATION, event.getFactSheetId(), event.getChangesetId());
    }

    private boolean active() {
        EventObservationConfig cfg = configService.getConfig();
        return cfg.enabled() && cfg.fineGrainedMutationsEnabled();
    }
}
