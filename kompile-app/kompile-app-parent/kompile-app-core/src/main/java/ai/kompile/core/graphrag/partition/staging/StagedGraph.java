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

package ai.kompile.core.graphrag.partition.staging;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.partition.PartitionMember;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a partition has learned so far, merged, and not yet given to the graph.
 *
 * <p>Extraction runs per chunk, so the same company appears in nine chunks as nine entities and
 * the same edge is asserted four times. Writing that straight through means the graph does the
 * deduplication — at which point the merge happens inside a store, one row at a time, with no
 * chance to notice that two chunks disagreed. Staging moves the merge in front of the write: the
 * partition folds its own output together, keeps the receipts for what it dropped, and hands the
 * graph a single consistent picture.</p>
 *
 * <p>The value is immutable, so every {@code stage} returns a new graph and a checkpoint is just a
 * kept reference. That costs a map copy per chunk, which is the right trade at partition scale —
 * a partition is tens to hundreds of chunks, and being able to roll a bad batch back without
 * losing the good ones is worth far more than the copies.</p>
 */
public final class StagedGraph {

    private final StagedKeys keys;
    private final Map<String, StagedEntity> entities;
    private final Map<String, StagedRelationship> relationships;

    private StagedGraph(StagedKeys keys, Map<String, StagedEntity> entities,
                        Map<String, StagedRelationship> relationships) {
        this.keys = keys;
        this.entities = entities;
        this.relationships = relationships;
    }

    /** An empty staged graph keyed on entity names. */
    public static StagedGraph empty() {
        return using(StagedKeys.byName());
    }

    /** An empty staged graph with a caller-chosen notion of identity. */
    public static StagedGraph using(StagedKeys keys) {
        return new StagedGraph(keys == null ? StagedKeys.byName() : keys,
                Map.of(), Map.of());
    }

    /** How this graph decides two mentions are the same thing. */
    public StagedKeys keys() {
        return keys;
    }

    /**
     * Folds one chunk's extraction output in, returning the merged graph.
     *
     * <p>Entities are merged first so relationship endpoints can be resolved against them. An
     * endpoint the extractor gave as an entity id resolves through this chunk's own entities; one
     * given as a name resolves through {@link StagedKeys#referenceKey}. Either way the edge ends
     * up on a stable key, so a second chunk asserting the same edge merges with it rather than
     * duplicating it.</p>
     *
     * @param produced what the extractor returned for this chunk; null or empty is a no-op
     * @param from     which chunk it came from
     */
    public StagedGraph stage(Graph produced, StagedProvenance from) {
        if (produced == null || from == null) {
            return this;
        }
        List<Entity> incomingEntities = produced.getEntities() == null
                ? List.of() : produced.getEntities();
        List<Relationship> incomingRelationships = produced.getRelationships() == null
                ? List.of() : produced.getRelationships();
        if (incomingEntities.isEmpty() && incomingRelationships.isEmpty()) {
            return this;
        }

        Map<String, StagedEntity> mergedEntities = new LinkedHashMap<>(entities);
        // Endpoint references the extractor may have used for the entities in this chunk. Built
        // per chunk on purpose: entity ids like "e1" are only meaningful inside the extraction
        // that produced them, and treating them as global would join unrelated entities.
        Map<String, String> localReferences = new LinkedHashMap<>();

        for (Entity entity : incomingEntities) {
            if (entity == null) {
                continue;
            }
            String key = keys.entityKey(entity);
            if (key == null || key.isBlank()) {
                continue;
            }
            StagedEntity existing = mergedEntities.get(key);
            mergedEntities.put(key, existing == null
                    ? StagedEntity.of(key, entity, from)
                    : existing.absorb(entity, from));
            if (entity.getId() != null && !entity.getId().isBlank()) {
                localReferences.put(keys.referenceKey(entity.getId()), key);
            }
            if (entity.getTitle() != null && !entity.getTitle().isBlank()) {
                localReferences.put(keys.referenceKey(entity.getTitle()), key);
            }
        }

        Map<String, StagedRelationship> mergedRelationships = new LinkedHashMap<>(relationships);
        for (Relationship relationship : incomingRelationships) {
            if (relationship == null) {
                continue;
            }
            String sourceKey = resolveEndpoint(relationship.getSource(), localReferences);
            String targetKey = resolveEndpoint(relationship.getTarget(), localReferences);
            if (sourceKey == null || targetKey == null) {
                // An edge with a missing end is not an edge. Dropping it here is not data loss:
                // there is nothing a graph could do with it either.
                continue;
            }
            String type = StagedText.firstNonBlank(relationship.getType(), "RELATED_TO");
            String key = keys.relationshipKey(sourceKey, type, targetKey);
            StagedRelationship existing = mergedRelationships.get(key);
            mergedRelationships.put(key, existing == null
                    ? StagedRelationship.of(key, sourceKey, targetKey, relationship, from)
                    : existing.absorb(relationship, from));
        }

        // Insertion-ordered on purpose: "the order they were first seen" is a promise this class
        // makes, and Map.copyOf would quietly break it.
        return new StagedGraph(keys, Collections.unmodifiableMap(mergedEntities),
                Collections.unmodifiableMap(mergedRelationships));
    }

