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

package ai.kompile.process.discovery.mining.causal;

import ai.kompile.event.attribution.domain.CausalEdgeType;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph.Arc;
import ai.kompile.process.discovery.mining.log.EventLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Couples a discovered process to the causal/probabilistic layer, with no LLM: it scores every
 * directly-follows relation in the log ({@link DependencyMeasures}), classifies it into the same
 * {@link CausalEdgeType} the attribution engine uses, and emits weighted PSL rules — in the engine's own
 * {@code State}/{@code Link} vocabulary — so the discovered control-flow can drive HL-MRF inference.
 *
 * <p>Directed, statistically-significant relations become {@code CAUSES}/{@code TRIGGERS}; symmetric ones
 * become {@code CORRELATES_WITH}. This replaces the keyword guessing in
 * {@code CausalTraversal.classifyEdge} with evidence: a χ² test and a dependency measure.
 */
public final class ProcessCausalAnalyzer {

    /** The causal view of a discovered process: per-arc dependencies plus the PSL rules they imply. */
    public record ProcessCausalModel(List<CausalDependency> dependencies, List<String> pslRules) {
    }

    private ProcessCausalAnalyzer() {
    }

    public static ProcessCausalModel analyze(EventLog log) {
        return analyze(DfgBuilder.build(log));
    }

    public static ProcessCausalModel analyze(DirectlyFollowsGraph dfg) {
        List<CausalDependency> dependencies = new ArrayList<>();
        for (Arc arc : dfg.arcs().keySet()) {
            String a = arc.from();
            String b = arc.to();
            long forward = dfg.follows(a, b);
            long reverse = dfg.follows(b, a);
            double dependency = DependencyMeasures.dependency(dfg, a, b);
            double chiSquare = DependencyMeasures.chiSquare(dfg, a, b);
            boolean significant = chiSquare > DependencyMeasures.CHI2_CRITICAL_0_05;
            CausalEdgeType type = classify(dependency, significant, forward, reverse);
            dependencies.add(new CausalDependency(a, b, forward, reverse, dependency, chiSquare, significant, type));
        }
        dependencies.sort((x, y) -> Double.compare(y.dependency(), x.dependency()));
        return new ProcessCausalModel(dependencies, generatePslRules(dependencies));
    }

    /** Maps the dependency strength + significance to a causal edge type. */
    static CausalEdgeType classify(double dependency, boolean significant, long forward, long reverse) {
        boolean symmetric = forward > 0 && reverse > 0 && Math.abs(dependency) < 0.2;
        if (symmetric) {
            return CausalEdgeType.CORRELATES_WITH;       // both orderings ⇒ concurrency/association
        }
        if (significant && dependency >= 0.7) {
            return CausalEdgeType.CAUSES;
        }
        if (significant && dependency >= 0.4) {
            return CausalEdgeType.TRIGGERS;
        }
        if (dependency >= 0.2) {
            return CausalEdgeType.CONTRIBUTES_TO;
        }
        return CausalEdgeType.CORRELATES_WITH;
    }

    /**
     * Emits one weighted PSL propagation rule per strong directed dependency, mirroring the engine's
     * built-in {@code State(X) & Link(X,Y) -> State(Y)} rule but grounded on the discovered activities
     * with a weight equal to the dependency strength. Every rule is valid {@code PslRule} syntax.
     */
    static List<String> generatePslRules(List<CausalDependency> dependencies) {
        List<String> rules = new ArrayList<>();
        for (CausalDependency d : dependencies) {
            if (d.type() == CausalEdgeType.CAUSES || d.type() == CausalEdgeType.TRIGGERS) {
                double weight = Math.max(0.1, Math.min(0.99, Math.abs(d.dependency())));
                String a = sanitize(d.from());
                String b = sanitize(d.to());
                rules.add(String.format(Locale.ROOT,
                        "%.2f: State(\"%s\") & Link(\"%s\",\"%s\") -> State(\"%s\") ^2", weight, a, a, b, b));
            }
        }
        return rules;
    }

    private static String sanitize(String label) {
        return label.replace('"', ' ').trim();
    }
}
