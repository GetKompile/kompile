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

package ai.kompile.app.services.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scores extraction outputs for correctness and merges several models' outputs into a single
 * weighted-consensus result for A/B extraction.
 *
 * <h3>Correctness score (0..1)</h3>
 * A model's raw response is scored on how usable its extraction is, independent of which model
 * produced it: it must be parseable JSON exposing an {@code entities} array (and ideally a
 * {@code relationships} array), and its entities/relationships must carry the expected identifying
 * fields. The score rewards <em>well-formed, populated</em> output and is 0 for anything that does
 * not parse or carries no entities. This feeds {@link CliAgentModelService} so model selection can
 * prefer models that produce <em>good</em> extractions, not merely non-empty ones.
 *
 * <h3>Weighted consensus (A/B)</h3>
 * Given several scored outputs for the same prompt, entities are unioned by normalized name and
 * relationships by {@code from|to|type}. Each item's confidence is the sum of the correctness scores
 * of the outputs that contain it, normalized by the total correctness across all outputs — so an
 * entity that several high-scoring models agree on scores near 1.0, while one only a single weak
 * model emitted scores low. The representative field values are taken from the highest-scoring output
 * that contains the item. The merged result is emitted in the same {@code {entities,relationships}}
 * shape the downstream graph extractor consumes, with a {@code confidence} field added to each item.
 *
 * <p>Field-name tolerant: entity name is read from {@code name}/{@code label}/{@code id}; entity type
 * from {@code type}/{@code entityType}; relationship endpoints from {@code from}/{@code source} and
 * {@code to}/{@code target}; relationship type from {@code type}/{@code relation}. The JSON object may
 * be embedded in prose — the first balanced {@code {…}} object is extracted.
 */
