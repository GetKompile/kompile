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
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.agent.RelationExtractionAgent;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.retrievers.RetrievedDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link RelationExtractionAgent} that uses regular-expression patterns to identify named
 * entities (PERSON, ORGANIZATION, LOCATION, DATE) in text and creates co-occurrence
 * relationships between entities found in the same chunk.
 *
 * <p>This agent requires no external LLM and runs entirely on the JVM.  It is intended as
 * a fast, always-available baseline that can be combined with LLM-based agents via the
 * multi-agent merge strategies.
 */
@Component
@Slf4j
public class PatternRelationExtractionAgent implements RelationExtractionAgent {

    public static final String AGENT_ID = "pattern-ner";

    // ─── Entity patterns ────────────────────────────────────────────────────

    /** Capitalised word sequences that are likely proper nouns (PERSON / ORGANIZATION / LOCATION). */
    private static final Pattern PROPER_NOUN_SEQUENCE = Pattern.compile(
            "\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,4})\\b");

    /**
     * Organisation suffixes — when the match ends with one of these we label it ORGANIZATION.
     */
    private static final Pattern ORG_SUFFIX = Pattern.compile(
            "\\b(?:Inc|Corp|Ltd|LLC|LLP|Co|Company|Group|Institute|University|College|" +
            "Foundation|Association|Agency|Bureau|Department|Ministry|Authority|Council)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Location indicators — when the match contains one of these we label it LOCATION.
     */
    private static final Pattern LOCATION_INDICATOR = Pattern.compile(
            "\\b(?:Street|Avenue|Boulevard|Road|Lane|Drive|Court|Place|Park|Lake|River|" +
            "Mountain|Valley|Bay|Gulf|Sea|Ocean|Island|City|Town|Village|County|State|" +
            "Province|Republic|Kingdom|Empire)\\b",
            Pattern.CASE_INSENSITIVE);

    /** ISO-8601 dates, US long dates, and common short-form dates. */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\b(?:" +
            "\\d{4}-\\d{2}-\\d{2}" +                                    // 2025-01-15
            "|(?:January|February|March|April|May|June|July|August|" +
               "September|October|November|December)" +
               "\\s+\\d{1,2},?\\s+\\d{4}" +                            // January 15, 2025
            "|\\d{1,2}/\\d{1,2}/\\d{2,4}" +                            // 1/15/2025
            "|\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{4}" // 15 Jan 2025
            + ")\\b",
            Pattern.CASE_INSENSITIVE);

    // ─── Stop-words that look capitalised but aren't proper nouns ───────────

    private static final Set<String> STOP_WORDS = Set.of(
            "The", "This", "That", "These", "Those", "A", "An", "And", "Or", "But",
            "In", "On", "At", "To", "For", "Of", "With", "By", "From", "As", "Is",
            "Are", "Was", "Were", "Be", "Been", "Being", "Have", "Has", "Had",
            "Do", "Does", "Did", "Will", "Would", "Could", "Should", "May", "Might",
            "Can", "Shall", "Not", "No", "All", "Any", "Each", "Every", "Its", "It",
            "He", "She", "They", "We", "I", "You", "Who", "Which", "What", "When",
            "Where", "Why", "How", "If", "Then", "There", "Here", "Also", "More"
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // RelationExtractionAgent contract
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public String getId() {
        return AGENT_ID;
    }

    @Override
    public String getDescription() {
        return "Pattern-based named entity recognition and co-occurrence relations";
    }

    @Override
    public Set<String> supportedContentTypes() {
        // Empty set means "any content type"
        return Set.of();
    }

    @Override
    public ExtractionResult extract(List<RetrievedDoc> chunks, ExtractionConfig config) {
        long startTime = System.currentTimeMillis();

        if (chunks == null || chunks.isEmpty()) {
            return emptyResult(0, 0);
        }

        ExtractionConfig effectiveConfig = config != null ? config : ExtractionConfig.defaults();
        double minConf = effectiveConfig.minConfidence();

        // Accumulate entities and relations across all chunks
        Map<String, Entity> entityById = new LinkedHashMap<>();
        List<Relationship> relationships = new ArrayList<>();
        int processedChunks = 0;

        for (RetrievedDoc chunk : chunks) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("PatternRelationExtractionAgent: interrupted, stopping early");
                break;
            }
            if (!chunk.isText() || chunk.getText() == null || chunk.getText().isBlank()) {
                continue;
            }

            List<Entity> chunkEntities = extractEntitiesFromText(chunk.getText(), effectiveConfig);

            // Merge chunk entities into global map (first occurrence wins)
            for (Entity e : chunkEntities) {
                entityById.putIfAbsent(e.getId(), e);
            }

            // Create co-occurrence relationships for all entity pairs in this chunk
            List<Relationship> chunkRels = buildCooccurrenceRelations(chunkEntities);
            relationships.addAll(chunkRels);

            processedChunks++;
        }

        // Filter by confidence
        List<Entity> entities = new ArrayList<>(entityById.values());
        if (minConf > 0.0) {
            entities = entities.stream()
                    .filter(e -> e.getConfidence() == null || e.getConfidence() >= minConf)
                    .toList();
            relationships = relationships.stream()
                    .filter(r -> r.getConfidence() == null || r.getConfidence() >= minConf)
                    .toList();
        }

        // Deduplicate relationships (same source+target+type)
        relationships = deduplicateRelations(relationships);

        Graph graph = new Graph();
        graph.setEntities(new ArrayList<>(entities));
        graph.setRelationships(new ArrayList<>(relationships));

        long elapsed = System.currentTimeMillis() - startTime;
        AgentMetrics metrics = new AgentMetrics(
                AGENT_ID,
                elapsed,
                entities.size(),
                relationships.size(),
                processedChunks,
                null,
                Map.of("chunksProcessed", processedChunks)
        );

        log.info("PatternRelationExtractionAgent: {} entities, {} relations from {} chunks in {}ms",
                entities.size(), relationships.size(), processedChunks, elapsed);

        return new ExtractionResult(graph, metrics);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pattern matching helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private List<Entity> extractEntitiesFromText(String text, ExtractionConfig config) {
        List<Entity> entities = new ArrayList<>();
        Set<String> requestedTypes = config.entityTypes() != null
                ? Set.copyOf(config.entityTypes())
                : Set.of("PERSON", "ORGANIZATION", "LOCATION", "DATE", "CONCEPT");

        // Extract DATE entities
        if (requestedTypes.contains("DATE")) {
            Matcher dateMatcher = DATE_PATTERN.matcher(text);
            while (dateMatcher.find()) {
                String dateText = dateMatcher.group().trim();
                String entityId = "DATE:" + normaliseId(dateText);
                entities.add(buildEntity(entityId, dateText, "DATE", 0.85,
                        "Date reference: " + dateText));
            }
        }

        // Extract proper-noun sequences and classify as PERSON / ORGANIZATION / LOCATION
        Matcher matcher = PROPER_NOUN_SEQUENCE.matcher(text);
        while (matcher.find()) {
            String surface = matcher.group(1).trim();
            if (surface.isEmpty() || STOP_WORDS.contains(surface)) {
                continue;
            }
            // Skip tokens that are entirely a single common word
            if (surface.split("\\s+").length == 1 && surface.length() <= 2) {
                continue;
            }

            String type = classifyProperNoun(surface);
            if (!requestedTypes.contains(type)) {
                continue;
            }

            String entityId = type + ":" + normaliseId(surface);
            double confidence = surface.contains(" ") ? 0.75 : 0.60;
            entities.add(buildEntity(entityId, surface, type, confidence,
                    type + " detected by pattern matching"));
        }

        return entities;
    }

    private String classifyProperNoun(String surface) {
        if (ORG_SUFFIX.matcher(surface).find()) {
            return "ORGANIZATION";
        }
        if (LOCATION_INDICATOR.matcher(surface).find()) {
            return "LOCATION";
        }
        // Heuristic: multi-word with ≤ 3 tokens → PERSON; longer → ORGANIZATION
        String[] tokens = surface.split("\\s+");
        if (tokens.length <= 3) {
            return "PERSON";
        }
        return "ORGANIZATION";
    }

    /**
     * Build co-occurrence (CO_OCCURS_WITH) relationships between every unique pair of entities
     * found in the same chunk. Co-occurrence is a weak but fast signal.
     */
    private List<Relationship> buildCooccurrenceRelations(List<Entity> chunkEntities) {
        List<Relationship> rels = new ArrayList<>();
        // Deduplicate by ID within this chunk before pairing
        Map<String, Entity> byId = new LinkedHashMap<>();
        for (Entity e : chunkEntities) {
            byId.putIfAbsent(e.getId(), e);
        }
        List<Entity> unique = new ArrayList<>(byId.values());

        for (int i = 0; i < unique.size(); i++) {
            for (int j = i + 1; j < unique.size(); j++) {
                Entity src = unique.get(i);
                Entity tgt = unique.get(j);

                Relationship rel = new Relationship();
                rel.setSource(src.getId());
                rel.setTarget(tgt.getId());
                rel.setType("CO_OCCURS_WITH");
                rel.setDescription("Entities co-occurring in the same document chunk");
                rel.setConfidence(0.6);
                rel.setWeight(0.6);
                rels.add(rel);
            }
        }
        return rels;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utility helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private Entity buildEntity(String id, String title, String type,
                                double confidence, String description) {
        Entity e = new Entity();
        e.setId(id);
        e.setTitle(title);
        e.setType(type);
        e.setDescription(description);
        e.setConfidence(confidence);
        e.setMetadata(Map.of("source", AGENT_ID));
        return e;
    }

    private String normaliseId(String surface) {
        return surface.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private List<Relationship> deduplicateRelations(List<Relationship> rels) {
        Map<String, Relationship> seen = new LinkedHashMap<>();
        for (Relationship r : rels) {
            String key = r.getSource() + "|" + r.getTarget() + "|" + r.getType();
            // canonical order: src < tgt to treat undirected pairs as equal
            String altKey = r.getTarget() + "|" + r.getSource() + "|" + r.getType();
            if (!seen.containsKey(key) && !seen.containsKey(altKey)) {
                seen.put(key, r);
            }
        }
        return new ArrayList<>(seen.values());
    }

    private ExtractionResult emptyResult(int chunksProcessed, long elapsedMs) {
        Graph g = new Graph();
        g.setEntities(List.of());
        g.setRelationships(List.of());
        return new ExtractionResult(
                g,
                new AgentMetrics(AGENT_ID, elapsedMs, 0, 0, chunksProcessed, null, Map.of())
        );
    }
}
