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

import ai.kompile.core.graphrag.model.Relationship;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One relationship as the partition has it so far, folded together from every chunk that asserted
 * it.
 *
 * <p>Endpoints and type are part of the staged key, so they never disagree within a staged
 * relationship — two chunks that connect different things are simply two relationships. What can
 * disagree is the story around the edge: its description and when it happened. Those follow the
 * same rule as {@link StagedEntity} — first non-blank wins, and a later contradiction becomes a
 * {@link StagedConflict} rather than an overwrite.</p>
 *
 * <p>Weight and confidence take the maximum instead, because they are not claims about the world
 * but about how sure the extractor was; a second chunk asserting the same edge is corroboration,
 * and corroboration never lowers a weight.</p>
 *
 * @param key         staged identity, derived from the endpoints and the type
 * @param sourceKey   staged key of the source entity
 * @param targetKey   staged key of the target entity
 * @param type        relationship label
 * @param description description, established by the first observation that had one
 * @param occurredAt  when the underlying event happened, established by the first observation
 * @param weight      strongest weight any observation reported
 * @param confidence  highest extractor confidence any observation reported
 * @param metadata    open bag; earlier keys win
 * @param provenance  chunks that asserted this edge, in the order they did
 * @param conflicts   disagreements staging resolved in favour of the earlier value
 */
public record StagedRelationship(
        String key,
        String sourceKey,
        String targetKey,
        String type,
        String description,
        String occurredAt,
        double weight,
        double confidence,
        Map<String, Object> metadata,
        List<StagedProvenance> provenance,
        List<StagedConflict> conflicts) {

    /** Metadata key listing the chunks that contributed this relationship. */
    public static final String META_CHUNKS = "partitionChunks";

    public StagedRelationship {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a staged relationship needs a key");
        }
        if (sourceKey == null || sourceKey.isBlank() || targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("a staged relationship needs both endpoints");
        }
        // Not Map.copyOf: extractor metadata legitimately contains null values.
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        weight = Double.isNaN(weight) ? 0.0 : weight;
        confidence = Double.isNaN(confidence) ? 0.0 : Math.max(0.0, Math.min(1.0, confidence));
    }

    /** Stages {@code relationship} for the first time under {@code key}, with resolved endpoints. */
    public static StagedRelationship of(String key, String sourceKey, String targetKey,
                                        Relationship relationship, StagedProvenance from) {
        if (relationship == null) {
            throw new IllegalArgumentException("cannot stage a null relationship");
        }
        if (from == null) {
            throw new IllegalArgumentException("cannot stage a relationship with no provenance");
        }
        return new StagedRelationship(key, sourceKey, targetKey,
                StagedText.firstNonBlank(relationship.getType(), "RELATED_TO"),
                StagedText.trimmedOrNull(relationship.getDescription()),
                StagedText.trimmedOrNull(relationship.getOccurredAt()),
                relationship.getWeight() == null ? 0.0 : relationship.getWeight(),
                relationship.getConfidence() == null ? 0.0 : relationship.getConfidence(),
                relationship.getMetadata() == null
                        ? Map.of() : new LinkedHashMap<>(relationship.getMetadata()),
                List.of(from),
                List.of());
    }

    /** Folds another assertion of the same edge in, returning the merged result. */
    public StagedRelationship absorb(Relationship incoming, StagedProvenance from) {
        if (incoming == null || from == null) {
            return this;
        }
        List<StagedConflict> mergedConflicts = new ArrayList<>(conflicts);

        String incomingDescription = StagedText.trimmedOrNull(incoming.getDescription());
        String mergedDescription = description;
        if (description == null) {
            mergedDescription = incomingDescription;
        } else if (incomingDescription != null && !description.equals(incomingDescription)) {
            mergedConflicts.add(new StagedConflict(key, "description", description,
                    incomingDescription, from.chunkId()));
        }

        String incomingOccurredAt = StagedText.trimmedOrNull(incoming.getOccurredAt());
        String mergedOccurredAt = occurredAt;
        if (occurredAt == null) {
            mergedOccurredAt = incomingOccurredAt;
        } else if (incomingOccurredAt != null && !occurredAt.equals(incomingOccurredAt)) {
            mergedConflicts.add(new StagedConflict(key, "occurredAt", occurredAt,
                    incomingOccurredAt, from.chunkId()));
        }

        Map<String, Object> mergedMetadata = new LinkedHashMap<>(metadata);
        if (incoming.getMetadata() != null) {
            incoming.getMetadata().forEach(mergedMetadata::putIfAbsent);
        }

        List<StagedProvenance> mergedProvenance = new ArrayList<>(provenance);
        if (mergedProvenance.stream().noneMatch(p -> p.chunkId().equals(from.chunkId()))) {
            mergedProvenance.add(from);
        }

        return new StagedRelationship(key, sourceKey, targetKey, type, mergedDescription,
                mergedOccurredAt,
                Math.max(weight, incoming.getWeight() == null ? 0.0 : incoming.getWeight()),
                Math.max(confidence,
                        incoming.getConfidence() == null ? 0.0 : incoming.getConfidence()),
                mergedMetadata, mergedProvenance, mergedConflicts);
    }

    /** Chunks that contributed this relationship, in the order they did. */
    public List<String> chunkIds() {
        return provenance.stream().map(StagedProvenance::chunkId).toList();
    }

    /** The relationship as the graph should receive it, with endpoints as staged keys. */
    public Relationship toRelationship() {
        Relationship relationship = new Relationship();
        relationship.setSource(sourceKey);
        relationship.setTarget(targetKey);
        relationship.setType(type);
        relationship.setDescription(description);
        relationship.setOccurredAt(occurredAt);
        relationship.setWeight(weight);
        relationship.setConfidence(confidence);

        Map<String, Object> stamped = new LinkedHashMap<>(metadata);
        stamped.put(META_CHUNKS, chunkIds());
        relationship.setMetadata(stamped);
        return relationship;
    }

    public String describe() {
        return sourceKey + " -" + type + "-> " + targetKey + " [x" + provenance.size()
                + (conflicts.isEmpty() ? "" : " !" + conflicts.size()) + "]";
    }
}
