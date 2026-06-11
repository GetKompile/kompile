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

package ai.kompile.core.graphrag;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Shared utility methods for {@link DocumentGraphExtractor} implementations.
 * Consolidates duplicated logic for metadata access, author/keyword splitting,
 * entity deduplication, and batch extraction.
 */
public final class ExtractorUtils {

    private ExtractorUtils() {}

    /** Reusable provenance map for extracted relations — avoids allocating a new Map.of() each time. */
    public static final Map<String, String> PROVENANCE_MAP =
            Map.of(GraphConstants.PROP_PROVENANCE, GraphConstants.PROVENANCE_EXTRACTED);

    private static final Pattern AUTHOR_SPLIT = Pattern.compile("[,;]|\\s+and\\s+|\\s*&\\s*");
    private static final Pattern KEYWORD_SPLIT = Pattern.compile("[,;|]");

    /**
     * Safely extracts a trimmed, non-empty string from a metadata value.
     * Returns null if the value is null, empty, or blank after trimming.
     */
    public static String str(Object obj) {
        if (obj == null) return null;
        String s = obj.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Generates a deterministic UUID-based entity ID from a string key.
     * Identical keys always produce the same ID, enabling cross-document deduplication.
     */
    public static String entityId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Splits an author field by common delimiters ({@code ,}, {@code ;}, {@code and}, {@code &}).
     * Returns a list of trimmed, non-empty author names.
     */
    public static List<String> splitAuthors(String authorField) {
        List<String> authors = new ArrayList<>();
        String[] parts = AUTHOR_SPLIT.split(authorField);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                authors.add(trimmed);
            }
        }
        return authors;
    }

    /**
     * Splits a keywords field by common delimiters ({@code ,}, {@code ;}, {@code |}).
     * Returns a list of trimmed keywords, excluding single-character entries.
     */
    public static List<String> splitKeywords(String keywordField) {
        List<String> keywords = new ArrayList<>();
        String[] parts = KEYWORD_SPLIT.split(keywordField);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && trimmed.length() > 1) {
                keywords.add(trimmed);
            }
        }
        return keywords;
    }

    /**
     * Adds an entity to the index, merging properties and taking max confidence
     * if an entity with the same ID already exists.
     */
    public static void addEntity(Map<String, ExtractedEntity> index, ExtractedEntity entity) {
        ExtractedEntity existing = index.get(entity.id());
        if (existing == null) {
            index.put(entity.id(), entity);
        } else {
            Map<String, String> mergedProps = new LinkedHashMap<>();
            if (existing.properties() != null) mergedProps.putAll(existing.properties());
            if (entity.properties() != null) mergedProps.putAll(entity.properties());

            double conf = Math.max(
                    existing.confidence() != null ? existing.confidence() : 0.0,
                    entity.confidence() != null ? entity.confidence() : 0.0
            );

            index.put(entity.id(), new ExtractedEntity(
                    entity.id(), existing.name(), existing.type(),
                    existing.aliases(), existing.description(), conf, mergedProps
            ));
        }
    }

    /**
     * Default batch extraction: extracts each document individually, merges entities
     * by ID, and combines all relations. Suitable for most {@link DocumentGraphExtractor}
     * implementations.
     *
     * @param extractor the extractor to use for individual documents
     * @param docs      the documents to process
     * @param extractorName the extractor identifier for metadata
     * @return merged extraction result
     */
    public static ExtractionResult extractBatch(DocumentGraphExtractor extractor,
                                                 List<Document> docs,
                                                 String extractorName) {
        Map<String, ExtractedEntity> mergedEntities = new LinkedHashMap<>();
        List<ExtractedRelation> mergedRelations = new ArrayList<>();

        for (Document doc : docs) {
            ExtractionResult result = extractor.extract(doc);
            for (ExtractedEntity entity : result.entities()) {
                addEntity(mergedEntities, entity);
            }
            mergedRelations.addAll(result.relations());
        }

        return ExtractionResult.of(
                new ArrayList<>(mergedEntities.values()),
                mergedRelations,
                new ExtractionMetadata(null, null, extractorName, null)
        );
    }

    /**
     * Creates an AUTHORED_BY relation and PERSON entities from an author metadata value.
     * Splits multiple authors and adds each as a separate PERSON entity.
     *
     * @param authorValue  raw author string (may contain multiple authors)
     * @param docEntityId  the source document entity ID
     * @param displayTitle the document display title (for relation descriptions)
     * @param entityIndex  the entity dedup index to add PERSON entities to
     * @param relations    the relations list to add AUTHORED_BY relations to
     */
    public static void extractAuthors(String authorValue, String docEntityId, String displayTitle,
                                       Map<String, ExtractedEntity> entityIndex,
                                       List<ExtractedRelation> relations) {
        for (String name : splitAuthors(authorValue)) {
            String personId = entityId("person:" + name.toLowerCase());
            ExtractedEntity personEntity = new ExtractedEntity(
                    personId, name, GraphConstants.ENTITY_PERSON,
                    null, "Document author", 0.9,
                    Map.of(GraphConstants.PROP_SOURCE_FIELD, "author")
            );
            addEntity(entityIndex, personEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, personId, GraphConstants.REL_AUTHORED_BY,
                    displayTitle + " authored by " + name,
                    0.9, Map.of(GraphConstants.PROP_SOURCE_FIELD, GraphConstants.PROVENANCE_EXTRACTED)
            ));
        }
    }

    /**
     * Creates a PRODUCED_BY relation and ORGANIZATION entity from a producer metadata value.
     *
     * @param producerValue raw producer string
     * @param docEntityId   the source document entity ID
     * @param displayTitle  the document display title (for relation descriptions)
     * @param entityIndex   the entity dedup index to add the ORGANIZATION entity to
     * @param relations     the relations list to add PRODUCED_BY relation to
     */
    public static void extractProducer(String producerValue, String docEntityId, String displayTitle,
                                        Map<String, ExtractedEntity> entityIndex,
                                        List<ExtractedRelation> relations) {
        String orgId = entityId("org:" + producerValue.toLowerCase());
        ExtractedEntity orgEntity = new ExtractedEntity(
                orgId, producerValue, GraphConstants.ENTITY_ORGANIZATION,
                null, "Document producing software", 0.7,
                Map.of(GraphConstants.PROP_SOURCE_FIELD, "producer")
        );
        addEntity(entityIndex, orgEntity);
        relations.add(new ExtractedRelation(
                docEntityId, orgId, GraphConstants.REL_PRODUCED_BY,
                displayTitle + " produced by " + producerValue,
                0.7, Map.of(GraphConstants.PROP_SOURCE_FIELD, GraphConstants.PROVENANCE_EXTRACTED)
        ));
    }

    /**
     * Creates HAS_TOPIC relations and TOPIC entities from a keywords metadata value.
     *
     * @param keywordsValue raw keywords string (comma/semicolon/pipe separated)
     * @param docEntityId   the source document entity ID
     * @param displayTitle  the document display title (for relation descriptions)
     * @param entityIndex   the entity dedup index to add TOPIC entities to
     * @param relations     the relations list to add HAS_TOPIC relations to
     */
    public static void extractTopics(String keywordsValue, String docEntityId, String displayTitle,
                                      Map<String, ExtractedEntity> entityIndex,
                                      List<ExtractedRelation> relations) {
        for (String keyword : splitKeywords(keywordsValue)) {
            String topicId = entityId("topic:" + keyword.toLowerCase());
            ExtractedEntity topicEntity = new ExtractedEntity(
                    topicId, keyword, GraphConstants.ENTITY_TOPIC,
                    null, "Document keyword/topic", 0.8,
                    Map.of(GraphConstants.PROP_SOURCE_FIELD, "keywords")
            );
            addEntity(entityIndex, topicEntity);
            relations.add(new ExtractedRelation(
                    docEntityId, topicId, GraphConstants.REL_HAS_TOPIC,
                    displayTitle + " has topic: " + keyword,
                    0.8, Map.of(GraphConstants.PROP_SOURCE_FIELD, GraphConstants.PROVENANCE_EXTRACTED)
            ));
        }
    }

    /**
     * Adds a metadata property to the map if the value is present and non-null.
     * Converts the value to a String.
     */
    public static void putIfPresent(Map<String, String> props, String key, Map<String, Object> meta, String metaKey) {
        Object val = meta.get(metaKey);
        if (val != null) {
            String s = str(val);
            if (s != null) {
                props.put(key, s);
            }
        }
    }

    /**
     * Adds a metadata property to the map using String.valueOf if the value is present.
     * Useful for numeric values where toString/trim normalization isn't needed.
     */
    public static void putValueIfPresent(Map<String, String> props, String key, Map<String, Object> meta, String metaKey) {
        Object val = meta.get(metaKey);
        if (val != null) {
            props.put(key, String.valueOf(val));
        }
    }

    /**
     * Copies multiple metadata entries to a properties map in one call.
     * Each pair is (metaKey, propKey): reads meta.get(metaKey) and writes props.put(propKey, value.toString()).
     * Skips null values.
     *
     * @param props  target property map
     * @param meta   source metadata map
     * @param pairs  alternating metaKey, propKey pairs
     */
    public static void copyMetaToProps(Map<String, String> props, Map<String, Object> meta, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object val = meta.get(pairs[i]);
            if (val != null) {
                props.put(pairs[i + 1], val.toString());
            }
        }
    }
}
