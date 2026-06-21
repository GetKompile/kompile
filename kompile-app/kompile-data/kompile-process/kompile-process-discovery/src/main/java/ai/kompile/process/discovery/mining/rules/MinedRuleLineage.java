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

package ai.kompile.process.discovery.mining.rules;

import ai.kompile.process.discovery.mining.causal.CausalDependency;

import java.time.Instant;
import java.util.List;

/**
 * Lineage record for a single mined PSL rule: captures the causal arcs / directly-follows
 * evidence the rule was derived from so that the rule (and later a process built from it)
 * traces back to its graph facts.
 *
 * <p>Serialised alongside the rule file as a JSON sidecar at
 * {@code <dataDir>/rules/<factSheetId>-mined-lineage.json}.
 */
public record MinedRuleLineage(
        /** The PSL rule string this lineage entry describes. */
        String ruleText,
        /** Activity pair — predecessor. */
        String fromActivity,
        /** Activity pair — successor. */
        String toActivity,
        /** Heuristics-Miner dependency measure ∈ (-1,1); from the arc evidence. */
        double dependencyStrength,
        /** χ² statistic from the independence test; higher = more evidence. */
        double chiSquare,
        /** Whether the arc was statistically significant (χ² > 3.841, p=0.05). */
        boolean significant,
        /** Forward observation count: how many times toActivity followed fromActivity. */
        long forwardCount,
        /** Reverse observation count: how many times fromActivity followed toActivity. */
        long reverseCount,
        /** Causal edge type assigned to this arc (CAUSES, TRIGGERS, …). */
        String edgeType,
        /** When this lineage record was captured. */
        Instant capturedAt
) {

    /** Build a lineage entry from a live {@link CausalDependency}. */
    public static MinedRuleLineage from(String ruleText, CausalDependency dep) {
        return new MinedRuleLineage(
                ruleText,
                dep.from(),
                dep.to(),
                dep.dependency(),
                dep.chiSquare(),
                dep.significant(),
                dep.forward(),
                dep.reverse(),
                dep.type().name(),
                Instant.now()
        );
    }

    // ── Hand-rolled JSON (no Jackson in this module's non-optional path) ──────────

    /** Serialise to a single-line JSON string (no pretty-print). */
    public String toJson() {
        return "{" +
                jsonStr("ruleText", ruleText) + "," +
                jsonStr("fromActivity", fromActivity) + "," +
                jsonStr("toActivity", toActivity) + "," +
                "\"dependencyStrength\":" + dependencyStrength + "," +
                "\"chiSquare\":" + chiSquare + "," +
                "\"significant\":" + significant + "," +
                "\"forwardCount\":" + forwardCount + "," +
                "\"reverseCount\":" + reverseCount + "," +
                jsonStr("edgeType", edgeType) + "," +
                jsonStr("capturedAt", capturedAt.toString()) +
                "}";
    }

    private static String jsonStr(String key, String value) {
        return "\"" + esc(key) + "\":" + (value == null ? "null" : "\"" + esc(value) + "\"");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * Serialise a list of lineage entries to a JSON array (pretty enough to read, one entry per line).
     */
    public static String toJsonArray(List<MinedRuleLineage> entries) {
        if (entries == null || entries.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < entries.size(); i++) {
            sb.append("  ").append(entries.get(i).toJson());
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }
}
