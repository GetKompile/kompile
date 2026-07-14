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
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.RuleAtom;
import ai.kompile.graph.reasoning.unified.MiniJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON serialization helper for {@link DatalogRule} lists bundled inside a {@code .kgraph} file.
 *
 * <h3>Canonical artifact key</h3>
 * <p>Rules are stored under the artifact key {@value #ARTIFACT_KEY} as a UTF-8 JSON array.
 * To bundle rules with a graph:
 * <pre>
 *   List&lt;DatalogRule&gt; rules = List.of(
 *       FolDatalogAdapter.copyRule("ancestor", "parent"),
 *       FolDatalogAdapter.transitivityRule("ancestor"));
 *   graph.putArtifactText(DatalogRulesBundle.ARTIFACT_KEY, DatalogRulesBundle.toJson(rules));
 * </pre>
 * On {@link LocalReasoningSession#open} the rules are automatically materialized into the
 * inferred fact store so {@code ask_graph_verify} / {@code ask_graph_explain} see derived atoms.
 * A graph without this artifact behaves exactly as before (observed-only).</p>
 *
 * <h3>JSON format</h3>
 * <p>Each rule is an object: {@code {"head": "p", "headArgs": ["?X","?Z"], "body": [{"pred":"q",
 * "args":["?X","?Y"],"neg":false}, ...]}}.</p>
 */
public final class DatalogRulesBundle {

    /** Artifact key under which rules are stored inside the {@code .kgraph} file. */
    public static final String ARTIFACT_KEY = "datalog_rules";

    private DatalogRulesBundle() {}

    /**
     * Serialize a list of {@link DatalogRule}s to a UTF-8 JSON string suitable for
     * {@link ai.kompile.graph.reasoning.unified.UnifiedGraph#putArtifactText}.
     */
    public static String toJson(List<DatalogRule> rules) {
        List<Object> arr = new ArrayList<>(rules.size());
        for (DatalogRule r : rules) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("head", r.headPredicate());
            obj.put("headArgs", r.headArgs());

            List<Object> bodyArr = new ArrayList<>(r.body().size());
            for (RuleAtom atom : r.body()) {
                Map<String, Object> atomObj = new LinkedHashMap<>();
                atomObj.put("pred", atom.predicate());
                atomObj.put("args", atom.args());
                atomObj.put("neg", atom.negated());
                bodyArr.add(atomObj);
            }
            obj.put("body", bodyArr);
            arr.add(obj);
        }
        return MiniJson.write(arr);
    }

    /**
     * Deserialize a UTF-8 JSON string (previously produced by {@link #toJson}) into a list
     * of {@link DatalogRule}s. Returns an empty list on any parse error.
     */
    @SuppressWarnings("unchecked")
    public static List<DatalogRule> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object parsed = MiniJson.parse(json);
            if (!(parsed instanceof List<?> rawList)) return List.of();
            List<DatalogRule> rules = new ArrayList<>(rawList.size());
            for (Object rawRule : rawList) {
                if (!(rawRule instanceof Map<?, ?> ruleMap)) continue;
                String head = strOf(ruleMap, "head");
                if (head == null) continue;

                List<String> headArgs = strList(ruleMap.get("headArgs"));
                List<Object> rawBody  = objList(ruleMap.get("body"));

                List<RuleAtom> body = new ArrayList<>(rawBody.size());
                for (Object rawAtom : rawBody) {
                    if (!(rawAtom instanceof Map<?, ?> atomMap)) continue;
                    String pred = strOf(atomMap, "pred");
                    if (pred == null) continue;
                    List<String> args = strList(atomMap.get("args"));
                    boolean neg = boolOf(atomMap, "neg");
                    body.add(new RuleAtom(pred, args, neg));
                }
                rules.add(new DatalogRule(head, headArgs, body));
            }
            return List.copyOf(rules);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Deserialize from raw UTF-8 bytes (artifact bytes from
     * {@link ai.kompile.graph.reasoning.unified.UnifiedGraph#artifact}).
     */
    public static List<DatalogRule> fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return List.of();
        return fromJson(new String(bytes, StandardCharsets.UTF_8));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String strOf(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean boolOf(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(v.toString().trim());
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) out.add(item.toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> objList(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        return (List<Object>) list;
    }
}
