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

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.QueryBinding;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives a role binding for each process step from the knowledge graph.
 *
 * <h3>Approach</h3>
 * <p>For each step name, we query the KB for PERSON, ROLE, or DEPARTMENT entities
 * adjacent to the activity's atom. The query pattern is:
 * <pre>
 *   hasRole(?Activity, ?Role)  — or worksOn(?Person, ?Activity)
 * </pre>
 * If neither yields a result, we fall back to querying by {@code isA(?Entity, PERSON)} /
 * {@code isA(?Entity, ROLE)} patterns that have any relationship to the step name.
 *
 * <p>The result is a {@code Map<stepName, roleLabel>} used by {@link ProcessSuggestion.SuggestedStep}
 * to populate {@code roleBinding}. Steps with no KB match return {@code "UNASSIGNED"}.
 */
public final class RoleBindingExtractor {

    /** Entity type constants matched against KB adjacency. */
    private static final List<String> ROLE_ENTITY_TYPES = List.of("PERSON", "ROLE", "DEPARTMENT", "TEAM");

    private RoleBindingExtractor() {
    }

    /**
     * Derive role bindings for a list of steps from the KB.
     *
     * @param steps       the steps to bind roles for
     * @param kbGrounding the KB grounding service (used for conjunctive queries)
     * @param factSheetId the fact sheet to query against
     * @return map from step name to role label (never null; unmatched steps get "UNASSIGNED")
     */
    public static Map<String, String> extractRoles(
            List<SuggestedStep> steps,
            KbGroundingService kbGrounding,
            long factSheetId) {
        Map<String, String> roleMap = new LinkedHashMap<>();
        for (SuggestedStep step : steps) {
            String role = deriveRoleForStep(step.getName(), kbGrounding, factSheetId);
            roleMap.put(step.getName(), role);
        }
        return roleMap;
    }

    /**
     * Apply role bindings to steps in-place, setting {@code step.roleBinding}.
     *
     * @param steps       steps to annotate (modified in-place)
     * @param kbGrounding the KB grounding service
     * @param factSheetId the fact sheet to query against
     */
    public static void applyRoleBindings(
            List<SuggestedStep> steps,
            KbGroundingService kbGrounding,
            long factSheetId) {
        Map<String, String> roleMap = extractRoles(steps, kbGrounding, factSheetId);
        for (SuggestedStep step : steps) {
            step.setRoleBinding(roleMap.getOrDefault(step.getName(), "UNASSIGNED"));
        }
    }

    /**
     * Derive a single role for a step name.
     *
     * <p>Query strategy (in order, first match wins):
     * <ol>
     *   <li>{@code hasRole("<activity>", ?Role)}</li>
     *   <li>{@code performedBy("<activity>", ?Performer)}</li>
     *   <li>{@code isA(?Entity, <roleType>)} AND {@code worksOn(?Entity, "<activity>")}</li>
     *   <li>Fall back to entity-type inference from activity name keywords</li>
     * </ol>
     */
    private static String deriveRoleForStep(
            String activityName,
            KbGroundingService kbGrounding,
            long factSheetId) {
        if (kbGrounding == null) {
            return inferRoleFromName(activityName);
        }

        // Strategy 1: hasRole(<activity>, ?Role)
        List<ConjunctiveQueryEngine.AtomPattern> q1 = List.of(
                new ConjunctiveQueryEngine.AtomPattern("hasRole",
                        List.of("\"" + activityName + "\"", "?Role"))
        );
        List<QueryBinding> r1 = kbGrounding.query(factSheetId, q1, 5);
        if (!r1.isEmpty()) {
            String role = r1.get(0).get("?Role");
            if (role != null && !role.isBlank()) return role;
        }

        // Strategy 2: performedBy(<activity>, ?Performer)
        List<ConjunctiveQueryEngine.AtomPattern> q2 = List.of(
                new ConjunctiveQueryEngine.AtomPattern("performedBy",
                        List.of("\"" + activityName + "\"", "?Performer"))
        );
        List<QueryBinding> r2 = kbGrounding.query(factSheetId, q2, 5);
        if (!r2.isEmpty()) {
            String performer = r2.get(0).get("?Performer");
            if (performer != null && !performer.isBlank()) return performer;
        }

        // Strategy 3: for each role entity type, try isA(?Entity, <type>) AND worksOn(?Entity, <activity>)
        for (String roleType : ROLE_ENTITY_TYPES) {
            List<ConjunctiveQueryEngine.AtomPattern> q3 = List.of(
                    new ConjunctiveQueryEngine.AtomPattern("isA", List.of("?Entity", roleType)),
                    new ConjunctiveQueryEngine.AtomPattern("worksOn",
                            List.of("?Entity", "\"" + activityName + "\""))
            );
            List<QueryBinding> r3 = kbGrounding.query(factSheetId, q3, 5);
            if (!r3.isEmpty()) {
                // Return the entity type as the role binding label
                return roleType;
            }
        }

        // Strategy 4: keyword-based fallback from activity name
        return inferRoleFromName(activityName);
    }

    /**
     * Keyword-based role inference when the KB has no match.
     * Returns a role label based on activity name patterns.
     */
    public static String inferRoleFromName(String activityName) {
        if (activityName == null) return "UNASSIGNED";
        String lower = activityName.toLowerCase();
        if (lower.contains("approv") || lower.contains("sign") || lower.contains("authoriz")) {
            return "APPROVER";
        }
        if (lower.contains("review") || lower.contains("check") || lower.contains("inspect")) {
            return "REVIEWER";
        }
        if (lower.contains("submit") || lower.contains("creat") || lower.contains("initiat")) {
            return "INITIATOR";
        }
        if (lower.contains("notify") || lower.contains("email") || lower.contains("send")) {
            return "NOTIFIER";
        }
        if (lower.contains("process") || lower.contains("execut") || lower.contains("run")) {
            return "EXECUTOR";
        }
        return "UNASSIGNED";
    }
}
