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
package ai.kompile.graph.reasoning.simulation;

import java.util.Map;
import java.util.Objects;

/**
 * One entity emitted by a {@link GraphScenario}, store-agnostic: the simulation runner maps it
 * onto whatever node representation the live store uses (e.g. an {@code ENTITY} node with an
 * {@code entity_type} metadata attribute).
 *
 * @param key              scenario-local STABLE identifier (e.g. {@code "p3"}). Deterministic per
 *                         (scenario, seed, params) so re-applying a tick is idempotent and ground
 *                         truth can reference entities before the store assigns real node ids
 * @param name             unique human-readable display name (doubles as the store entity title,
 *                         which the reasoning stack's atom keys and the inferred-fact materializer
 *                         resolve by)
 * @param entityType       semantic type label (e.g. {@code "Person"}, {@code "Organization"});
 *                         mapped to the {@code entity_type} node attribute the visualizer colors by
 * @param metadata         additional node attributes (identifiers such as emails/barcodes for
 *                         resolution scenarios); may be empty, never null after canonicalization
 * @param occurredAtEpochMs event time for temporal/causal scenarios; null for atemporal entities.
 *                          Always derived from a fixed scenario base timestamp — never wall clock —
 *                          so generation stays deterministic
 */
public record ScenarioNode(
        String key,
        String name,
        String entityType,
        Map<String, Object> metadata,
        Long occurredAtEpochMs) {

    public ScenarioNode {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience factory for an atemporal node without extra metadata. */
    public static ScenarioNode of(String key, String name, String entityType) {
        return new ScenarioNode(key, name, entityType, Map.of(), null);
    }
}
