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

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.knowledgegraph.domain.GraphNode;

import java.util.Map;

/**
 * Maps a {@link GraphNode} to the activity label it represents in an event log. This is the one place
 * where "what counts as a step" is decided, and it is intentionally pluggable per domain.
 *
 * <p>The default ({@link #byEntityType()}) uses the node's {@code entity_type} (read through the
 * store-agnostic {@link GraphNode#getMetadata()} seam, so it works identically on the JPA and the
 * {@code @Primary} matrix backends), falling back to the structural {@code NodeLevel} when no
 * {@code entity_type} is present.
 */
@FunctionalInterface
public interface ActivityClassifier {

    String activityOf(GraphNode node);

    /** Default: the node's {@code entity_type}, else its {@code NodeLevel}, else {@code "UNKNOWN"}. */
    static ActivityClassifier byEntityType() {
        return node -> {
            if (node == null) {
                return "UNKNOWN";
            }
            Map<String, Object> meta = node.getMetadata();
            if (meta != null) {
                Object entityType = meta.get("entity_type");
                if (entityType instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
            return node.getNodeType() != null ? node.getNodeType().name() : "UNKNOWN";
        };
    }
}
