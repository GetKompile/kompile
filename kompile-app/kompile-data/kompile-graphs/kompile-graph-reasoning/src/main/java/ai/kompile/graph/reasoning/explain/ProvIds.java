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
package ai.kompile.graph.reasoning.explain;

/**
 * Deterministic PROV identifier helpers for {@link ProvSerializer}.
 *
 * <p>All IDs are derived from the step's <em>positional path</em> through the
 * {@link ReasoningTrace} tree so that the same trace always serializes identically — a
 * property the {@link ProvSerializerTest} relies on.  Conclusion text does NOT appear
 * in the id (it lives in {@code rdfs:label} / the PROV-N string literal instead).</p>
 *
 * <p>ID structure:</p>
 * <pre>
 *   e_root            – entity for the root step
 *   e_root_0          – entity for root's first premise
 *   e_root_1_2        – entity for root → premise[1] → premise[2]
 *   a_root            – activity for the root step (derived kinds only)
 *   a_root_0          – activity for root's first premise (derived kinds only)
 *   ag_&lt;n&gt;            – agent for the n-th distinct source string (1-based)
 * </pre>
 *
 * <p>Entity ids are deduplicated by content in {@link ProvSerializer}: when the same
 * (conclusion, kind) pair appears in two branches the first positional id wins and later
 * occurrences reuse it.  Activity ids remain positional (activities represent the
 * computation event, not the conclusion value).</p>
 */
public final class ProvIds {

    /** Namespace prefix for kompile annotations. */
    public static final String NS_KOMPILE = "https://kompile.ai/prov#";

    /** Namespace for PROV-O. */
    public static final String NS_PROV = "http://www.w3.org/ns/prov#";

    /** Namespace for rdfs. */
    public static final String NS_RDFS = "http://www.w3.org/2000/01/rdf-schema#";

    private ProvIds() { }

    /** Entity id for a step at the given position path (e.g. {@code "root_0_1"}). */
    public static String entityId(String path) {
        return "e_" + path;
    }

    /** Activity id for a step at the given position path. */
    public static String activityId(String path) {
        return "a_" + path;
    }

    /** Agent id for a 1-based agent index. */
    public static String agentId(int index) {
        return "ag_" + index;
    }

    /** Position path for the root step. */
    public static String rootPath() {
        return "root";
    }

    /** Position path for a child of {@code parentPath} at index {@code idx}. */
    public static String childPath(String parentPath, int idx) {
        return parentPath + "_" + idx;
    }

    /**
     * Sanitize a meta key so it forms a valid XML/PROV-N local name: replace any
     * character that is not {@code [A-Za-z0-9_\-]} with {@code _}.
     */
    public static String sanitizeMetaKey(String key) {
        if (key == null) return "_null";
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
