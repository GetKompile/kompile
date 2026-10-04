package ai.kompile.graph.reasoning.psl;

import ai.kompile.graph.reasoning.learning.PslWeightLearningService;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parameters for the default graph builder, NOT a codec for arbitrary PSL programs/checkpoints. */
public final class GraphPslWeightsArtifactCodec {
    public static final String ARTIFACT = "reasoning/graph-psl-weights.v1.json";
    public static final String LEGACY_ARTIFACT = "reasoning/psl-weights.json";
    public static final String FORMAT = "kompile-graph-psl-weights";
    public static final String BUILDER_CONTRACT = "graph-psl-default-v3";
    public static final int MAX_JSON_CHARS = 32_768;
    /** Bounded numerical contract for the portable scalar solver; weights are not rescaled. */
    public static final double MAX_WEIGHT = 1_000_000;
    private static final List<String> RULE_IDS = List.of("propagation", "abduction", "conflict", "prior-forward", "prior-reverse");
    private static final Map<String, String> SIGNATURES = signatures();

    private GraphPslWeightsArtifactCodec() {}

    private static Map<String, String> signatures() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("sample", "ENTITY", "sample");
        List<PslRule> rules = new GraphPslProgramBuilder().build(graph).rules();
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < RULE_IDS.size(); i++) result.put(RULE_IDS.get(i), rules.get(i).signature());
        return Map.copyOf(result);
    }

    public static String toJson(PslProgram program) {
        Map<String, Double> bySignature = new LinkedHashMap<>();
        if (!program.arithmeticRules().isEmpty()) throw new IllegalArgumentException("Arithmetic PSL is not the default graph contract");
        for (PslRule rule : program.rules()) {
            if (rule.hard() || !SIGNATURES.containsValue(rule.signature())
                    || bySignature.putIfAbsent(rule.signature(), weight(rule.weight())) != null) {
                throw new IllegalArgumentException("Only the five default graph PSL rules can be exported as parameters");
            }
        }
        if (bySignature.size() != RULE_IDS.size()) throw new IllegalArgumentException("Incomplete default graph PSL rules");
        List<Object> rows = new ArrayList<>();
        for (String id : RULE_IDS) rows.add(Map.of("id", id, "weight", bySignature.get(SIGNATURES.get(id))));
        return MiniJson.write(Map.of("format", FORMAT, "version", 1, "builderContract", BUILDER_CONTRACT, "rules", rows));
    }

    /** Prefer canonical JSON; present but invalid canonical data never falls back to legacy. */
    public static PslProgram apply(UnifiedGraph graph, PslProgram program) {
        String canonical = graph.artifactText(ARTIFACT);
        String legacy = graph.artifactText(LEGACY_ARTIFACT);
        Map<String, Double> weights = canonical != null ? fromJson(canonical)
                : legacy != null ? fromLegacyJson(legacy) : Map.of();
        return weights.isEmpty() ? program : PslWeightLearningService.applyWeights(program, weights);
    }

    public static Map<String, Double> fromJson(String json) {
        Map<?, ?> root = object(parse(json));
        if (!root.keySet().equals(Set.of("format", "version", "builderContract", "rules"))
                || !FORMAT.equals(root.get("format")) || !Long.valueOf(1).equals(root.get("version"))
                || !BUILDER_CONTRACT.equals(root.get("builderContract"))) {
            throw new IllegalArgumentException("Unsupported graph PSL weights format/version/builder contract");
        }
        if (!(root.get("rules") instanceof List<?> rows) || rows.size() != RULE_IDS.size()) {
            throw new IllegalArgumentException("Expected five default graph PSL weights");
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (Object row : rows) {
            Map<?, ?> rule = object(row);
            Object id = rule.get("id");
            if (!rule.keySet().equals(Set.of("id", "weight")) || !(id instanceof String) || !SIGNATURES.containsKey(id)) {
                throw new IllegalArgumentException("Unknown default graph PSL rule");
            }
            if (result.putIfAbsent(SIGNATURES.get(id), weight(rule.get("weight"))) != null) {
                throw new IllegalArgumentException("Duplicate default graph PSL rule");
            }
        }
        return result;
    }

    /** Bounded legacy compatibility: known signatures only; partial maps retain explicit defaults. */
    public static Map<String, Double> fromLegacyJson(String json) {
        Map<?, ?> root = object(parse(json));
        if (root.isEmpty() || root.size() > RULE_IDS.size()) throw new IllegalArgumentException("Empty/oversized PSL weights map");
        Map<String, Double> result = new LinkedHashMap<>();
        for (var entry : root.entrySet()) {
            String signature = PslRule.withoutWeight((String) entry.getKey());
            if (!SIGNATURES.containsValue(signature)) throw new IllegalArgumentException("Unsupported PSL rule signature: " + signature);
            if (result.putIfAbsent(signature, weight(entry.getValue())) != null) throw new IllegalArgumentException("Duplicate PSL rule signature");
        }
        return result;
    }

    private static Object parse(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("PSL weights JSON size limit exceeded");
        return MiniJson.parseStrict(json, 4);
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected PSL weights JSON object");
        return map;
    }

    private static double weight(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > MAX_WEIGHT) {
            throw new IllegalArgumentException("Portable PSL weights must be finite numbers in [0, " + MAX_WEIGHT + "]");
        }
        return n.doubleValue();
    }
}