@Service
public class ExtractionConsensusService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionConsensusService.class);

    private final ObjectMapper objectMapper;

    public ExtractionConsensusService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A model's parsed + scored extraction. {@code correctness} is 0..1. */
    public record ScoredExtraction(String modelId, double correctness,
                                   List<Entity> entities, List<Relationship> relationships,
                                   String rawJson) {
    }

    public record Entity(String name, String type, ObjectNode fields) {
    }

    public record Relationship(String from, String to, String type, ObjectNode fields) {
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Scoring
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Parse {@code rawResponse} and compute a correctness score in [0,1]. Never throws — an
     * unparseable or entity-less response scores 0 with empty lists.
     */
    public ScoredExtraction score(String modelId, String rawResponse) {
        JsonNode root = parseJsonObject(rawResponse);
        if (root == null) {
            return new ScoredExtraction(modelId, 0.0, List.of(), List.of(), rawResponse);
        }
        List<Entity> entities = readEntities(root);
        List<Relationship> relationships = readRelationships(root);

        if (entities.isEmpty() && relationships.isEmpty()) {
            return new ScoredExtraction(modelId, 0.0, entities, relationships, rawResponse);
        }

        // Correctness = structure validity × field completeness, with a mild reward for richer output.
        //  • base 0.5 for valid JSON that exposes at least one entity or relationship;
        //  • up to +0.3 for field completeness (entities have name+type, rels have from/to/type);
        //  • up to +0.2 for having a non-trivial count of both entities AND relationships.
        double completeness = fieldCompleteness(entities, relationships);
        double richness = richness(entities.size(), relationships.size());
        double score = clamp01(0.5 + 0.3 * completeness + 0.2 * richness);
        return new ScoredExtraction(modelId, score, entities, relationships, rawResponse);
    }

    private double fieldCompleteness(List<Entity> entities, List<Relationship> rels) {
        int total = 0, complete = 0;
        for (Entity e : entities) {
            total++;
            if (notBlank(e.name()) && notBlank(e.type())) complete++;
        }
        for (Relationship r : rels) {
            total++;
            if (notBlank(r.from()) && notBlank(r.to()) && notBlank(r.type())) complete++;
        }
        return total == 0 ? 0.0 : (double) complete / total;
    }

    /** Reward extracting both entities and relationships, saturating so garbage-padding can't game it. */
    private double richness(int entityCount, int relCount) {
        double e = Math.min(1.0, entityCount / 5.0);
        double r = Math.min(1.0, relCount / 3.0);
        return (e + r) / 2.0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Weighted consensus merge
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Merge several scored outputs into one weighted-consensus JSON string in
     * {@code {"entities":[...],"relationships":[...]}} form, each item annotated with a
     * {@code confidence} in [0,1]. Outputs with correctness 0 are ignored. If only one usable
     * output remains, its raw JSON is returned unchanged.
     */
    public String merge(List<ScoredExtraction> outputs) {
        List<ScoredExtraction> usable = new ArrayList<>();
        for (ScoredExtraction s : outputs) {
            if (s != null && s.correctness() > 0.0
                    && (!s.entities().isEmpty() || !s.relationships().isEmpty())) {
                usable.add(s);
            }
        }
        if (usable.isEmpty()) {
            throw new IllegalArgumentException("Cannot merge extraction outputs: no usable model output scored above zero");
        }
        if (usable.size() == 1) {
            return usable.get(0).rawJson();
        }

        double totalWeight = usable.stream().mapToDouble(ScoredExtraction::correctness).sum();
        if (totalWeight <= 0) totalWeight = 1.0;

        // Union entities by normalized name; keep the representative from the highest-scoring output.
        Map<String, Agg<Entity>> entityAgg = new LinkedHashMap<>();
        for (ScoredExtraction s : usable) {
            for (Entity e : s.entities()) {
                String key = norm(e.name());
                if (key.isEmpty()) continue;
                entityAgg.computeIfAbsent(key, k -> new Agg<>()).add(e, s.correctness());
            }
        }
        Map<String, Agg<Relationship>> relAgg = new LinkedHashMap<>();
        for (ScoredExtraction s : usable) {
            for (Relationship r : s.relationships()) {
                String key = norm(r.from()) + "|" + norm(r.to()) + "|" + norm(r.type());
                if (key.replace("|", "").isEmpty()) continue;
                relAgg.computeIfAbsent(key, k -> new Agg<>()).add(r, s.correctness());
            }
        }

        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode entitiesOut = out.putArray("entities");
        for (Agg<Entity> agg : entityAgg.values()) {
            ObjectNode node = agg.best.fields().deepCopy();
            node.put("confidence", round(agg.weight / totalWeight));
            entitiesOut.add(node);
        }
        ArrayNode relsOut = out.putArray("relationships");
        for (Agg<Relationship> agg : relAgg.values()) {
            ObjectNode node = agg.best.fields().deepCopy();
            node.put("confidence", round(agg.weight / totalWeight));
            relsOut.add(node);
        }

        log.info("A/B consensus merge: {} outputs (correctness {}) → {} entities, {} relationships",
                usable.size(),
                usable.stream().map(s -> s.modelId() + "=" + round(s.correctness())).toList(),
                entitiesOut.size(), relsOut.size());
        return out.toString();
    }

    /** Accumulator: total agreement weight + the field-representative from the highest-scoring output. */
    private static final class Agg<T> {
        double weight = 0.0;
        double bestScore = -1.0;
        T best;

        void add(T item, double score) {
            weight += score;
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JSON parsing (field-name tolerant, prose-embedded JSON tolerant)
    // ═══════════════════════════════════════════════════════════════════════════

    private JsonNode parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String json = extractFirstJsonObject(raw);
        if (json == null) return null;
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract the first balanced {@code {…}} object from possibly-prose text. */
    private String extractFirstJsonObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private List<Entity> readEntities(JsonNode root) {
        List<Entity> out = new ArrayList<>();
        JsonNode arr = firstArray(root, "entities", "Entities", "nodes");
        if (arr == null) return out;
        for (JsonNode n : arr) {
            if (!n.isObject()) continue;
            String name = text(n, "name", "label", "id", "value");
            String type = text(n, "type", "entityType", "entity_type", "category");
            out.add(new Entity(name, type, (ObjectNode) n));
        }
        return out;
    }

    private List<Relationship> readRelationships(JsonNode root) {
        List<Relationship> out = new ArrayList<>();
        JsonNode arr = firstArray(root, "relationships", "Relationships", "relations", "edges");
        if (arr == null) return out;
        for (JsonNode n : arr) {
            if (!n.isObject()) continue;
            String from = text(n, "from", "source", "subject", "head");
            String to = text(n, "to", "target", "object", "tail");
            String type = text(n, "type", "relation", "relationship", "predicate");
            out.add(new Relationship(from, to, type, (ObjectNode) n));
        }
        return out;
    }

    private JsonNode firstArray(JsonNode root, String... keys) {
        for (String k : keys) {
            JsonNode n = root.get(k);
            if (n != null && n.isArray()) return n;
        }
        return null;
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) return v.asText();
        }
        return "";
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
