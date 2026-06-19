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
package ai.kompile.event.observation.domain;

/**
 * A connection in the knowledge graph — an edge of {@code edgeType} from {@code sourceNodeId} to
 * {@code targetNodeId}. Per the design, <em>connections produce observed events</em>: each time a
 * channel is observed during a crawl it emits a {@link EventType#CONNECTION_OCCURRENCE} event.
 */
public record EventChannel(String sourceNodeId, String edgeType, String targetNodeId) {

    /** The stable event key for this connection. */
    public String eventKey() {
        return EventKeys.connection(sourceNodeId, edgeType, targetNodeId);
    }
}
