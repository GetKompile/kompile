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
package ai.kompile.graph.reasoning.embedding.learn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hand-rolled JSON serialization for {@link EmbeddingTable} — the learned embedding vectors
 * as a first-class portable artifact.
 *
 * <h2>Format</h2>
 * <p>A flat JSON object:</p>
 * <pre>
 * {
 *   "dim": 8,
 *   "entityIds": ["Alice","Bob","Carol"],
 *   "vectors": [
 *     [0.1, 0.2, ...],
 *     [0.3, 0.4, ...],
 *     [0.5, 0.6, ...]
 *   ]
 * }
 * </pre>
 * <p>Only the entity (target) vectors are persisted — these are the product of training.
 * The context vectors are not part of the public output contract of {@link EmbeddingTable}
 * and are omitted here; a restored table will have zero context vectors (which is the same
 * as a freshly constructed table used only for retrieval).</p>
 *
 * <h2>Infra-free contract</h2>
 * <p>No jackson-databind, no Spring, no JPA. Pattern mirrors
 * {@link ai.kompile.graph.reasoning.learning.PslWeightLearningService#weightsToJson} and
 * {@link ai.kompile.graph.reasoning.learning.MebnWeightSerializer#strengthsToJson}.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   // Serialize after training
 *   String json = EmbeddingTableIO.toJson(table);
 *
 *   // Restore
 *   EmbeddingTable restored = EmbeddingTableIO.fromJson(json);
 *   double[] aliceVec = restored.vector("Alice");
 * </pre>
 */
public final class EmbeddingTableIO {

    private EmbeddingTableIO() { }

    // ═════════════════════════════════════════════════════════════════════════
    // Serialization
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Serialize the entity vectors in {@code table} to a JSON string.
     *
     * <p>The output includes {@code dim}, the ordered {@code entityIds} list, and a
     * {@code vectors} array-of-arrays (one inner array per entity, in id-insertion order).
     * Values are written with full precision ({@code %.17g}) to survive round-trips without
     * floating-point degradation.</p>
     *
     * @param table the embedding table to serialize (never {@code null})
     * @return a JSON string parseable by {@link #fromJson}
     */
    public static String toJson(EmbeddingTable table) {
        // Collect ordered ids + vectors via asMap() which preserves insertion order.
        Map<String, double[]> byId = table.asMap();
        List<String>   ids  = new ArrayList<>(byId.keySet());
        List<double[]> vecs = new ArrayList<>(byId.values());
        int dim = table.dim();

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"dim\":").append(dim).append(',');
        sb.append("\"entityIds\":").append(toJsonStringArray(ids)).append(',');
        sb.append("\"vectors\":[");
        for (int i = 0; i < vecs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(toJsonDoubleRow(vecs.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Deserialization
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Deserialize an {@link EmbeddingTable} from a JSON string produced by {@link #toJson}.
     *
     * <p>The restored table exposes entity vectors via {@link EmbeddingTable#vector(String)}
     * with the same values that were serialized. Context vectors are zero (context is an
     * internal training artifact; the public output contract of {@link EmbeddingTable} only
     * covers entity vectors).</p>
     *
     * @param json JSON string produced by {@link #toJson} (never {@code null})
     * @return a restored {@link EmbeddingTable}
     * @throws IllegalArgumentException if the JSON is malformed or missing required fields
     */
    public static EmbeddingTable fromJson(String json) {
        ParsedTable p = parse(json);
        if (p.dim < 1) {
            throw new IllegalArgumentException("EmbeddingTableIO.fromJson: invalid dim=" + p.dim);
        }
        if (p.ids.size() != p.vectors.size()) {
            throw new IllegalArgumentException(
                    "EmbeddingTableIO.fromJson: entityIds.size()=" + p.ids.size()
                    + " != vectors.size()=" + p.vectors.size());
        }

        // Build an EmbeddingTable from the plain-Java constructor (random init),
        // then overwrite each entity row with the deserialized vector.
        // Seed=0 is fine because we immediately overwrite all values.
        EmbeddingTable table = new EmbeddingTable(p.ids, p.dim, 0L);
        for (int i = 0; i < p.ids.size(); i++) {
            double[] row = table.vector(p.ids.get(i));
            if (row != null) {
                double[] src = p.vectors.get(i);
                System.arraycopy(src, 0, row, 0, Math.min(row.length, src.length));
            }
        }
        return table;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal parsing
    // ─────────────────────────────────────────────────────────────────────────

    private static final class ParsedTable {
        int          dim     = -1;
        List<String>   ids   = new ArrayList<>();
        List<double[]> vectors = new ArrayList<>();
    }

    private static ParsedTable parse(String json) {
        ParsedTable out = new ParsedTable();
        String s = json.trim();

        // ── dim ─────────────────────────────────────────────────────────────
        int dimIdx = s.indexOf("\"dim\":");
        if (dimIdx >= 0) {
            int vs = dimIdx + 6; // after "dim":
            while (vs < s.length() && s.charAt(vs) <= ' ') vs++;
            int ve = vs;
            while (ve < s.length() && s.charAt(ve) != ',' && s.charAt(ve) != '}') ve++;
            try { out.dim = Integer.parseInt(s.substring(vs, ve).trim()); }
            catch (NumberFormatException ignore) { }
        }

        // ── entityIds ────────────────────────────────────────────────────────
        int eIdx = s.indexOf("\"entityIds\":");
        if (eIdx >= 0) {
            int arrStart = s.indexOf('[', eIdx + 12);
            if (arrStart >= 0) {
                out.ids = parseStringArray(s, arrStart);
            }
        }

        // ── vectors ──────────────────────────────────────────────────────────
        int vIdx = s.indexOf("\"vectors\":");
        if (vIdx >= 0) {
            int arrStart = s.indexOf('[', vIdx + 10);
            if (arrStart >= 0) {
                out.vectors = parseVectorsArray(s, arrStart);
            }
        }

        return out;
    }

    /** Parse the outer {@code vectors} array: an array of double arrays. */
    private static List<double[]> parseVectorsArray(String s, int start) {
        List<double[]> out = new ArrayList<>();
        int i = start + 1; // skip outer '['
        int n = s.length();
        while (i < n && s.charAt(i) != ']') {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == ']') break;
            if (s.charAt(i) == '[') {
                out.add(parseDoubleRow(s, i));
                i = skipArray(s, i);
            } else {
                i++;
            }
        }
        return out;
    }

    /** Parse a single inner {@code [v0, v1, ...]} row. */
    private static double[] parseDoubleRow(String s, int start) {
        List<Double> vals = new ArrayList<>();
        int i = start + 1; // skip '['
        int n = s.length();
        while (i < n && s.charAt(i) != ']') {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == ']') break;
            int vs = i;
            while (i < n && s.charAt(i) != ',' && s.charAt(i) != ']') i++;
            String tok = s.substring(vs, i).trim();
            if (!tok.isEmpty()) {
                try { vals.add(Double.parseDouble(tok)); } catch (NumberFormatException ignore) { }
            }
        }
        double[] arr = new double[vals.size()];
        for (int j = 0; j < arr.length; j++) arr[j] = vals.get(j);
        return arr;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON building helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String toJsonStringArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(items.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String toJsonDoubleRow(double[] row) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < row.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.17g", row[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared parsing utilities (mirrors PslWeightLearningService / MebnWeightSerializer)
    // ─────────────────────────────────────────────────────────────────────────

    private static List<String> parseStringArray(String s, int start) {
        List<String> out = new ArrayList<>();
        int i = start + 1;
        int n = s.length();
        while (i < n && s.charAt(i) != ']') {
            while (i < n && (s.charAt(i) <= ' ' || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == ']') break;
            if (s.charAt(i) == '"') {
                int vs = i + 1;
                i = endQuote(s, vs);
                out.add(unescape(s.substring(vs, i)));
                i++;
            } else {
                break;
            }
        }
        return out;
    }

    private static int skipArray(String s, int start) {
        int depth = 0, i = start, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i + 1; }
            else if (c == '"') { i = endQuote(s, i + 1) + 1; continue; }
            i++;
        }
        return i;
    }

    private static int endQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"')  return i;
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
