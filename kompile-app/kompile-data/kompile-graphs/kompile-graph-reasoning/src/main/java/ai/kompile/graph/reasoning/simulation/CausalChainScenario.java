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
package ai.kompile.graph.reasoning.simulation;

import ai.kompile.graph.reasoning.simulation.GroundTruthManifest.ExpectedEdge;
import ai.kompile.graph.reasoning.simulation.ScenarioDescriptor.ParamSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Temporal event log sampled from a known causal DAG: several incident chains whose events fire
 * in order, plus confounder root-cause events that trigger the heads of two chains at once
 * (making those heads correlated but NOT causally linked to each other).
 *
 * <p>Observed: all events with {@code occurredAt} timestamps, and for each adjacent pair either
 * the true {@code TRIGGERS} edge (at {@code observedTriggerRate}) or a mere {@code PRECEDES}
 * sequence edge (the store keeps one edge per node pair). Planted truth: the full causal DAG in
 * {@link GroundTruthManifest#expectedCausalLinks()} and the
 * confounded head pairs in {@link GroundTruthManifest#forbiddenCausalLinks()} — inferring a
 * confounded pair is a scored error.</p>
 *
 * <p>All timestamps derive from a fixed base instant so generation is deterministic.</p>
 */
public final class CausalChainScenario implements GraphScenario {

    static final ParamSpec CHAINS = ParamSpec.ofInt("chains", "Incident chains", 4, 1, 12);
    static final ParamSpec CHAIN_LENGTH = ParamSpec.ofInt("chainLength", "Events per chain", 5, 3, 12);
    static final ParamSpec CONFOUNDERS = ParamSpec.ofInt("confounders", "Confounder root causes", 3, 0, 10);
    static final ParamSpec OBSERVED_TRIGGER_RATE =
            ParamSpec.ofDouble("observedTriggerRate", "Fraction of TRIGGERS edges observed", 0.7, 0.2, 1.0);
    static final ParamSpec TICKS = ParamSpec.ofInt("ticks", "Hydration ticks", 4, 1, 20);

    /** Fixed epoch base (2026-01-01T00:00:00Z) — never wall clock, for determinism. */
    static final long BASE_EPOCH_MS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long STEP_MS = 3_600_000L;   // one hour between chain steps

    private static final String[] EVENT_KIND = {
            "Alert", "Outage", "Backlog", "Failover", "Spike", "Restart", "Rollback", "Recovery",
            "Timeout", "Degradation", "Escalation", "Resolution"};

    @Override
    public ScenarioDescriptor describe() {
        return new ScenarioDescriptor(
                "causal-chain",
                "Causal Chains",
                "Timestamped incident chains sampled from a known causal DAG, with confounder root "
                        + "causes that make two chain heads correlated but not causal. Observed: events, "
                        + "PRECEDES sequence edges, and a fraction of true TRIGGERS edges. Truth: the full "
                        + "causal DAG (recover the unobserved links) and the confounded pairs (inferring "
                        + "those is a scored error).",
                List.of("causal-links"),
                List.of(CHAINS, CHAIN_LENGTH, CONFOUNDERS, OBSERVED_TRIGGER_RATE, TICKS));
    }

    @Override
    public ScenarioRun generate(long seed, Map<String, Object> params) {
        int nChains = ScenarioParams.intParam(params, CHAINS);
        int chainLength = ScenarioParams.intParam(params, CHAIN_LENGTH);
        int nConfounders = ScenarioParams.intParam(params, CONFOUNDERS);
        double observedTriggerRate = ScenarioParams.doubleParam(params, OBSERVED_TRIGGER_RATE);
        int nTicks = ScenarioParams.intParam(params, TICKS);

        Random rnd = new Random(seed);

        List<ScenarioNode> nodes = new ArrayList<>();
        List<ScenarioEdge> edges = new ArrayList<>();
        List<ExpectedEdge> causalTruth = new ArrayList<>();
        List<ExpectedEdge> forbidden = new ArrayList<>();
        Set<String> pairSeen = new HashSet<>();

        String[][] chainEventKeys = new String[nChains][chainLength];
        for (int c = 0; c < nChains; c++) {
            for (int k = 0; k < chainLength; k++) {
                String key = "ev" + c + "_" + k;
                chainEventKeys[c][k] = key;
                long at = BASE_EPOCH_MS + (long) c * 50 * STEP_MS + (long) k * STEP_MS;
                String name = EVENT_KIND[(c + k) % EVENT_KIND.length] + " C" + (c + 1) + "-S" + (k + 1);
                nodes.add(new ScenarioNode(key, name, "Event",
                        Map.of("occurredAt", Instant.ofEpochMilli(at).toString(), "chain", "C" + (c + 1)), at));
                if (k > 0) {
                    String prev = chainEventKeys[c][k - 1];
                    causalTruth.add(new ExpectedEdge(prev, "TRIGGERS", key,
                            "true causal step " + k + " of chain C" + (c + 1)));
                    // The store keeps ONE edge per (source,target) pair, so each adjacent pair is
                    // observed either as a causal TRIGGERS link or as mere PRECEDES sequence info —
                    // the unobserved TRIGGERS links are what causal reasoning should recover.
                    if (rnd.nextDouble() < observedTriggerRate) {
                        addEdge(edges, pairSeen, prev, key, "TRIGGERS", jitter(rnd, 0.8, 0.1));
                    } else {
                        addEdge(edges, pairSeen, prev, key, "PRECEDES", jitter(rnd, 0.9, 0.05));
                    }
                }
            }
        }

        // Confounders: one early root cause triggers the heads of two different chains.
        for (int f = 0; f < nConfounders && nChains >= 2; f++) {
            String key = "conf" + f;
            long at = BASE_EPOCH_MS - (f + 1) * STEP_MS;
            nodes.add(new ScenarioNode(key, "Root Cause RC-" + (f + 1), "Event",
                    Map.of("occurredAt", Instant.ofEpochMilli(at).toString()), at));
            int a = rnd.nextInt(nChains);
            int b = rnd.nextInt(nChains);
            if (a == b) b = (b + 1) % nChains;
            String headA = chainEventKeys[a][0];
            String headB = chainEventKeys[b][0];
            addEdge(edges, pairSeen, key, headA, "TRIGGERS", jitter(rnd, 0.85, 0.1));
            addEdge(edges, pairSeen, key, headB, "TRIGGERS", jitter(rnd, 0.85, 0.1));
            causalTruth.add(new ExpectedEdge(key, "TRIGGERS", headA, "confounder root cause"));
            causalTruth.add(new ExpectedEdge(key, "TRIGGERS", headB, "confounder root cause"));
            forbidden.add(new ExpectedEdge(headA, "TRIGGERS", headB,
                    "correlated via common cause RC-" + (f + 1) + ", NOT causal", true));
        }

        GroundTruthManifest truth = new GroundTruthManifest(
                List.of(), List.of(), List.of(), causalTruth, forbidden, List.of(), Set.of());
        return new ScenarioRun("causal-chain", seed, Ticks.partition(nodes, edges, nTicks), truth);
    }

    private static void addEdge(List<ScenarioEdge> edges, Set<String> pairSeen,
                                String src, String dst, String rel, double conf) {
        if (pairSeen.add(src + "|" + dst)) {
            edges.add(ScenarioEdge.of(src, dst, rel, conf));
        }
    }

    private static double jitter(Random rnd, double base, double spread) {
        double v = base + (rnd.nextDouble() - 0.5) * 2 * spread;
        return Math.max(0.05, Math.min(0.99, v));
    }
}
