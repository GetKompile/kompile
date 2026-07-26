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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One entity as the partition has it so far, folded together from every chunk that mentioned it.
 *
 * <p>The merge rule is deliberately conservative: <strong>the first chunk to say something owns
 * that field, and later chunks may only add.</strong> A later chunk can fill a blank, raise the
 * confidence, contribute an alias or a text unit — it cannot silently rewrite a description or
 * retype the entity. When it tries, staging keeps the earlier value and records a
 * {@link StagedConflict}.</p>
 *
 * <p>The alternative — last writer wins, or highest confidence wins — reads as tidier and is
 * worse, because extractor confidence is not calibrated across chunks. A long, specific
 * description from chunk one being replaced by a vague one from chunk nine, on the strength of a
 * self-reported 0.9, is a real regression that nobody would ever see. Keeping the first and
 * writing down the disagreement makes the same situation auditable.</p>
 *
 * @param key         staged identity; equal keys are the same entity
 * @param title       primary name, established by the first observation
 * @param type        entity type, established by the first observation that had one
 * @param description description, established by the first observation that had one
 * @param aliases     other surface forms seen for this entity, in the order they were seen
 * @param textUnits   text units contributed by every observation
 * @param metadata    open bag; earlier keys win, and no receipt is written for a metadata clash
 *                    because metadata is not a claim
 * @param confidence  highest extractor confidence any observation reported
 * @param provenance  chunks that proposed this entity, in the order they did
 * @param conflicts   disagreements staging resolved in favour of the earlier value
 */
