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

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cross-chunk entity deduplication and fuzzy merge service.
 * <p>
 * Resolution pipeline:
 * 1. Normalize entity names (lowercase, strip suffixes, trim)
 * 2. String similarity via Levenshtein distance (configurable threshold, default 0.85)
 * 3. Alias matching from extraction
 * 4. Merge matching entities: combine aliases, keep highest confidence, link source chunks
 */
@Service
public class EntityResolutionService {

    private static final Logger log = LoggerFactory.getLogger(EntityResolutionService.class);

    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.85;
    private static final Pattern SUFFIX_PATTERN = Pattern.compile(
            "\\b(Inc\\.?|Corp\\.?|Corporation|Ltd\\.?|Limited|LLC|Co\\.?|Company|Group|Plc\\.?)$",
            Pattern.CASE_INSENSITIVE
    );

    private double similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;

    public void setSimilarityThreshold(double threshold) {
        this.similarityThreshold = threshold;
    }

    /**
     * Resolve entities across multiple extraction results, merging duplicates.
     *
     * @param results list of per-chunk extraction results
     * @return a single merged ExtractionResult with deduplicated entities
     */
    public ExtractionResult resolve(List<ExtractionResult> results) {
        if (results == null || results.isEmpty()) {
            return ExtractionResult.of(List.of(), List.of(), null);
        }

        // Collect all entities and relations
        List<ExtractedEntity> allEntities = new ArrayList<>();
        List<ExtractedRelation> allRelations = new ArrayList<>();
        for (ExtractionResult result : results) {
            allEntities.addAll(result.entities());
            allRelations.addAll(result.relations());
        }

        // Resolve entities
        EntityMergeResult mergeResult = mergeEntities(allEntities);

        // Remap relation references from old IDs to merged IDs
        List<ExtractedRelation> remappedRelations = remapRelations(allRelations, mergeResult.idMapping());

        log.info("Entity resolution: {} entities merged to {}, {} relations remapped",
                allEntities.size(), mergeResult.mergedEntities().size(), remappedRelations.size());

        return ExtractionResult.of(mergeResult.mergedEntities(), remappedRelations, null);
    }

    /**
     * Resolve entities within a single ExtractionResult.
     */
    public ExtractionResult resolveSingle(ExtractionResult result) {
        return resolve(List.of(result));
    }

    /**
     * Merge a list of entities, deduplicating by name similarity and alias matching.
     */
    EntityMergeResult mergeEntities(List<ExtractedEntity> entities) {
        // Map of canonical ID -> merged entity state
        LinkedHashMap<String, MergeState> canonicalEntities = new LinkedHashMap<>();
        // Map of original ID -> canonical ID
        Map<String, String> idMapping = new HashMap<>();

        for (ExtractedEntity entity : entities) {
            String normalizedName = normalize(entity.name());
            String matchedCanonicalId = findMatch(entity, canonicalEntities);

            if (matchedCanonicalId != null) {
                // Merge into existing
                MergeState existing = canonicalEntities.get(matchedCanonicalId);
                existing.merge(entity);
                idMapping.put(entity.id(), matchedCanonicalId);
            } else {
                // New canonical entity
                MergeState state = new MergeState(entity);
                canonicalEntities.put(entity.id(), state);
                idMapping.put(entity.id(), entity.id());
            }
        }

        List<ExtractedEntity> merged = new ArrayList<>();
        for (MergeState state : canonicalEntities.values()) {
            merged.add(state.toEntity());
        }

        return new EntityMergeResult(merged, idMapping);
    }

    private String findMatch(ExtractedEntity entity, LinkedHashMap<String, MergeState> canonicalEntities) {
        String normalizedName = normalize(entity.name());
        Set<String> entityAliasesNorm = normalizeAliases(entity);

        for (Map.Entry<String, MergeState> entry : canonicalEntities.entrySet()) {
            MergeState candidate = entry.getValue();

            // Must be same type to merge
            if (!entity.type().equalsIgnoreCase(candidate.type)) {
                continue;
            }

            // Check exact normalized name match
            if (normalizedName.equals(candidate.normalizedName)) {
                return entry.getKey();
            }

            // Check alias match: does the new entity's name/aliases match any candidate name/aliases?
            if (candidate.normalizedAliases.contains(normalizedName)) {
                return entry.getKey();
            }
            for (String alias : entityAliasesNorm) {
                if (alias.equals(candidate.normalizedName) || candidate.normalizedAliases.contains(alias)) {
                    return entry.getKey();
                }
            }

            // Check string similarity (Levenshtein)
            double similarity = levenshteinSimilarity(normalizedName, candidate.normalizedName);
            if (similarity >= similarityThreshold) {
                return entry.getKey();
            }
        }

        return null;
    }

