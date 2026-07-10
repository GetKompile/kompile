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
 * Derives a role binding for each process step, best evidence first.
 *
 * <h3>Resolution order</h3>
 * <ol>
 *   <li><b>OBSERVED</b> — the majority actor tallied from the crawl's actor-incident relations by
 *       {@link ActorResourceObservations} ("bob sent the approval email in 4 of 4 cases"). This is
 *       real per-instance evidence and always wins when present.</li>
 *   <li><b>KB</b> — conjunctive queries against the fact sheet's knowledge base:
 *       {@code hasRole("<activity>", ?Role)}, then {@code performedBy("<activity>", ?Performer)},
 *       then {@code isA(?Entity, <roleType>) & worksOn(?Entity, "<activity>")}. The miner itself
 *       promotes {@code performedBy}/{@code hasRole} facts from observed tallies, so this tier
 *       answers on re-mines even when the actor edges have since degraded.</li>
 *   <li><b>HEURISTIC</b> — keyword inference from the activity name (approv→APPROVER…), the
 *       last resort.</li>
 * </ol>
 *
 * <p>Results populate {@code SuggestedStep.roleBinding} plus {@code roleSource} (one of the
 * {@code SOURCE_*} constants), so the UI can distinguish "we saw who did this" from "the name
 * sounded like approving". Steps with no evidence at all get {@code "UNASSIGNED"} and a null source.
 */
public final class RoleBindingExtractor {

    /** Entity type constants matched against KB adjacency. */
    private static final List<String> ROLE_ENTITY_TYPES = List.of("PERSON", "ROLE", "DEPARTMENT", "TEAM");

    /** {@code roleSource} value: majority actor observed on the activity's own instances. */
    public static final String SOURCE_OBSERVED = "OBSERVED";
    /** {@code roleSource} value: answered by a knowledge-base query. */
    public static final String SOURCE_KB = "KB";
    /** {@code roleSource} value: keyword inference from the activity name. */
    public static final String SOURCE_HEURISTIC = "HEURISTIC";

    /** A resolved role plus where it came from ({@code source} null for UNASSIGNED). */
    public record RoleResolution(String role, String source) {
    }

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
            RoleResolution resolution = deriveRoleForStep(step.getName(), kbGrounding, factSheetId, Map.of());
            roleMap.put(step.getName(), resolution.role());
        }
        return roleMap;
    }

    /**
     * Apply role bindings to steps in-place, setting {@code step.roleBinding} — KB/heuristic tiers
     * only. Kept for callers without an observed-actor tally.
     */
    public static void applyRoleBindings(
            List<SuggestedStep> steps,
            KbGroundingService kbGrounding,
            long factSheetId) {
        applyRoleBindings(steps, kbGrounding, factSheetId, Map.of());
    }

    /**
     * Apply role bindings to steps in-place, setting {@code step.roleBinding} and
     * {@code step.roleSource}, with an observed-actor tally as the strongest tier.
     *
     * @param steps         steps to annotate (modified in-place)
     * @param kbGrounding   the KB grounding service (nullable — observed/heuristic tiers still run)
     * @param factSheetId   the fact sheet to query against
     * @param observedRoles activity name → observed majority actor, from
     *                      {@link ActorResourceObservations#tally}
     */
    public static void applyRoleBindings(
            List<SuggestedStep> steps,
            KbGroundingService kbGrounding,
            long factSheetId,
            Map<String, ActorResourceObservations.ObservedRole> observedRoles) {
        for (SuggestedStep step : steps) {
            RoleResolution resolution = deriveRoleForStep(step.getName(), kbGrounding, factSheetId,
                    observedRoles != null ? observedRoles : Map.of());
            step.setRoleBinding(resolution.role());
            step.setRoleSource(resolution.source());
        }
    }

    /**
     * Derive a single role for a step name.
     *
     * <p>Strategy (in order, first match wins):
     * <ol>
     *   <li>Observed majority actor from the crawl's actor-incident relations</li>
     *   <li>{@code hasRole("<activity>", ?Role)}</li>
     *   <li>{@code performedBy("<activity>", ?Performer)}</li>
     *   <li>{@code isA(?Entity, <roleType>)} AND {@code worksOn(?Entity, "<activity>")}</li>
     *   <li>Fall back to entity-type inference from activity name keywords</li>
     * </ol>
     */
    private static RoleResolution deriveRoleForStep(
            String activityName,
            KbGroundingService kbGrounding,
            long factSheetId,
            Map<String, ActorResourceObservations.ObservedRole> observedRoles) {
        // Strategy 0: the tallied majority actor — direct observation beats any lookup.
        ActorResourceObservations.ObservedRole observed = observedRoles.get(activityName);
        if (observed != null && observed.roleLabel() != null && !observed.roleLabel().isBlank()) {
            return new RoleResolution(observed.roleLabel(), SOURCE_OBSERVED);
        }

        if (kbGrounding == null) {
            return heuristic(activityName);
        }

        // Strategy 1: hasRole(<activity>, ?Role)
        List<ConjunctiveQueryEngine.AtomPattern> q1 = List.of(
                new ConjunctiveQueryEngine.AtomPattern("hasRole",
                        List.of("\"" + activityName + "\"", "?Role"))
        );
        List<QueryBinding> r1 = kbGrounding.query(factSheetId, q1, 5);
        if (!r1.isEmpty()) {
            String role = r1.get(0).get("?Role");
            if (role != null && !role.isBlank()) return new RoleResolution(unquote(role), SOURCE_KB);
        }

        // Strategy 2: performedBy(<activity>, ?Performer)
        List<ConjunctiveQueryEngine.AtomPattern> q2 = List.of(
                new ConjunctiveQueryEngine.AtomPattern("performedBy",
                        List.of("\"" + activityName + "\"", "?Performer"))
        );
        List<QueryBinding> r2 = kbGrounding.query(factSheetId, q2, 5);
        if (!r2.isEmpty()) {
            String performer = r2.get(0).get("?Performer");
            if (performer != null && !performer.isBlank()) return new RoleResolution(unquote(performer), SOURCE_KB);
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
                return new RoleResolution(roleType, SOURCE_KB);
            }
        }

        // Strategy 4: keyword-based fallback from activity name
        return heuristic(activityName);
    }

    /** Keyword tier as a resolution: HEURISTIC when a keyword hit, null source for UNASSIGNED. */
    private static RoleResolution heuristic(String activityName) {
        String role = inferRoleFromName(activityName);
        return new RoleResolution(role, "UNASSIGNED".equals(role) ? null : SOURCE_HEURISTIC);
    }

    /** KB bindings for label-argument atoms come back quoted ({@code "bob"}) — strip for display. */
    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
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
