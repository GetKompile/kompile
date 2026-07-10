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

package ai.kompile.process.discovery.mining.log;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * A single event in an {@link EventLog}: one occurrence of an {@code activity} for a given case,
 * at a point in time, traceable back to the knowledge-graph node it was projected from.
 *
 * <p>Activities are plain strings — the standard representation in process mining (ProM, PM4Py).
 * The label is whatever the {@code ActivityClassifier} chose (typically the node {@code entity_type}
 * read from {@code GraphNode.getMetadata()}, falling back to {@code NodeLevel} or an edge
 * {@code relationType}). Keeping events as pure data with no JPA/KG types is deliberate: it lets the
 * miners run and be unit-tested in complete isolation from the graph stores.
 *
 * @param caseId      identifier of the process instance this event belongs to (the "case notion")
 * @param activity    the activity label; must be non-blank
 * @param timestamp   when the event occurred in the real world; may be {@code null} when unknown,
 *                    in which case ordering falls back to stable insertion order
 * @param graphNodeId the knowledge-graph node this event was projected from (provenance); may be null
 * @param attributes  optional extra attributes (resource, cost, …); never null (defaults to empty)
 */
public record Event(
        String caseId,
        String activity,
        LocalDateTime timestamp,
        String graphNodeId,
        Map<String, Object> attributes) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Event {
        if (activity == null || activity.isBlank()) {
            throw new IllegalArgumentException("Event activity must be non-blank");
        }
        attributes = (attributes == null) ? Map.of() : Map.copyOf(attributes);
    }

    /** Convenience factory for an event with no extra attributes. */
    public static Event of(String caseId, String activity, LocalDateTime timestamp, String graphNodeId) {
        return new Event(caseId, activity, timestamp, graphNodeId, Map.of());
    }
}
