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

package ai.kompile.knowledgegraph.resolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tracks entities mentioned in a conversation session for resolving
 * ambiguous references like "that company" or "the CEO".
 * <p>
 * Entities are tracked by graph node ID and associated with the turn
 * they were last mentioned. Older entities are evicted when capacity is reached.
 */
public class SessionEntityState {

    private static final int DEFAULT_MAX_ENTITIES = 50;

    private final int maxEntities;
    private final LinkedHashMap<String, TrackedEntity> entities;

    public SessionEntityState() {
        this(DEFAULT_MAX_ENTITIES);
    }

    public SessionEntityState(int maxEntities) {
        this.maxEntities = maxEntities;
        this.entities = new LinkedHashMap<>(maxEntities, 0.75f, true); // access-order
    }

    /**
     * A tracked entity in the conversation session.
     */
    public record TrackedEntity(
            String entityId,
            String name,
            String type,
            List<String> aliases,
            int lastMentionedTurn,
            String graphNodeId
    ) {
    }

    /**
     * Record an entity mention in the current turn.
     */
    public void trackEntity(String entityId, String name, String type,
                            List<String> aliases, int turn, String graphNodeId) {
        // Evict oldest if at capacity
        if (entities.size() >= maxEntities && !entities.containsKey(entityId)) {
            String oldestKey = entities.keySet().iterator().next();
            entities.remove(oldestKey);
        }

        entities.put(entityId, new TrackedEntity(entityId, name, type, aliases, turn, graphNodeId));
    }

    /**
     * Try to resolve an ambiguous reference to a tracked entity.
     * Checks against entity names, types, and aliases.
     *
     * @param reference the ambiguous text (e.g., "that company", "the CEO")
     * @return resolved entity or null
     */
    public TrackedEntity resolveReference(String reference) {
        if (reference == null || reference.isBlank()) return null;

        String normalized = reference.toLowerCase().trim();

        // Strip common prefixes
        normalized = normalized.replaceFirst("^(that|the|this|those)\\s+", "");

        // Direct name match
        for (TrackedEntity entity : getRecentEntities()) {
            if (entity.name().toLowerCase().contains(normalized) ||
                    normalized.contains(entity.name().toLowerCase())) {
                return entity;
            }
        }

        // Type match (e.g., "the company" -> most recent ORGANIZATION)
        String typeGuess = guessTypeFromReference(normalized);
        if (typeGuess != null) {
            for (TrackedEntity entity : getRecentEntities()) {
                if (entity.type().equalsIgnoreCase(typeGuess)) {
                    return entity;
                }
            }
        }

        // Alias match
        for (TrackedEntity entity : getRecentEntities()) {
            if (entity.aliases() != null) {
                for (String alias : entity.aliases()) {
                    if (alias.toLowerCase().contains(normalized) ||
                            normalized.contains(alias.toLowerCase())) {
                        return entity;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Get all tracked entities, most recently mentioned first.
     */
    public List<TrackedEntity> getRecentEntities() {
        List<TrackedEntity> result = new ArrayList<>(entities.values());
        // Reverse to get most recent first (LinkedHashMap with access-order)
        java.util.Collections.reverse(result);
        return result;
    }

    /**
     * Get tracked entities of a specific type.
     */
    public List<TrackedEntity> getEntitiesByType(String type) {
        return entities.values().stream()
                .filter(e -> e.type().equalsIgnoreCase(type))
                .collect(Collectors.toList());
    }

    /**
     * Get all entity names currently tracked (for query augmentation).
     */
    public Set<String> getTrackedEntityNames() {
        return entities.values().stream()
                .map(TrackedEntity::name)
                .collect(Collectors.toSet());
    }

    /**
     * Build a context string summarizing recently mentioned entities for query augmentation.
     */
    public String buildEntityContext(int maxEntities) {
        List<TrackedEntity> recent = getRecentEntities();
        if (recent.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("Recently discussed entities: ");
        int count = 0;
        for (TrackedEntity entity : recent) {
            if (count >= maxEntities) break;
            if (count > 0) sb.append("; ");
            sb.append(entity.name()).append(" (").append(entity.type()).append(")");
            count++;
        }
        return sb.toString();
    }

    public int size() {
        return entities.size();
    }

    public void clear() {
        entities.clear();
    }

    private static String guessTypeFromReference(String reference) {
        if (reference.contains("company") || reference.contains("organization") ||
                reference.contains("firm") || reference.contains("corporation")) {
            return "ORGANIZATION";
        }
        if (reference.contains("person") || reference.contains("ceo") ||
                reference.contains("founder") || reference.contains("manager") ||
                reference.contains("director") || reference.contains("employee")) {
            return "PERSON";
        }
        if (reference.contains("place") || reference.contains("city") ||
                reference.contains("country") || reference.contains("location")) {
            return "LOCATION";
        }
        if (reference.contains("product") || reference.contains("tool") ||
                reference.contains("software") || reference.contains("service")) {
            return "PRODUCT";
        }
        if (reference.contains("event") || reference.contains("meeting") ||
                reference.contains("conference")) {
            return "EVENT";
        }
        return null;
    }
}
