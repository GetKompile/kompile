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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Bridges a multi-signal {@link ClaimDossier} into QBAF gradual-argumentation adjudication.
 *
 * <p>The dossier's log-odds fusion answers "how much does the evidence add up?"; this bridge
 * answers the <em>dialectical</em> question instead — each supporting item becomes a PRO
 * supporter and each refuting item a CON attacker of the claim, scored by DF-QuAD
 * ({@link DfQuadSemantics}). The two views are complementary: the fused score is a calibrated
 * magnitude, the adjudicated verdict reflects the pro/con balance and yields a
 * {@code ReasoningTrace} whose refuting items are first-class {@code REBUTTAL} steps.</p>
 */
public final class DossierAdjudicator {

    private final ClaimAdjudicator adjudicator;

    public DossierAdjudicator() {
        this(new ClaimAdjudicator());
    }

    public DossierAdjudicator(ClaimAdjudicator adjudicator) {
        this.adjudicator = Objects.requireNonNull(adjudicator, "adjudicator");
    }

    /** Adjudicate a dossier with a neutral prior of 0.5. */
    public AdjudicatedVerdict adjudicate(ClaimDossier dossier) {
        return adjudicate(dossier, 0.5);
    }

    /** Adjudicate a dossier: supporting items → PRO supporters, refuting items → CON attackers. */
    public AdjudicatedVerdict adjudicate(ClaimDossier dossier, double prior) {
        Objects.requireNonNull(dossier, "dossier");
        List<EvidenceItem> items = new ArrayList<>(dossier.supporting().size() + dossier.refuting().size());
        for (DossierItem it : dossier.supporting()) {
            items.add(EvidenceItem.pro(it.description(), clamp01(it.probability()),
                    kindLabel(it.kind()), it.provenance()));
        }
        for (DossierItem it : dossier.refuting()) {
            items.add(EvidenceItem.con(it.description(), clamp01(it.probability()),
                    kindLabel(it.kind()), it.provenance()));
        }
        return adjudicator.adjudicate(dossier.claimAtom(), prior, items);
    }

    private static String kindLabel(DossierItem.Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static double clamp01(double x) {
        return Double.isNaN(x) ? 0.0 : Math.max(0.0, Math.min(1.0, x));
    }
}
