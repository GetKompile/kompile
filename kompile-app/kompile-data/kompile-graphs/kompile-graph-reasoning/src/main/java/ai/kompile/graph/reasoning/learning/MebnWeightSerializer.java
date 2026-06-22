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
package ai.kompile.graph.reasoning.learning;

import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Serializes and deserializes MEBN learned edge strengths, the MEBN counterpart to
 * {@link PslWeightLearningService#weightsToJson} / {@link PslWeightLearningService#parseWeights}.
 *
 * <p>PSL learned weights already persist via {@link PslWeightLearningService}; this class provides
 * the same capability for MEBN noisy-OR edge strengths so they survive sessions. The format is
 * hand-rolled JSON (no jackson-databind) keyed by {@code "<mfragName>|<parent>-><child>"} with
 * {@code double} strength values.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   // Persist after learning
 *   String json = MebnWeightSerializer.strengthsToJson(theory);
 *   // files.save(json);
 *
 *   // Restore on next session
 *   String json = files.load();
 *   MebnWeightSerializer.applyStrengths(theory, json);
 * </pre>
 */
public final class MebnWeightSerializer {

    private MebnWeightSerializer() {
    }

    /**
     * Serialize every MFrag edge strength in {@code theory} as JSON.
     *
     * <p>The resulting object maps {@code "<mfragName>|<parent>-><child>"} to the numeric strength.
     * Numbers are formatted with {@link Locale#ROOT} for locale-independence. Keys are escaped
     * the same way as {@link PslWeightLearningService#weightsToJson}.</p>
     *
     * @param theory the MTheory whose edge strengths to serialize
     * @return a JSON string; {@code "{}"} when there are no edges
     */
    public static String strengthsToJson(MTheory theory) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (MFrag mfrag : theory.getMFrags()) {
            for (Map.Entry<String, Double> entry : mfrag.getEdgeStrengths().entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                String compositeKey = mfrag.getName() + "|" + entry.getKey();
                sb.append('"').append(escape(compositeKey)).append("\":");
                sb.append(String.format(Locale.ROOT, "%s", entry.getValue()));
            }
        }
        return sb.append('}').toString();
    }

    /**
     * Parse {@code json} produced by {@link #strengthsToJson} and re-inject the strengths
     * onto the matching MFrags in {@code theory} via {@link MFrag#setEdgeStrength}.
     *
     * <p>Each key has the form {@code "<mfragName>|<parent>-><child>"}. The mfrag name is
     * matched by splitting on the last {@code |}, and the {@code parent->child} pair by splitting
     * on {@code ->}. MFrags or edges not present in the theory are silently skipped.</p>
     *
     * @param theory the MTheory to update in place
     * @param json   the JSON string previously produced by {@link #strengthsToJson}
     */
    public static void applyStrengths(MTheory theory, String json) {
        Map<String, Double> parsed = parseStrengths(json);
        for (Map.Entry<String, Double> entry : parsed.entrySet()) {
            String compositeKey = entry.getKey();
            // Split on the LAST '|' to separate mfrag name from "parent->child"
            int lastPipe = compositeKey.lastIndexOf('|');
            if (lastPipe < 0) {
                continue;
            }
            String mfragName = compositeKey.substring(0, lastPipe);
            String edgeKey   = compositeKey.substring(lastPipe + 1);
            // Split "parent->child" on "->"
            int arrowIdx = edgeKey.indexOf("->");
            if (arrowIdx < 0) {
                continue;
            }
            String parent = edgeKey.substring(0, arrowIdx);
            String child  = edgeKey.substring(arrowIdx + 2);
            MFrag mfrag = theory.getMFrag(mfragName);
            if (mfrag != null) {
                mfrag.setEdgeStrength(parent, child, entry.getValue());
            }
        }
    }

    /**
     * Parse the JSON produced by {@link #strengthsToJson} into a {@code compositeKey → strength} map.
     * Robust to surrounding whitespace; skips malformed entries.
     *
     * <p>This is public so that infrastructure adapters in other packages (e.g.
     * {@code ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter}) can read
     * serialized edge strengths without needing to hold an {@link MTheory} instance.</p>
     */
    public static Map<String, Double> parseStrengths(String json) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        String s = json.trim();
        if (s.startsWith("{")) {
            s = s.substring(1);
        }
        if (s.endsWith("}")) {
            s = s.substring(0, s.length() - 1);
        }
        int i = 0;
        int n = s.length();
        while (i < n) {
            // skip whitespace
            while (i < n && s.charAt(i) <= ' ') {
                i++;
            }
            if (i >= n || s.charAt(i) != '"') {
                break;
            }
            int keyStart = i + 1;
            i = endQuote(s, keyStart);
            String key = unescape(s.substring(keyStart, i));
            i++; // skip closing quote
            // skip whitespace and ':'
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ':')) {
                i++;
            }
            int valStart = i;
            while (i < n && s.charAt(i) != ',') {
                i++;
            }
            try {
                out.put(key, Double.parseDouble(s.substring(valStart, i).trim()));
            } catch (NumberFormatException ignored) {
                // skip malformed value
            }
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) {
                i++;
            }
        }
        return out;
    }

    private static int endQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                i++;
                continue;
            }
            if (s.charAt(i) == '"') {
                return i;
            }
        }
        return s.length();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
