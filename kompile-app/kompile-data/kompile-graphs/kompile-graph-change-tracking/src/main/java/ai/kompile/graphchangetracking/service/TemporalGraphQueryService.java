package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Service
@Slf4j
public class TemporalGraphQueryService {

    private final GraphMutationStore mutationStore;
    private final ObjectMapper objectMapper;

    public TemporalGraphQueryService(GraphMutationStore mutationStore,
                                      ObjectMapper objectMapper) {
        this.mutationStore = mutationStore;
        this.objectMapper = objectMapper;
    }

    public List<GraphMutationRecord> getNodeHistory(String nodeId) {
        return mutationStore.findByEntityKindAndEntityIdOrderByOccurredAtDesc("NODE", nodeId);
    }

    public List<GraphMutationRecord> getEdgeHistory(String edgeId) {
        return mutationStore.findByEntityKindAndEntityIdOrderByOccurredAtDesc("EDGE", edgeId);
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> reconstructNodeAt(String nodeId, LocalDateTime asOf) {
        List<GraphMutationRecord> records = mutationStore.findMostRecentBefore(
                "NODE", nodeId, asOf, PageRequest.of(0, 1));
        if (records.isEmpty()) {
            return Optional.empty();
        }
        GraphMutationRecord latest = records.get(0);
        String snapshot = latest.getSnapshotAfter();
        if (snapshot == null && "NODE_DELETED".equals(latest.getMutationType())) {
            snapshot = latest.getSnapshotBefore();
        }
        if (snapshot == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(snapshot, Map.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize snapshot for node {} at {}", nodeId, asOf, e);
            return Optional.empty();
        }
    }

    public GraphDiff diffFactSheet(Long factSheetId, LocalDateTime from, LocalDateTime to) {
        Page<GraphMutationRecord> mutations = mutationStore
                .findByFactSheetIdAndOccurredAtBetweenOrderByOccurredAtDesc(
                        factSheetId, from, to, Pageable.unpaged());
        List<GraphMutationRecord> records = mutations.getContent();

        int nodesCreated = 0, nodesUpdated = 0, nodesDeleted = 0;
        int edgesCreated = 0, edgesUpdated = 0, edgesDeleted = 0;
        for (GraphMutationRecord r : records) {
            switch (r.getMutationType()) {
                case "NODE_CREATED" -> nodesCreated++;
                case "NODE_UPDATED" -> nodesUpdated++;
                case "NODE_DELETED" -> nodesDeleted++;
                case "EDGE_CREATED" -> edgesCreated++;
                case "EDGE_UPDATED" -> edgesUpdated++;
                case "EDGE_DELETED" -> edgesDeleted++;
            }
        }

        return new GraphDiff(factSheetId, from, to,
                nodesCreated, nodesUpdated, nodesDeleted,
                edgesCreated, edgesUpdated, edgesDeleted,
                records);
    }

    public record GraphDiff(
            Long factSheetId,
            LocalDateTime from,
            LocalDateTime to,
            int nodesCreated,
            int nodesUpdated,
            int nodesDeleted,
            int edgesCreated,
            int edgesUpdated,
            int edgesDeleted,
            List<GraphMutationRecord> mutations
    ) {}

    // ── semantic entity-level diff (Phase 5) ────────────────────────────────────────

    /** Volatile/infra snapshot keys excluded from attribute comparison (always-changing or non-semantic). */
    private static final Set<String> NON_SEMANTIC_KEYS = Set.of(
            "id", "createdAt", "updatedAt", "lastVerifiedAt", "observedAt", "staleAt", "computedAt",
            "validUntil", "childCount", "edgeCount", "vectorId", "metadata", "children", "parent",
            "sourceNode", "targetNode", "kgEmbedding", "kgRelationEmbedding", "kgEmbeddingUpdatedAt",
            "kgEmbeddingVersion", "kgEmbeddingAlgorithm");

    /**
     * Semantic, entity-level diff of a fact sheet's graph between two times: which entities were
     * <em>added</em>, <em>removed</em>, or had <em>attributes changed</em> — built from the full
     * before/after snapshots in the mutation log (Phase 5; complements the count-only
     * {@link #diffFactSheet}). State at {@code from} is the earliest in-window mutation's
     * {@code snapshotBefore}; state at {@code to} is the latest's {@code snapshotAfter}. Entities
     * created-and-deleted within the window net to no change.
     */
    public EntityDiff semanticDiffFactSheet(Long factSheetId, LocalDateTime from, LocalDateTime to) {
        List<GraphMutationRecord> records = mutationStore
                .findByFactSheetIdAndOccurredAtBetweenOrderByOccurredAtDesc(
                        factSheetId, from, to, Pageable.unpaged())
                .getContent();

        // Group by entity, preserving first-seen order. Records arrive newest-first.
        Map<String, List<GraphMutationRecord>> byEntity = new LinkedHashMap<>();
        for (GraphMutationRecord r : records) {
            byEntity.computeIfAbsent(r.getEntityKind() + "|" + r.getEntityId(), k -> new ArrayList<>()).add(r);
        }

        List<EntityRef> added = new ArrayList<>();
        List<EntityRef> removed = new ArrayList<>();
        List<EntityChange> changed = new ArrayList<>();

        for (List<GraphMutationRecord> group : byEntity.values()) {
            GraphMutationRecord latest = group.get(0);                  // newest → state at `to`
            GraphMutationRecord earliest = group.get(group.size() - 1); // oldest → state before `from`
            Map<String, Object> before = parseSnapshot(earliest.getSnapshotBefore());
            Map<String, Object> after = parseSnapshot(latest.getSnapshotAfter());
            String kind = latest.getEntityKind();
            String id = latest.getEntityId();

            if (before == null && after != null) {
                added.add(toRef(kind, id, after));
            } else if (before != null && after == null) {
                removed.add(toRef(kind, id, before));
            } else if (before != null) { // present at both → compare attributes
                List<AttributeChange> deltas = diffAttributes(before, after);
                if (!deltas.isEmpty()) {
                    changed.add(new EntityChange(kind, id, entityType(kind, after), label(kind, after), deltas));
                }
            }
            // before == null && after == null → created+deleted within the window → net no-op
        }

        return new EntityDiff(factSheetId, from, to, added, removed, changed,
                added.size(), removed.size(), changed.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse mutation snapshot: {}", e.getMessage());
            return null;
        }
    }

    private List<AttributeChange> diffAttributes(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<AttributeChange> deltas = new ArrayList<>();
        for (String key : keys) {
            if (NON_SEMANTIC_KEYS.contains(key)) {
                continue;
            }
            if (!Objects.equals(before.get(key), after.get(key))) {
                deltas.add(new AttributeChange(key, asString(before.get(key)), asString(after.get(key))));
            }
        }
        return deltas;
    }

    private static EntityRef toRef(String kind, String id, Map<String, Object> state) {
        return new EntityRef(kind, id, entityType(kind, state), label(kind, state));
    }

    private static String entityType(String kind, Map<String, Object> state) {
        if (state == null) {
            return null;
        }
        return asString("EDGE".equals(kind)
                ? firstNonNull(state.get("relationType"), state.get("edgeType"))
                : state.get("nodeType"));
    }

    private static String label(String kind, Map<String, Object> state) {
        if (state == null) {
            return null;
        }
        return asString("EDGE".equals(kind)
                ? firstNonNull(state.get("label"), state.get("description"))
                : state.get("title"));
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Entity-level semantic diff: which entities were added / removed / attribute-changed in a window. */
    public record EntityDiff(
            Long factSheetId,
            LocalDateTime from,
            LocalDateTime to,
            List<EntityRef> added,
            List<EntityRef> removed,
            List<EntityChange> changed,
            int addedCount,
            int removedCount,
            int changedCount
    ) {}

    /** A reference to an added/removed entity. */
    public record EntityRef(String entityKind, String entityId, String entityType, String label) {}

    /** An entity present at both times whose attributes differ. */
    public record EntityChange(String entityKind, String entityId, String entityType, String label,
                               List<AttributeChange> changes) {}

    /** A single attribute that differs between the two times. */
    public record AttributeChange(String attribute, String before, String after) {}
}