public record StagedEntity(
        String key,
        String title,
        String type,
        String description,
        List<String> aliases,
        List<String> textUnits,
        Map<String, Object> metadata,
        double confidence,
        List<StagedProvenance> provenance,
        List<StagedConflict> conflicts) {

    /** Metadata key listing the chunks that contributed this entity. */
    public static final String META_CHUNKS = "partitionChunks";

    /** Metadata key listing the discovery channels those chunks came from. */
    public static final String META_CHANNELS = "partitionChannels";

    /** Metadata key holding the staged identity the merge was keyed on. */
    public static final String META_STAGED_KEY = "partitionStagedKey";

    public StagedEntity {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a staged entity needs a key");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        textUnits = textUnits == null ? List.of() : List.copyOf(textUnits);
        // Not Map.copyOf: extractor metadata legitimately contains null values, and staging
        // refusing to hold an entity because one of its metadata slots was empty would be absurd.
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        confidence = Double.isNaN(confidence) ? 0.0 : Math.max(0.0, Math.min(1.0, confidence));
    }

    /** Stages {@code entity} for the first time under {@code key}. */
    public static StagedEntity of(String key, Entity entity, StagedProvenance from) {
        if (entity == null) {
            throw new IllegalArgumentException("cannot stage a null entity");
        }
        if (from == null) {
            throw new IllegalArgumentException("cannot stage an entity with no provenance");
        }
        return new StagedEntity(key,
                StagedText.firstNonBlank(entity.getTitle(), entity.getId(), key),
                StagedText.trimmedOrNull(entity.getType()),
                StagedText.trimmedOrNull(entity.getDescription()),
                StagedText.cleaned(entity.getAliases()),
                StagedText.cleaned(entity.getTextUnits()),
                entity.getMetadata() == null ? Map.of() : new LinkedHashMap<>(entity.getMetadata()),
                entity.getConfidence() == null ? 0.0 : entity.getConfidence(),
                List.of(from),
                List.of());
    }

    /**
     * Folds another observation of the same entity in, returning the merged result.
     *
     * <p>Re-staging the same chunk is idempotent for provenance — a chunk that is retried does not
     * appear twice — but its field values are still merged, because a retry that produces a better
     * answer should not be thrown away.</p>
     */
    public StagedEntity absorb(Entity incoming, StagedProvenance from) {
        if (incoming == null || from == null) {
            return this;
        }
        List<StagedConflict> mergedConflicts = new ArrayList<>(conflicts);

        String incomingType = StagedText.trimmedOrNull(incoming.getType());
        String mergedType = type;
        if (type == null) {
            mergedType = incomingType;
        } else if (incomingType != null && !type.equalsIgnoreCase(incomingType)) {
            mergedConflicts.add(new StagedConflict(key, "type", type, incomingType, from.chunkId()));
        }

        String incomingDescription = StagedText.trimmedOrNull(incoming.getDescription());
        String mergedDescription = description;
        if (description == null) {
            mergedDescription = incomingDescription;
        } else if (incomingDescription != null && !description.equals(incomingDescription)) {
            mergedConflicts.add(new StagedConflict(key, "description", description,
                    incomingDescription, from.chunkId()));
        }

        // A differing title is not a disagreement — it is another surface form for something two
        // chunks already agree is the same entity, which is exactly what an alias is for.
        Set<String> mergedAliases = new LinkedHashSet<>(aliases);
        String incomingTitle = StagedText.trimmedOrNull(incoming.getTitle());
        if (incomingTitle != null && !incomingTitle.equalsIgnoreCase(title)) {
            mergedAliases.add(incomingTitle);
        }
        mergedAliases.addAll(StagedText.cleaned(incoming.getAliases()));
        mergedAliases.removeIf(alias -> alias.equalsIgnoreCase(title));

        Set<String> mergedTextUnits = new LinkedHashSet<>(textUnits);
        mergedTextUnits.addAll(StagedText.cleaned(incoming.getTextUnits()));

        Map<String, Object> mergedMetadata = new LinkedHashMap<>(metadata);
        if (incoming.getMetadata() != null) {
            incoming.getMetadata().forEach(mergedMetadata::putIfAbsent);
        }

        List<StagedProvenance> mergedProvenance = new ArrayList<>(provenance);
        if (mergedProvenance.stream().noneMatch(p -> p.chunkId().equals(from.chunkId()))) {
            mergedProvenance.add(from);
        }

        double mergedConfidence = Math.max(confidence,
                incoming.getConfidence() == null ? 0.0 : incoming.getConfidence());

        return new StagedEntity(key, title, mergedType, mergedDescription,
                List.copyOf(mergedAliases), List.copyOf(mergedTextUnits), mergedMetadata,
                mergedConfidence, mergedProvenance, mergedConflicts);
    }

    /** Chunks that contributed this entity, in the order they did. */
    public List<String> chunkIds() {
        return provenance.stream().map(StagedProvenance::chunkId).toList();
    }

    /**
     * The entity as the graph should receive it, with provenance stamped into its metadata.
     *
     * <p>The id is the staged key on purpose: the crawl's persistence path keys nodes on the
     * entity id, so normalising it here is what makes two chunks' mentions land on one node
     * instead of two.</p>
     */
    public Entity toEntity() {
        Entity entity = new Entity();
        entity.setId(key);
        entity.setTitle(title);
        entity.setType(type);
        entity.setDescription(description);
        entity.setAliases(aliases.isEmpty() ? null : List.copyOf(aliases));
        entity.setTextUnits(textUnits.isEmpty() ? null : List.copyOf(textUnits));
        entity.setConfidence(confidence);

        Map<String, Object> stamped = new LinkedHashMap<>(metadata);
        stamped.put(META_STAGED_KEY, key);
        stamped.put(META_CHUNKS, chunkIds());
        stamped.put(META_CHANNELS, provenance.stream()
                .map(p -> p.channel().name())
                .distinct()
                .toList());
        entity.setMetadata(stamped);
        return entity;
    }

    public String describe() {
        return key + " [" + (type == null ? "?" : type) + " x" + provenance.size()
                + (conflicts.isEmpty() ? "" : " !" + conflicts.size()) + "]";
    }
}