    private Set<String> normalizeAliases(ExtractedEntity entity) {
        Set<String> result = new HashSet<>();
        if (entity.aliases() != null) {
            for (String alias : entity.aliases()) {
                result.add(normalize(alias));
            }
        }
        return result;
    }

    List<ExtractedRelation> remapRelations(List<ExtractedRelation> relations, Map<String, String> idMapping) {
        List<ExtractedRelation> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ExtractedRelation rel : relations) {
            String newSource = idMapping.getOrDefault(rel.source(), rel.source());
            String newTarget = idMapping.getOrDefault(rel.target(), rel.target());

            // Deduplicate same source-target-type relations after remapping
            String key = newSource + "|" + newTarget + "|" + rel.type();
            if (seen.add(key)) {
                result.add(new ExtractedRelation(
                        newSource, newTarget, rel.type(),
                        rel.description(), rel.confidence(), rel.properties()
                ));
            }
        }
        return result;
    }

    /**
     * Normalize an entity name for comparison.
     */
    public static String normalize(String name) {
        if (name == null) return "";
        String result = name.trim().toLowerCase();
        result = SUFFIX_PATTERN.matcher(result).replaceAll("").trim();
        // Collapse whitespace
        result = result.replaceAll("\\s+", " ");
        return result;
    }

    /**
     * Compute Levenshtein similarity between two strings (0.0 to 1.0).
     */
    public static double levenshteinSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        int maxLen = Math.max(a.length(), b.length());
        int distance = levenshteinDistance(a, b);
        return 1.0 - (double) distance / maxLen;
    }

    public static int levenshteinDistance(String a, String b) {
        int lenA = a.length();
        int lenB = b.length();
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];

        for (int j = 0; j <= lenB; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            for (int j = 1; j <= lenB; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lenB];
    }

    /**
     * Internal mutable state for tracking an entity being merged.
     */
    private static class MergeState {
        String id;
        String name;
        String type;
        String normalizedName;
        Set<String> normalizedAliases;
        Set<String> allAliases;
        String description;
        double confidence;
        Map<String, String> properties;
        Set<String> sourceChunkIds;

        MergeState(ExtractedEntity entity) {
            this.id = entity.id();
            this.name = entity.name();
            this.type = entity.type();
            this.normalizedName = normalize(entity.name());
            this.normalizedAliases = new HashSet<>();
            this.allAliases = new HashSet<>();
            this.description = entity.description();
            this.confidence = entity.confidence() != null ? entity.confidence() : 1.0;
            this.properties = new HashMap<>(entity.properties() != null ? entity.properties() : Map.of());
            this.sourceChunkIds = new HashSet<>();

            if (entity.aliases() != null) {
                for (String alias : entity.aliases()) {
                    allAliases.add(alias);
                    normalizedAliases.add(normalize(alias));
                }
            }

            String chunkId = entity.properties() != null ? entity.properties().get("sourceChunkId") : null;
            if (chunkId != null) {
                sourceChunkIds.add(chunkId);
            }
        }

        void merge(ExtractedEntity other) {
            // Add name as alias if different
            if (!normalize(other.name()).equals(normalizedName)) {
                allAliases.add(other.name());
                normalizedAliases.add(normalize(other.name()));
            }

            // Merge aliases
            if (other.aliases() != null) {
                for (String alias : other.aliases()) {
                    allAliases.add(alias);
                    normalizedAliases.add(normalize(alias));
                }
            }

            // Keep longer/better description
            if (other.description() != null && (description == null ||
                    other.description().length() > description.length())) {
                description = other.description();
            }

            // Keep highest confidence
            double otherConf = other.confidence() != null ? other.confidence() : 1.0;
            if (otherConf > confidence) {
                confidence = otherConf;
            }

            // Merge properties
            if (other.properties() != null) {
                for (Map.Entry<String, String> entry : other.properties().entrySet()) {
                    properties.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }

            // Track source chunks
            String chunkId = other.properties() != null ? other.properties().get("sourceChunkId") : null;
            if (chunkId != null) {
                sourceChunkIds.add(chunkId);
            }
        }

        ExtractedEntity toEntity() {
            Map<String, String> finalProps = new HashMap<>(properties);
            if (!sourceChunkIds.isEmpty()) {
                finalProps.put("sourceChunkIds", String.join(",", sourceChunkIds));
            }

            return new ExtractedEntity(
                    id, name, type,
                    allAliases.isEmpty() ? List.of() : new ArrayList<>(allAliases),
                    description, confidence, finalProps
            );
        }
    }
}
