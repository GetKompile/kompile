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

import ai.kompile.event.attribution.algorithm.psl.HlMrfMapInference;
import ai.kompile.event.attribution.algorithm.psl.PslProgram;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph.Arc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live PSL inference driven by a discovered process: it builds a real {@link PslProgram} over the
 * activities of a directly-follows graph and runs the project's HL-MRF engine on it — the mined
 * structure actually drives the inference, with no LLM and no changes to the attribution engine.
 *
 * <p>Encoding (the same predicate vocabulary {@code KgPslProgramBuilder} uses, so it rides the same
 * solver): {@code State(a)} is the soft truth that activity {@code a} is "active" (a target unless it
 * is given as evidence); {@code Link(a,b)} is observed at the directed dependency strength of the
 * directly-follows arc; {@code Prior(a)} anchors each activity (start activities high, others low). The
 * built-in propagation/abduction/prior rules then settle a globally consistent activation — e.g. clamp
 * the first step to active and the downstream steps light up in proportion to dependency strength.
 */
public final class ProcessPslInference {

    private static final String STATE = "State";
    private static final String LINK = "Link";
    private static final String PRIOR = "Prior";

    /**
     * @param activation  activity → inferred soft truth ∈ [0,1]
     * @param priors      activity → the structural prior it was anchored to
     * @param groundRules number of ground rules the program produced
     * @param iterations  solver iterations
     * @param converged   whether the solver reached tolerance
     */
    public record Result(
            Map<String, Double> activation,
            Map<String, Double> priors,
            int groundRules,
            int iterations,
            boolean converged) {
    }

    private ProcessPslInference() {
    }

    public static Result infer(DirectlyFollowsGraph dfg, Collection<String> evidenceActive) {
        List<String> activities = new ArrayList<>(dfg.activities());
        Map<String, String> toConst = new LinkedHashMap<>();
        for (int i = 0; i < activities.size(); i++) {
            toConst.put(activities.get(i), "a" + i);   // lowercase ⇒ a ground constant, not a variable
        }
        Set<String> evidence = (evidenceActive == null) ? Set.of() : new HashSet<>(evidenceActive);

        PslProgram program = new PslProgram();
        Map<String, Double> priors = new LinkedHashMap<>();

        Map<String, Long> starts = dfg.startActivities();
        long maxStart = starts.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        for (String a : activities) {
            double prior = starts.containsKey(a)
                    ? Math.min(0.9, 0.5 + 0.4 * starts.get(a) / Math.max(1L, maxStart))
                    : 0.1;
            priors.put(a, prior);
            program.observe(PRIOR, prior, toConst.get(a));
            if (evidence.contains(a)) {
                program.observe(STATE, 1.0, toConst.get(a));   // fixed evidence
            } else {
                program.target(STATE, toConst.get(a));          // to be inferred
            }
        }

        // Observed Link truth = directed dependency strength of the directly-follows arc.
        for (Arc arc : dfg.arcs().keySet()) {
            double strength = clamp01(DependencyMeasures.dependency(dfg, arc.from(), arc.to()));
            if (strength > 0.0) {
                program.observe(LINK, strength, toConst.get(arc.from()), toConst.get(arc.to()));
            }
        }

        // The engine's standard rules: propagation, abduction, and prior anchoring.
        program.addRule("1.0: " + STATE + "(X) & " + LINK + "(X, Y) -> " + STATE + "(Y) ^2");
        program.addRule("0.5: " + STATE + "(Y) & " + LINK + "(X, Y) -> " + STATE + "(X) ^2");
        program.addRule("0.5: " + PRIOR + "(N) -> " + STATE + "(N) ^2");
        program.addRule("0.5: " + STATE + "(N) -> " + PRIOR + "(N) ^2");

        HlMrfMapInference.Result res = HlMrfMapInference.solve(program);

        Map<String, Double> activation = new LinkedHashMap<>();
        for (String a : activities) {
            if (evidence.contains(a)) {
                activation.put(a, 1.0);
            } else {
                Double v = res.values().get(STATE + "(" + toConst.get(a) + ")");
                activation.put(a, v != null ? v : priors.get(a));
            }
        }
        return new Result(activation, priors, res.groundRules().size(), res.iterations(), res.converged());
    }

    private static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }
}
