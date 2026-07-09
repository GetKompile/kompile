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
package ai.kompile.graph.reasoning.argument;

import ai.kompile.graph.reasoning.claims.ClaimDossier;
import ai.kompile.graph.reasoning.claims.DossierItem;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link DossierAdjudicator}: ClaimDossier → QBAF adjudication bridge. */
class DossierAdjudicatorTest {

    private static ClaimDossier dossier(List<DossierItem> supporting, List<DossierItem> refuting) {
        return new ClaimDossier("worksAt(alice, acme)", "alice", "worksAt", "acme",
                supporting, refuting, 0.5);
    }

    @Test
    @DisplayName("support-only dossier adjudicates SUPPORTED with strength above prior")
    void supportOnly() {
        ClaimDossier d = dossier(List.of(
                new DossierItem(DossierItem.Kind.DIRECT_EDGE, "edge worksAt(alice, acme)", 0.9,
                        List.of("rel-1")),
                new DossierItem(DossierItem.Kind.MINED_RULE, "rule employment-transitivity fired", 0.7,
                        List.of("rule-1"))), List.of());
        AdjudicatedVerdict v = new DossierAdjudicator().adjudicate(d);
        assertEquals(AdjudicatedVerdict.Status.SUPPORTED, v.status());
        assertTrue(v.strength() > 0.5, "net support must raise strength above the prior");
    }

    @Test
    @DisplayName("refuting items become REBUTTAL steps and pull strength down")
    void refutingItemsRebut() {
        ClaimDossier balanced = dossier(
                List.of(new DossierItem(DossierItem.Kind.PATH, "2-hop path via acme-hq", 0.6,
                        List.of("path"))),
                List.of(new DossierItem(DossierItem.Kind.FUNCTIONAL_CONFLICT,
                        "worksAt(alice, other-corp) @0.9", 0.9, List.of("competing"))));
        DossierAdjudicator bridge = new DossierAdjudicator();
        AdjudicatedVerdict v = bridge.adjudicate(balanced);

        ClaimDossier supportOnly = dossier(
                List.of(new DossierItem(DossierItem.Kind.PATH, "2-hop path via acme-hq", 0.6,
                        List.of("path"))), List.of());
        AdjudicatedVerdict vSupport = bridge.adjudicate(supportOnly);
        assertTrue(v.strength() < vSupport.strength(),
                "an attacker must strictly lower the claim strength vs the attack-free dossier");

        boolean hasRebuttal = v.trace().steps().stream()
                .anyMatch(s -> s.kind() == ReasoningTrace.StepKind.REBUTTAL);
        assertTrue(hasRebuttal, "refuting dossier items must surface as REBUTTAL trace steps");
    }

    @Test
    @DisplayName("empty dossier stays at the prior (UNKNOWN)")
    void emptyDossier() {
        AdjudicatedVerdict v = new DossierAdjudicator().adjudicate(dossier(List.of(), List.of()));
        assertEquals(AdjudicatedVerdict.Status.UNKNOWN, v.status());
        assertEquals(0.5, v.strength(), 1e-9);
    }

    @Test
    @DisplayName("kind labels are kebab-cased into evidence kinds on the trace")
    void kindLabels() {
        ClaimDossier d = dossier(List.of(), List.of(
                new DossierItem(DossierItem.Kind.NEGATED_ATOM, "~worksAt(alice, acme)", 0.8,
                        List.of("neg"))));
        AdjudicatedVerdict v = new DossierAdjudicator().adjudicate(d);
        boolean kindInMeta = v.trace().steps().stream()
                .anyMatch(s -> "negated-atom".equals(s.meta().get("kind")));
        assertTrue(kindInMeta, "DossierItem.Kind should surface kebab-cased in step meta");
    }
}