    /** Folds in what {@code member}'s chunk produced, taking provenance from the member. */
    public StagedGraph stage(PartitionMember member, Graph produced) {
        return member == null ? this : stage(produced, StagedProvenance.from(member));
    }

    private String resolveEndpoint(String rawReference, Map<String, String> localReferences) {
        String reference = keys.referenceKey(rawReference);
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String resolved = localReferences.get(reference);
        return resolved != null ? resolved : reference;
    }

    /** Staged entities in the order they were first seen. */
    public Collection<StagedEntity> entities() {
        return entities.values();
    }

    /** Staged relationships in the order they were first seen. */
    public Collection<StagedRelationship> relationships() {
        return relationships.values();
    }

    public Optional<StagedEntity> entity(String key) {
        return Optional.ofNullable(entities.get(key));
    }

    public Optional<StagedRelationship> relationship(String key) {
        return Optional.ofNullable(relationships.get(key));
    }

    public boolean isEmpty() {
        return entities.isEmpty() && relationships.isEmpty();
    }

    /** Every disagreement staging resolved, entities first. */
    public List<StagedConflict> conflicts() {
        List<StagedConflict> all = new ArrayList<>();
        entities.values().forEach(entity -> all.addAll(entity.conflicts()));
        relationships.values().forEach(relationship -> all.addAll(relationship.conflicts()));
        return List.copyOf(all);
    }

    /**
     * Edges whose endpoints were never staged as entities here.
     *
     * <p>These are not necessarily wrong — the endpoint may already exist in the graph from an
     * earlier partition, and the write path will resolve it. They are reported because the other
     * possibility is that an extractor invented an endpoint, and a commit that quietly drops such
     * an edge looks identical to one that wrote it.</p>
     */
    public List<StagedRelationship> danglingRelationships() {
        return relationships.values().stream()
                .filter(r -> !entities.containsKey(r.sourceKey())
                        || !entities.containsKey(r.targetKey()))
                .toList();
    }

    /**
     * Chunks that contributed anything staged here, in the order they contributed.
     *
     * <p>Order is part of the answer: this list ends up in the committed graph's metadata, and a
     * reader tracing how a merged node came to say what it says needs the chunks in the sequence
     * that built it, not in whatever order a hash bucket produced.</p>
     */
    public Set<String> chunkIds() {
        Set<String> chunks = new LinkedHashSet<>();
        entities.values().forEach(entity -> chunks.addAll(entity.chunkIds()));
        relationships.values().forEach(relationship -> chunks.addAll(relationship.chunkIds()));
        return Collections.unmodifiableSet(chunks);
    }

    /**
     * The merged picture as an extraction graph, ready for the ordinary persistence path.
     *
     * <p>Entity ids are the staged keys, and relationship endpoints refer to those ids, which is
     * what makes the crawl's writer land every chunk's mention of a subject on one node.</p>
     */
    public Graph toGraph(String name, Long factSheetId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stagedChunks", List.copyOf(chunkIds()));
        metadata.put("stagedConflicts", conflicts().size());
        return Graph.builder()
                .id(name)
                .name(name)
                .factSheetId(factSheetId)
                .entities(entities.values().stream().map(StagedEntity::toEntity).toList())
                .relationships(relationships.values().stream()
                        .map(StagedRelationship::toRelationship).toList())
                .metadata(metadata)
                .build();
    }

    public String describe() {
        int conflicts = conflicts().size();
        int dangling = danglingRelationships().size();
        StringBuilder sb = new StringBuilder()
                .append(entities.size()).append(" entities, ")
                .append(relationships.size()).append(" relationships from ")
                .append(chunkIds().size()).append(" chunk(s)");
        if (conflicts > 0) {
            sb.append(", ").append(conflicts).append(" conflict(s)");
        }
        if (dangling > 0) {
            sb.append(", ").append(dangling).append(" edge(s) with unstaged endpoints");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StagedGraph that)) {
            return false;
        }
        return entities.equals(that.entities) && relationships.equals(that.relationships);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entities, relationships);
    }

    @Override
    public String toString() {
        return "StagedGraph[" + describe() + "]";
    }
}
