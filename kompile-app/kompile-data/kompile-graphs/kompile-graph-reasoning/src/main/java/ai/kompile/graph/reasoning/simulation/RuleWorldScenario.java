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
import ai.kompile.graph.reasoning.simulation.GroundTruthManifest.GeneratingRule;
import ai.kompile.graph.reasoning.simulation.ScenarioDescriptor.ParamSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The purest "did it learn the pattern" scenario: observations are sampled from three hidden
 * weighted rules, a fraction of each rule's heads is emitted as supporting evidence (so rule
 * mining / propagation has something to learn from) and the REST of the heads are withheld —
 * recovery means the reasoning cascade re-derives the held-out heads as INFERRED edges.
 *
 * <p>Hidden rules:</p>
 * <pre>
 * R1: WORKS_FOR(p,o) &amp; LOCATED_IN(o,c) → BASED_IN(p,c)        (w = 0.9)
 * R2: MENTORS(s,j)   &amp; WORKS_FOR(j,o)  → AFFILIATED_WITH(s,o) (w = 0.8)
 * R3: PARTNERS_WITH(a,b)               → PARTNERS_WITH(b,a)    (w = 0.7, symmetry)
 * </pre>
 *
 * <p>Noise edges (spurious relations between random pairs) are planted at {@code noiseRate} and
 * recorded in {@link GroundTruthManifest#corruptedEdgeKeys()}; the maintenance side should end up
 * suppressing them, and the scorer never counts them against the reasoner. A noise edge is never
 * allowed to coincide with a held-out head (that would leak the answer).</p>
 */
public final class RuleWorldScenario implements GraphScenario {

    static final ParamSpec PEOPLE = ParamSpec.ofInt("people", "People", 40, 5, 500);
    static final ParamSpec ORGS = ParamSpec.ofInt("orgs", "Organizations", 8, 2, 50);
    static final ParamSpec CITIES = ParamSpec.ofInt("cities", "Cities", 6, 2, 30);
    static final ParamSpec SUPPORT_RATE =
            ParamSpec.ofDouble("supportRate", "Rule support rate (observed head fraction)", 0.6, 0.1, 0.95);
    static final ParamSpec NOISE_RATE = ParamSpec.ofDouble("noiseRate", "Noise rate", 0.05, 0.0, 0.5);
    static final ParamSpec TICKS = ParamSpec.ofInt("ticks", "Hydration ticks", 4, 1, 20);

    private static final String[] FIRST = {
            "Alice", "Bruno", "Chen", "Dara", "Elif", "Farid", "Grete", "Hiro",
            "Ines", "Jonas", "Kaia", "Lior", "Mina", "Nora", "Otto", "Priya",
            "Quinn", "Rosa", "Sami", "Tova"};
    private static final String[] LAST = {
            "Vega", "Okafor", "Lindqvist", "Marchetti", "Novak", "Osei", "Petrov", "Quispe",
            "Reyes", "Sato", "Tanaka", "Umarov", "Varga", "Weiss", "Xu", "Yilmaz",
            "Zhang", "Ahmadi", "Bergström", "Costa"};
    private static final String[] ORG_STEM = {
            "Acme", "Vertex", "Northwind", "Helios", "Quanta", "Borealis",
            "Meridian", "Zephyr", "Cobalt", "Aurora", "Kestrel", "Latitude"};
    private static final String[] ORG_SUFFIX = {"Corp", "Labs", "Group", "Systems", "Industries", "Partners"};
    private static final String[] CITY = {
            "Berlin", "Tokyo", "Nairobi", "Lima", "Oslo", "Porto", "Seoul", "Austin",
            "Zurich", "Krakow", "Osaka", "Bogota", "Tallinn", "Lyon", "Perth", "Quito"};

    @Override
    public ScenarioDescriptor describe() {
        return new ScenarioDescriptor(
                "rule-world",
                "Rule World",
                "Observations sampled from three hidden weighted rules (workplace/location/mentorship). "
                        + "A supportRate fraction of each rule's heads is observed as evidence; the rest are "
                        + "withheld as ground truth. Recovery = the cascade re-derives the held-out heads as "
                        + "INFERRED edges. Optional noise plants spurious relations the maintenance side "
                        + "should suppress.",
                List.of("inferred-edges"),
                List.of(PEOPLE, ORGS, CITIES, SUPPORT_RATE, NOISE_RATE, TICKS));
    }

    @Override
    public ScenarioRun generate(long seed, Map<String, Object> params) {
        int nPeople = ScenarioParams.intParam(params, PEOPLE);
        int nOrgs = ScenarioParams.intParam(params, ORGS);
        int nCities = ScenarioParams.intParam(params, CITIES);
        double supportRate = ScenarioParams.doubleParam(params, SUPPORT_RATE);
        double noiseRate = ScenarioParams.doubleParam(params, NOISE_RATE);
        int nTicks = ScenarioParams.intParam(params, TICKS);

        Random rnd = new Random(seed);

        List<ScenarioNode> cities = new ArrayList<>(nCities);
        for (int i = 0; i < nCities; i++) {
            cities.add(ScenarioNode.of("c" + i, uniqueName(CITY, i), "City"));
        }
        List<ScenarioNode> orgs = new ArrayList<>(nOrgs);
        for (int i = 0; i < nOrgs; i++) {
            String name = ORG_STEM[rnd.nextInt(ORG_STEM.length)] + " "
                    + ORG_SUFFIX[rnd.nextInt(ORG_SUFFIX.length)] + " " + (i + 1);
            orgs.add(ScenarioNode.of("o" + i, name, "Organization"));
        }
        List<ScenarioNode> people = new ArrayList<>(nPeople);
        for (int i = 0; i < nPeople; i++) {
            String name = FIRST[rnd.nextInt(FIRST.length)] + " "
                    + LAST[rnd.nextInt(LAST.length)] + " " + String.format("%02d", i + 1);
            people.add(ScenarioNode.of("p" + i, name, "Person"));
        }

        List<ScenarioEdge> edges = new ArrayList<>();
        List<ExpectedEdge> heldOut = new ArrayList<>();
        Set<String> pairSeen = new HashSet<>();       // ordered (src|dst) — store dedups per pair
        Set<String> heldOutKeys = new HashSet<>();

        // Base structure: every org located in a city; every person works for an org.
        int[] cityOfOrg = new int[nOrgs];
        for (int i = 0; i < nOrgs; i++) {
            cityOfOrg[i] = rnd.nextInt(nCities);
            addEdge(edges, pairSeen, "o" + i, "c" + cityOfOrg[i], "LOCATED_IN", jitter(rnd, 0.85, 0.1), false);
        }
        int[] orgOfPerson = new int[nPeople];
        for (int i = 0; i < nPeople; i++) {
            orgOfPerson[i] = rnd.nextInt(nOrgs);
            addEdge(edges, pairSeen, "p" + i, "o" + orgOfPerson[i], "WORKS_FOR", jitter(rnd, 0.8, 0.1), false);
        }

        // R1: WORKS_FOR ∧ LOCATED_IN → BASED_IN — observe supportRate of heads, hold out the rest.
        for (int i = 0; i < nPeople; i++) {
            String personKey = "p" + i;
            String cityKey = "c" + cityOfOrg[orgOfPerson[i]];
            if (rnd.nextDouble() < supportRate) {
                addEdge(edges, pairSeen, personKey, cityKey, "BASED_IN", jitter(rnd, 0.75, 0.15), false);
            } else {
                holdOut(heldOut, heldOutKeys, new ExpectedEdge(personKey, "BASED_IN", cityKey,
                        "R1: WORKS_FOR ∧ LOCATED_IN → BASED_IN"));
            }
        }

        // R2: MENTORS ∧ WORKS_FOR → AFFILIATED_WITH (skip trivially-same-org pairs).
        Set<String> affiliationSeen = new HashSet<>();
        int mentorPairs = Math.max(1, nPeople / 3);
        for (int m = 0; m < mentorPairs; m++) {
            int s = rnd.nextInt(nPeople);
            int j = rnd.nextInt(nPeople);
            if (s == j || orgOfPerson[s] == orgOfPerson[j]) continue;
            if (!addEdge(edges, pairSeen, "p" + s, "p" + j, "MENTORS", jitter(rnd, 0.8, 0.1), false)) continue;
            String orgKey = "o" + orgOfPerson[j];
            if (!affiliationSeen.add("p" + s + "|" + orgKey)) continue;
            if (rnd.nextDouble() < supportRate) {
                addEdge(edges, pairSeen, "p" + s, orgKey, "AFFILIATED_WITH", jitter(rnd, 0.7, 0.15), false);
            } else {
                holdOut(heldOut, heldOutKeys, new ExpectedEdge("p" + s, "AFFILIATED_WITH", orgKey,
                        "R2: MENTORS ∧ WORKS_FOR → AFFILIATED_WITH"));
            }
        }

        // R3: PARTNERS_WITH symmetry — always observe a→b, observe b→a only at supportRate.
        Set<String> partnerPairs = new LinkedHashSet<>();
        for (int t = 0; t < nOrgs; t++) {
            int a = rnd.nextInt(nOrgs);
            int b = rnd.nextInt(nOrgs);
            if (a == b) continue;
            if (!partnerPairs.add(Math.min(a, b) + "|" + Math.max(a, b))) continue;
            addEdge(edges, pairSeen, "o" + a, "o" + b, "PARTNERS_WITH", jitter(rnd, 0.8, 0.1), false);
            if (rnd.nextDouble() < supportRate) {
                addEdge(edges, pairSeen, "o" + b, "o" + a, "PARTNERS_WITH", jitter(rnd, 0.8, 0.1), false);
            } else {
                holdOut(heldOut, heldOutKeys, new ExpectedEdge("o" + b, "PARTNERS_WITH", "o" + a,
                        "R3: PARTNERS_WITH is symmetric"));
            }
        }

        // Noise: spurious relations between random nodes; never on a held-out head or seen pair.
        List<ScenarioNode> allNodes = new ArrayList<>(cities.size() + orgs.size() + people.size());
        allNodes.addAll(cities);
        allNodes.addAll(orgs);
        allNodes.addAll(people);
        String[] noiseRels = {"WORKS_FOR", "BASED_IN", "AFFILIATED_WITH", "LOCATED_IN"};
        Set<String> corrupted = new LinkedHashSet<>();
        int noiseTarget = (int) Math.round(noiseRate * edges.size());
        for (int n = 0; n < noiseTarget; n++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                String src = allNodes.get(rnd.nextInt(allNodes.size())).key();
                String dst = allNodes.get(rnd.nextInt(allNodes.size())).key();
                String rel = noiseRels[rnd.nextInt(noiseRels.length)];
                if (src.equals(dst)) continue;
                String edgeKey = GroundTruthManifest.edgeKey(src, rel, dst);
                if (heldOutKeys.contains(edgeKey) || pairSeen.contains(src + "|" + dst)) continue;
                addEdge(edges, pairSeen, src, dst, rel, jitter(rnd, 0.55, 0.1), true);
                corrupted.add(edgeKey);
                break;
            }
        }

        GroundTruthManifest truth = new GroundTruthManifest(
                heldOut,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new GeneratingRule("WORKS_FOR(p,o) & LOCATED_IN(o,c) -> BASED_IN(p,c)", 0.9),
                        new GeneratingRule("MENTORS(s,j) & WORKS_FOR(j,o) -> AFFILIATED_WITH(s,o)", 0.8),
                        new GeneratingRule("PARTNERS_WITH(a,b) -> PARTNERS_WITH(b,a)", 0.7)),
                corrupted);

        List<ScenarioNode> nodeOrder = new ArrayList<>(allNodes.size());
        nodeOrder.addAll(cities);
        nodeOrder.addAll(orgs);
        nodeOrder.addAll(people);
        return new ScenarioRun("rule-world", seed, Ticks.partition(nodeOrder, edges, nTicks), truth);
    }

    private static void holdOut(List<ExpectedEdge> heldOut, Set<String> heldOutKeys, ExpectedEdge e) {
        if (heldOutKeys.add(GroundTruthManifest.edgeKey(e.sourceKey(), e.relationType(), e.targetKey()))) {
            heldOut.add(e);
        }
    }

    private static boolean addEdge(List<ScenarioEdge> edges, Set<String> pairSeen,
                                   String src, String dst, String rel, double conf, boolean noise) {
        if (!pairSeen.add(src + "|" + dst)) {
            return false;
        }
        edges.add(new ScenarioEdge(src, dst, rel, conf, Map.of(), noise));
        return true;
    }

    private static double jitter(Random rnd, double base, double spread) {
        double v = base + (rnd.nextDouble() - 0.5) * 2 * spread;
        return Math.max(0.05, Math.min(0.99, v));
    }

    private static String uniqueName(String[] pool, int i) {
        return i < pool.length ? pool[i] : pool[i % pool.length] + " " + (i / pool.length + 1);
    }
}
