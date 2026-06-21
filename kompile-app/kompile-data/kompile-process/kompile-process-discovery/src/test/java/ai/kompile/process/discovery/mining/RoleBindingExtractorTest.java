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

package ai.kompile.process.discovery.mining;

import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.QueryBinding;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;
import ai.kompile.process.discovery.mining.extract.RoleBindingExtractor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies RoleBindingExtractor: keyword fallback, KB strategy 1 (hasRole),
 * KB strategy 2 (performedBy), and in-place applyRoleBindings.
 */
class RoleBindingExtractorTest {

    private static SuggestedStep step(String name) {
        return SuggestedStep.builder().name(name).stepType("AUTO").build();
    }

    // ── Keyword fallback (null KB) ────────────────────────────────────────────────

    @Test
    void keywordFallback_approve_returnsAPPROVER() {
        Map<String, String> roles = RoleBindingExtractor.extractRoles(
                List.of(step("Approve Invoice")), null, 0L);
        assertEquals("APPROVER", roles.get("Approve Invoice"));
    }

    @Test
    void keywordFallback_review_returnsREVIEWER() {
        assertEquals("REVIEWER",
                RoleBindingExtractor.inferRoleFromName("Review Order"));
    }

    @Test
    void keywordFallback_submit_returnsINITIATOR() {
        assertEquals("INITIATOR",
                RoleBindingExtractor.inferRoleFromName("Submit Request"));
    }

    @Test
    void keywordFallback_unknown_returnsUNASSIGNED() {
        assertEquals("UNASSIGNED",
                RoleBindingExtractor.inferRoleFromName("Foo Bar XYZ"));
    }

    @Test
    void keywordFallback_null_returnsUNASSIGNED() {
        assertEquals("UNASSIGNED", RoleBindingExtractor.inferRoleFromName(null));
    }

    // ── KB strategy 1: hasRole ────────────────────────────────────────────────────

    @Test
    void kbStrategy1_hasRole_returnsRoleFromBinding() {
        KbGroundingService kb = new KbGroundingService() {
            @Override
            public List<QueryBinding> query(long fsId,
                                            List<ConjunctiveQueryEngine.AtomPattern> conjuncts,
                                            int maxResults) {
                // Match strategy 1: hasRole(<activity>, ?Role)
                if (!conjuncts.isEmpty() && "hasRole".equals(conjuncts.get(0).predicate())) {
                    Map<String, String> binding = Map.of("Role", "FINANCE_MANAGER");
                    return List.of(new QueryBinding(binding, 0.9));
                }
                return List.of();
            }
            @Override
            public VerifyResult verify(long fsId, String atomKey) {
                return VerifyResult.unknown();
            }
        };
        Map<String, String> roles = RoleBindingExtractor.extractRoles(
                List.of(step("Approve Budget")), kb, 1L);
        assertEquals("FINANCE_MANAGER", roles.get("Approve Budget"));
    }

    // ── KB strategy 2: performedBy ────────────────────────────────────────────────

    @Test
    void kbStrategy2_performedBy_returnsPerformerWhenHasRoleMisses() {
        KbGroundingService kb = new KbGroundingService() {
            @Override
            public List<QueryBinding> query(long fsId,
                                            List<ConjunctiveQueryEngine.AtomPattern> conjuncts,
                                            int maxResults) {
                if (!conjuncts.isEmpty() && "hasRole".equals(conjuncts.get(0).predicate())) {
                    return List.of(); // strategy 1 misses
                }
                if (!conjuncts.isEmpty() && "performedBy".equals(conjuncts.get(0).predicate())) {
                    return List.of(new QueryBinding(Map.of("Performer", "CLERK"), 0.7));
                }
                return List.of();
            }
            @Override
            public VerifyResult verify(long fsId, String atomKey) {
                return VerifyResult.unknown();
            }
        };
        Map<String, String> roles = RoleBindingExtractor.extractRoles(
                List.of(step("Create PO")), kb, 2L);
        assertEquals("CLERK", roles.get("Create PO"));
    }

    // ── applyRoleBindings (in-place) ────────────────────────────────────────────

    @Test
    void applyRoleBindings_setsRoleOnSteps() {
        List<SuggestedStep> steps = new ArrayList<>();
        steps.add(step("Submit Request"));
        steps.add(step("Approve Request"));
        steps.add(step("Random Step XYZ"));

        RoleBindingExtractor.applyRoleBindings(steps, null, 0L);

        assertEquals("INITIATOR", steps.get(0).getRoleBinding());
        assertEquals("APPROVER",  steps.get(1).getRoleBinding());
        assertEquals("UNASSIGNED", steps.get(2).getRoleBinding());
    }

    @Test
    void extractRoles_noSteps_returnsEmptyMap() {
        Map<String, String> result = RoleBindingExtractor.extractRoles(List.of(), null, 0L);
        assertNotNull(result);
        assertEquals(0, result.size());
    }
}
