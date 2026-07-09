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

import ai.kompile.graph.reasoning.confidence.Opinion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * W3C PROV export adapter for {@link ReasoningTrace} — an <em>export-only</em> serializer that
 * maps the Step tree to W3C PROV-DM concepts and emits either PROV-N notation or PROV-JSON.
 *
 * <p>Round-trip is explicitly NOT supported: the adapter produces the PROV wire format from the
 * internal {@link ReasoningTrace}; loading PROV-N or PROV-JSON back to a {@link ReasoningTrace}
 * is out of scope.</p>
 *
 * <h2>PROV Mapping</h2>
 * <table>
 *   <caption>Step kind → PROV concepts</caption>
 *   <tr><th>Step kind</th><th>PROV Entity</th><th>PROV Activity</th><th>Relations</th></tr>
 *   <tr>
 *     <td>FACT (leaf)</td>
 *     <td>{@code prov:Entity} — one entity for the conclusion value (deduped)</td>
 *     <td>none</td>
 *     <td>none (leaf node)</td>
 *   </tr>
 *   <tr>
 *     <td>ASSUMPTION (leaf)</td>
 *     <td>{@code prov:Entity} annotated {@code kompile:assumed "true"}</td>
 *     <td>none</td>
 *     <td>none</td>
 *   </tr>
 *   <tr>
 *     <td>RULE / INFERENCE / FUSION / QUERY</td>
 *     <td>{@code prov:Entity} for the conclusion</td>
 *     <td>{@code prov:Activity} (the operation)</td>
 *     <td>
 *       conclusion {@code prov:wasGeneratedBy} activity;<br>
 *       activity {@code prov:used} each premise entity;<br>
 *       conclusion {@code prov:wasDerivedFrom} each premise entity
 *     </td>
 *   </tr>
 *   <tr>
 *     <td>REBUTTAL</td>
 *     <td>Same as RULE/INFERENCE, plus {@code kompile:rebuts} the parent conclusion entity</td>
 *     <td>{@code prov:Activity}</td>
 *     <td>As RULE, plus {@code kompile:rebuts} annotation on the conclusion entity</td>
 *   </tr>
 *   <tr>
 *     <td>REVISION</td>
 *     <td>Same as RULE/INFERENCE, plus {@code kompile:revises} the parent conclusion entity</td>
 *     <td>{@code prov:Activity}</td>
 *     <td>As RULE, plus {@code kompile:revises} annotation on the conclusion entity</td>
 *   </tr>
 * </table>
 *
 * <h2>Attribute mapping</h2>
 * <ul>
 *   <li>{@code source} → {@code prov:wasAttributedTo} a {@code prov:Agent} (one agent per
 *       distinct source string; agent ids are {@code ag_1}, {@code ag_2}, ...)</li>
 *   <li>{@code confidence} → {@code kompile:confidence} literal on the entity</li>
 *   <li>{@link Opinion} (when present) → {@code kompile:belief}, {@code kompile:disbelief},
 *       {@code kompile:uncertainty}, {@code kompile:baseRate} literals</li>
 *   <li>Step {@code meta} entries → {@code kompile:meta_<sanitizedKey>} literals
 *       (keys sanitized via {@link ProvIds#sanitizeMetaKey(String)})</li>
 *   <li>Conclusion text → {@code rdfs:label} on the entity</li>
 * </ul>
 *
 * <h2>Entity deduplication (DAG reconstruction)</h2>
 * <p>A {@link ReasoningTrace} is a <em>tree</em>: the same logical fact can appear as a premise
 * under multiple branches.  PROV models provenance as a <em>DAG</em> where one entity can be
 * {@code used} by multiple activities.  This serializer deduplicates entities by
 * {@code (conclusion, kind)} content key: the first positional id wins; any later occurrence of
 * the same key reuses that id.  Activity ids remain positional (they represent the computation
 * event, not the value).</p>
 *
 * <h2>Determinism</h2>
 * <p>ID assignment is purely position-based (pre-order traversal); attributes are emitted in
 * sorted key order.  The same {@link ReasoningTrace} always produces byte-identical output.</p>
 *
 * <h2>No external dependencies</h2>
 * <p>Both formats are hand-rolled with no Jena, no Jackson-databind, and no other new compile
 * dependencies.  JSON escaping follows the same approach as {@link ReasoningTrace#toJson()}.</p>
 */
public final class ProvSerializer {

    private ProvSerializer() { }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Serialize {@code trace} to PROV-N notation (W3C PROV-N, <a
     * href="https://www.w3.org/TR/prov-n/">https://www.w3.org/TR/prov-n/</a>).
     *
     * <p>Output starts with {@code document}, declares the {@code kompile} and {@code rdfs}
     * prefixes, emits entities / activities / relations in declaration order, then closes with
     * {@code endDocument}.</p>
     *
     * @param trace the trace to export; must not be null
     * @return a PROV-N document as a string
     */
    public static String toProvN(ReasoningTrace trace) {
        State state = new State();
        state.collectStep(trace.conclusion(), ProvIds.rootPath(), null);

        StringBuilder sb = new StringBuilder(512);
        sb.append("document\n");
        sb.append("  prefix kompile <").append(ProvIds.NS_KOMPILE).append(">\n");
        sb.append("  prefix prov <").append(ProvIds.NS_PROV).append(">\n");
        sb.append("  prefix rdfs <").append(ProvIds.NS_RDFS).append(">\n");
        sb.append('\n');

        // Agents
        for (Map.Entry<String, String> e : state.agentIdBySource.entrySet()) {
            String agId = e.getValue();
            sb.append("  agent(").append(agId)
              .append(", [prov:type='prov:SoftwareAgent'])\n");
        }
        if (!state.agentIdBySource.isEmpty()) sb.append('\n');

        // Entities
        for (EntityRecord er : state.entities.values()) {
            sb.append("  entity(").append(er.id).append(", [");
            sb.append("rdfs:label=");
            appendProvNString(sb, er.conclusion);
            sb.append(", kompile:confidence=\"").append(formatDouble(er.confidence)).append('"');
            if (er.assumed) sb.append(", kompile:assumed=\"true\"");
            if (er.opinion != null) {
                sb.append(", kompile:belief=\"").append(formatDouble(er.opinion.belief())).append('"');
                sb.append(", kompile:disbelief=\"").append(formatDouble(er.opinion.disbelief())).append('"');
                sb.append(", kompile:uncertainty=\"").append(formatDouble(er.opinion.uncertainty())).append('"');
                sb.append(", kompile:baseRate=\"").append(formatDouble(er.opinion.baseRate())).append('"');
            }
            if (er.rebutsEntityId != null) {
                sb.append(", kompile:rebuts=").append(er.rebutsEntityId);
            }
            if (er.revisesEntityId != null) {
                sb.append(", kompile:revises=").append(er.revisesEntityId);
            }
            for (Map.Entry<String, String> me : new TreeMap<>(er.metaAttributes).entrySet()) {
                sb.append(", kompile:meta_").append(ProvIds.sanitizeMetaKey(me.getKey()))
                  .append("=");
                appendProvNString(sb, me.getValue());
            }
            sb.append("])\n");
        }
        if (!state.entities.isEmpty()) sb.append('\n');

        // Activities
        for (ActivityRecord ar : state.activities) {
            sb.append("  activity(").append(ar.id).append(", -, -)\n");
        }
        if (!state.activities.isEmpty()) sb.append('\n');

        // wasGeneratedBy
        for (WasGeneratedBy wgb : state.wasGeneratedBy) {
            sb.append("  wasGeneratedBy(").append(wgb.entityId).append(", ")
              .append(wgb.activityId).append(", -)\n");
        }
        // used
        for (Used used : state.used) {
            sb.append("  used(").append(used.activityId).append(", ")
              .append(used.entityId).append(", -)\n");
        }
        // wasDerivedFrom
        for (WasDerivedFrom wdf : state.wasDerivedFrom) {
            sb.append("  wasDerivedFrom(").append(wdf.derivedEntityId).append(", ")
              .append(wdf.sourceEntityId).append(")\n");
        }
        // wasAttributedTo
        for (WasAttributedTo wat : state.wasAttributedTo) {
            sb.append("  wasAttributedTo(").append(wat.entityId).append(", ")
              .append(wat.agentId).append(")\n");
        }

        sb.append("\nendDocument\n");
        return sb.toString();
    }

    /**
     * Serialize {@code trace} to PROV-JSON (W3C PROV-JSON, <a
     * href="https://www.w3.org/Submission/2013/SUBM-prov-json-20130424/">W3C Submission 2013</a>).
     *
     * <p>The output is a JSON object with seven top-level keys:
     * {@code entity}, {@code activity}, {@code agent}, {@code wasGeneratedBy},
     * {@code used}, {@code wasDerivedFrom}, {@code wasAttributedTo}.
     * Each value is a map from id to attributes.  The prefix map is emitted
     * as a special {@code prefix} key.</p>
     *
     * @param trace the trace to export; must not be null
     * @return a PROV-JSON document as a string
     */
    public static String toProvJson(ReasoningTrace trace) {
        State state = new State();
        state.collectStep(trace.conclusion(), ProvIds.rootPath(), null);

        StringBuilder sb = new StringBuilder(512);
        sb.append('{');

        // prefix
        sb.append("\"prefix\":{");
        sb.append("\"kompile\":"); appendJsonString(sb, ProvIds.NS_KOMPILE);
        sb.append(",\"prov\":"); appendJsonString(sb, ProvIds.NS_PROV);
        sb.append(",\"rdfs\":"); appendJsonString(sb, ProvIds.NS_RDFS);
        sb.append('}');

        // entity
        sb.append(",\"entity\":{");
        boolean firstEntity = true;
        for (EntityRecord er : state.entities.values()) {
            if (!firstEntity) sb.append(',');
            firstEntity = false;
            appendJsonString(sb, er.id);
            sb.append(":{");
            appendJsonStringEntry(sb, "rdfs:label", er.conclusion, true);
            sb.append(",\"kompile:confidence\":"); appendJsonString(sb, formatDouble(er.confidence));
            if (er.assumed) {
                sb.append(",\"kompile:assumed\":"); appendJsonString(sb, "true");
            }
            if (er.opinion != null) {
                sb.append(",\"kompile:belief\":"); appendJsonString(sb, formatDouble(er.opinion.belief()));
                sb.append(",\"kompile:disbelief\":"); appendJsonString(sb, formatDouble(er.opinion.disbelief()));
                sb.append(",\"kompile:uncertainty\":"); appendJsonString(sb, formatDouble(er.opinion.uncertainty()));
                sb.append(",\"kompile:baseRate\":"); appendJsonString(sb, formatDouble(er.opinion.baseRate()));
            }
            if (er.rebutsEntityId != null) {
                sb.append(",\"kompile:rebuts\":"); appendJsonString(sb, er.rebutsEntityId);
            }
            if (er.revisesEntityId != null) {
                sb.append(",\"kompile:revises\":"); appendJsonString(sb, er.revisesEntityId);
            }
            for (Map.Entry<String, String> me : new TreeMap<>(er.metaAttributes).entrySet()) {
                String metaKey = "kompile:meta_" + ProvIds.sanitizeMetaKey(me.getKey());
                sb.append(',');
                appendJsonString(sb, metaKey);
                sb.append(':');
                appendJsonString(sb, me.getValue());
            }
            sb.append('}');
        }
        sb.append('}');

        // activity
        sb.append(",\"activity\":{");
        boolean firstActivity = true;
        for (ActivityRecord ar : state.activities) {
            if (!firstActivity) sb.append(',');
            firstActivity = false;
            appendJsonString(sb, ar.id);
            sb.append(":{\"prov:type\":\"prov:Activity\"");
            sb.append(",\"kompile:operation\":");
            appendJsonString(sb, ar.operation);
            sb.append('}');
        }
        sb.append('}');

        // agent
        sb.append(",\"agent\":{");
        boolean firstAgent = true;
        for (Map.Entry<String, String> e : state.agentIdBySource.entrySet()) {
            if (!firstAgent) sb.append(',');
            firstAgent = false;
            appendJsonString(sb, e.getValue());
            sb.append(":{\"prov:type\":\"prov:SoftwareAgent\",\"rdfs:label\":");
            appendJsonString(sb, e.getKey());
            sb.append('}');
        }
        sb.append('}');

        // wasGeneratedBy
        sb.append(",\"wasGeneratedBy\":{");
        boolean firstWgb = true;
        int wgbIdx = 0;
        for (WasGeneratedBy wgb : state.wasGeneratedBy) {
            if (!firstWgb) sb.append(',');
            firstWgb = false;
            appendJsonString(sb, "_:wgb" + wgbIdx++);
            sb.append(":{\"prov:entity\":{\"$\":"); appendJsonString(sb, wgb.entityId); sb.append('}');
            sb.append(",\"prov:activity\":{\"$\":"); appendJsonString(sb, wgb.activityId); sb.append('}');
            sb.append('}');
        }
        sb.append('}');

        // used
        sb.append(",\"used\":{");
        boolean firstUsed = true;
        int usedIdx = 0;
        for (Used used : state.used) {
            if (!firstUsed) sb.append(',');
            firstUsed = false;
            appendJsonString(sb, "_:used" + usedIdx++);
            sb.append(":{\"prov:activity\":{\"$\":"); appendJsonString(sb, used.activityId); sb.append('}');
            sb.append(",\"prov:entity\":{\"$\":"); appendJsonString(sb, used.entityId); sb.append('}');
            sb.append('}');
        }
        sb.append('}');

        // wasDerivedFrom
        sb.append(",\"wasDerivedFrom\":{");
        boolean firstWdf = true;
        int wdfIdx = 0;
        for (WasDerivedFrom wdf : state.wasDerivedFrom) {
            if (!firstWdf) sb.append(',');
            firstWdf = false;
            appendJsonString(sb, "_:wdf" + wdfIdx++);
            sb.append(":{\"prov:generatedEntity\":{\"$\":"); appendJsonString(sb, wdf.derivedEntityId); sb.append('}');
            sb.append(",\"prov:usedEntity\":{\"$\":"); appendJsonString(sb, wdf.sourceEntityId); sb.append('}');
            sb.append('}');
        }
        sb.append('}');

        // wasAttributedTo
        sb.append(",\"wasAttributedTo\":{");
        boolean firstWat = true;
        int watIdx = 0;
        for (WasAttributedTo wat : state.wasAttributedTo) {
            if (!firstWat) sb.append(',');
            firstWat = false;
            appendJsonString(sb, "_:wat" + watIdx++);
            sb.append(":{\"prov:entity\":{\"$\":"); appendJsonString(sb, wat.entityId); sb.append('}');
            sb.append(",\"prov:agent\":{\"$\":"); appendJsonString(sb, wat.agentId); sb.append('}');
            sb.append('}');
        }
        sb.append('}');

        sb.append('}');
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Internal records
    // ══════════════════════════════════════════════════════════════════════════

    private static final class EntityRecord {
        final String id;
        final String conclusion;
        final double confidence;
        final boolean assumed;
        final Opinion opinion;
        final Map<String, String> metaAttributes;
        // Set by the caller when needed (REBUTTAL / REVISION)
        String rebutsEntityId;
        String revisesEntityId;

        EntityRecord(String id, String conclusion, double confidence, boolean assumed,
                     Opinion opinion, Map<String, String> metaAttributes) {
            this.id = id;
            this.conclusion = conclusion;
            this.confidence = confidence;
            this.assumed = assumed;
            this.opinion = opinion;
            this.metaAttributes = metaAttributes == null ? Map.of() : metaAttributes;
        }
    }

    private record ActivityRecord(String id, String operation) { }
    private record WasGeneratedBy(String entityId, String activityId) { }
    private record Used(String activityId, String entityId) { }
    private record WasDerivedFrom(String derivedEntityId, String sourceEntityId) { }
    private record WasAttributedTo(String entityId, String agentId) { }

    // ══════════════════════════════════════════════════════════════════════════
    // Traversal state
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Holds all accumulated PROV records for one traversal.  The traversal is a single
     * pre-order DFS over the step tree; entities are stored in a {@link LinkedHashMap}
     * keyed by content key so duplicates reuse the first id; activities are positional.
     */
    private static final class State {

        /**
         * Content key → entity record.  Insertion order = pre-order encounter order.
         * Also serves as the "seen" set: {@code entities.containsKey(key)} tells us whether
         * a step has already been registered.
         */
        final Map<String, EntityRecord> entities = new LinkedHashMap<>();

        /** Positional activities in encounter order. */
        final List<ActivityRecord> activities = new ArrayList<>();

        final List<WasGeneratedBy> wasGeneratedBy = new ArrayList<>();
        final List<Used> used = new ArrayList<>();
        final List<WasDerivedFrom> wasDerivedFrom = new ArrayList<>();
        final List<WasAttributedTo> wasAttributedTo = new ArrayList<>();

        /** Source string → agent id (in encounter order). */
        final Map<String, String> agentIdBySource = new LinkedHashMap<>();

        int agentCounter = 0;

        /**
         * Traverse the step tree rooted at {@code step}.
         *
         * @param step            the current step
         * @param path            position path for this step
         * @param parentEntityId  the entity id of the parent step, or {@code null} for the root;
         *                        used to wire REBUTTAL/REVISION annotations on child steps
         * @return the entity id assigned to (or looked up for) this step
         */
        String collectStep(ReasoningTrace.Step step, String path, String parentEntityId) {
            String contentKey = contentKey(step);

            // --- Entity deduplication (DAG reconstruction) ---
            if (entities.containsKey(contentKey)) {
                // Reuse the previously registered entity id. The calling activity's
                // used/wasDerivedFrom edges are wired by the CALLER after we return.
                return entities.get(contentKey).id;
            }

            String entityId = ProvIds.entityId(path);
            boolean assumed = step.kind() == ReasoningTrace.StepKind.ASSUMPTION;
            EntityRecord er = new EntityRecord(entityId, step.conclusion(), step.confidence(),
                    assumed, step.opinion(), step.meta());
            entities.put(contentKey, er);

            // REBUTTAL / REVISION: annotate this entity
            if (step.kind() == ReasoningTrace.StepKind.REBUTTAL && parentEntityId != null) {
                er.rebutsEntityId = parentEntityId;
            } else if (step.kind() == ReasoningTrace.StepKind.REVISION && parentEntityId != null) {
                er.revisesEntityId = parentEntityId;
            }

            // Source → agent attribution
            if (step.source() != null && !step.source().isBlank()) {
                String agId = agentIdBySource.computeIfAbsent(step.source(),
                        s -> ProvIds.agentId(++agentCounter));
                wasAttributedTo.add(new WasAttributedTo(entityId, agId));
            }

            // Leaf steps (FACT, ASSUMPTION) have no activity
            if (step.isLeaf()) {
                return entityId;
            }

            // Derived step: create an Activity and wire relations
            String actId = ProvIds.activityId(path);
            activities.add(new ActivityRecord(actId, step.operation()));
            wasGeneratedBy.add(new WasGeneratedBy(entityId, actId));

            List<ReasoningTrace.Step> premises = step.premises();
            for (int i = 0; i < premises.size(); i++) {
                ReasoningTrace.Step premise = premises.get(i);

                // Recurse first — may register a new entity or return the existing deduped id
                String premiseEntityId = collectStep(premise, ProvIds.childPath(path, i), entityId);

                // Wire THIS activity to the premise entity (deduped id).
                // If the premise was a duplicate, collectStep returned early with the first id;
                // the used/wasDerivedFrom edges still point to that canonical entity.
                used.add(new Used(actId, premiseEntityId));
                wasDerivedFrom.add(new WasDerivedFrom(entityId, premiseEntityId));
            }

            return entityId;
        }

        /** Content key: (conclusion || '\0' || kind.name()) — stable dedup key. */
        private static String contentKey(ReasoningTrace.Step step) {
            return step.conclusion() + '\0' + step.kind().name();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // String helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Append {@code s} as a PROV-N string literal: single-quoted and with internal single quotes
     * doubled, backslashes escaped, and newlines replaced with {@code \n}.
     */
    private static void appendProvNString(StringBuilder sb, String s) {
        if (s == null) s = "";
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\'' -> sb.append("''");        // PROV-N escapes single-quote by doubling
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        sb.append('\'');
    }

    /**
     * Append {@code s} as a JSON string literal.  Uses the same escaping as
     * {@link ReasoningTrace#toJson()}.
     */
    static void appendJsonString(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Append a JSON key:value entry where the value is a JSON string.  Optionally first. */
    private static void appendJsonStringEntry(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append(',');
        appendJsonString(sb, key);
        sb.append(':');
        appendJsonString(sb, value);
    }

    /** Format a double for embedding in PROV literals — 6 decimal places, locale-safe. */
    private static String formatDouble(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
