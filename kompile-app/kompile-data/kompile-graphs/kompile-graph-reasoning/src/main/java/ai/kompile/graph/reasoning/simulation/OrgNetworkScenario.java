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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Organizational network with a planted containment hierarchy and community structure:
 * org → department → team ({@code PART_OF} chains), people as team members with reporting lines,
 * and collaboration edges that are dense inside a team and sparse across teams.
 *
 * <p>Planted truth:</p>
 * <ul>
 *   <li><b>inferred-edges</b> — the transitive containment closure {@code PART_OF(team, org)}
 *       is never observed; the ontology/has-a closure reasoning should materialize it.</li>
 *   <li><b>communities</b> — each team's member set is one planted community; community detection
 *       over the collaboration edges should co-assign teammates.</li>
 * </ul>
 *
 * <p>This scenario also exercises ontology auto-provisioning: the {@code PART_OF}-shaped
 * structure is exactly what the structural-ontology deriver mines has-a relations from.</p>
 */
public final class OrgNetworkScenario implements GraphScenario {

    static final ParamSpec ORGS = ParamSpec.ofInt("orgs", "Organizations", 3, 1, 10);
    static final ParamSpec DEPTS_PER_ORG = ParamSpec.ofInt("deptsPerOrg", "Departments per org", 3, 1, 10);
    static final ParamSpec TEAMS_PER_DEPT = ParamSpec.ofInt("teamsPerDept", "Teams per department", 3, 1, 10);
    static final ParamSpec PEOPLE_PER_TEAM = ParamSpec.ofInt("peoplePerTeam", "People per team", 5, 2, 30);
    static final ParamSpec INTRA_COLLAB = ParamSpec.ofDouble("intraCollabProb",
            "Intra-team collaboration probability", 0.5, 0.05, 1.0);
    static final ParamSpec INTER_COLLAB = ParamSpec.ofDouble("interCollabProb",
            "Cross-team collaboration probability", 0.02, 0.0, 0.3);
    static final ParamSpec TICKS = ParamSpec.ofInt("ticks", "Hydration ticks", 4, 1, 20);

    private static final String[] FIRST = {
            "Ada", "Boris", "Carla", "Deniz", "Emil", "Freya", "Goran", "Hana",
            "Ivo", "Jala", "Kenji", "Leila", "Marco", "Nadia", "Omar", "Petra",
            "Rene", "Sana", "Timo", "Uma"};
    private static final String[] LAST = {
            "Almeida", "Brandt", "Cardoso", "Dvorak", "Eriksen", "Farkas", "Gallo", "Haddad",
            "Iversen", "Jansen", "Kovacs", "Larsen", "Moreau", "Nilsen", "Oliveira", "Popov",
            "Rossi", "Silva", "Toth", "Vidal"};
    private static final String[] ORG_STEM = {"Vertex", "Northwind", "Helios", "Borealis", "Meridian",
            "Quanta", "Aurora", "Kestrel", "Cobalt", "Latitude"};
    private static final String[] DEPT_STEM = {"Engineering", "Finance", "Operations", "Sales",
            "Research", "Legal", "Marketing", "Support", "Design", "Security"};
    private static final String[] TEAM_STEM = {"Platform", "Data", "Growth", "Core", "Edge",
            "Atlas", "Nova", "Delta", "Orbit", "Pulse"};
    private static final String[] CITY = {"Berlin", "Tokyo", "Nairobi", "Lima", "Oslo",
            "Porto", "Seoul", "Austin", "Zurich", "Krakow"};

    @Override
    public ScenarioDescriptor describe() {
        return new ScenarioDescriptor(
                "org-network",
                "Org Network",
                "Organizations containing departments containing teams (PART_OF chains), people as "
                        + "team members with reporting lines, and collaboration edges dense inside teams. "
                        + "Planted truth: the never-observed PART_OF(team, org) transitive closure "
                        + "(has-a reasoning) and the per-team community partition (community detection).",
                List.of("inferred-edges", "communities"),
                List.of(ORGS, DEPTS_PER_ORG, TEAMS_PER_DEPT, PEOPLE_PER_TEAM,
                        INTRA_COLLAB, INTER_COLLAB, TICKS));
    }

    @Override
    public ScenarioRun generate(long seed, Map<String, Object> params) {
        int nOrgs = ScenarioParams.intParam(params, ORGS);
        int deptsPerOrg = ScenarioParams.intParam(params, DEPTS_PER_ORG);
        int teamsPerDept = ScenarioParams.intParam(params, TEAMS_PER_DEPT);
        int peoplePerTeam = ScenarioParams.intParam(params, PEOPLE_PER_TEAM);
        double intraCollab = ScenarioParams.doubleParam(params, INTRA_COLLAB);
        double interCollab = ScenarioParams.doubleParam(params, INTER_COLLAB);
        int nTicks = ScenarioParams.intParam(params, TICKS);

        Random rnd = new Random(seed);

        List<ScenarioNode> nodes = new ArrayList<>();
        List<ScenarioEdge> edges = new ArrayList<>();
        List<ExpectedEdge> closure = new ArrayList<>();
        List<Set<String>> communities = new ArrayList<>();
        Set<String> pairSeen = new HashSet<>();

        int deptCounter = 0;
        int teamCounter = 0;
        int personCounter = 0;

        for (int o = 0; o < nOrgs; o++) {
            String orgKey = "o" + o;
            nodes.add(ScenarioNode.of(orgKey, ORG_STEM[o % ORG_STEM.length] + " Group " + (o + 1), "Organization"));
            String cityKey = "city" + o;
            nodes.add(ScenarioNode.of(cityKey, CITY[o % CITY.length], "City"));
            addEdge(edges, pairSeen, orgKey, cityKey, "LOCATED_IN", jitter(rnd, 0.9, 0.05));

            for (int d = 0; d < deptsPerOrg; d++) {
                String deptKey = "d" + (deptCounter++);
                nodes.add(ScenarioNode.of(deptKey,
                        DEPT_STEM[d % DEPT_STEM.length] + " Dept " + deptCounter, "Department"));
                addEdge(edges, pairSeen, deptKey, orgKey, "PART_OF", jitter(rnd, 0.9, 0.05));

                String deptHeadKey = null;
                for (int t = 0; t < teamsPerDept; t++) {
                    String teamKey = "t" + (teamCounter++);
                    nodes.add(ScenarioNode.of(teamKey,
                            TEAM_STEM[(t + d) % TEAM_STEM.length] + " Team " + teamCounter, "Team"));
                    addEdge(edges, pairSeen, teamKey, deptKey, "PART_OF", jitter(rnd, 0.9, 0.05));

                    // The closure edge PART_OF(team, org) is planted truth, never observed.
                    closure.add(new ExpectedEdge(teamKey, "PART_OF", orgKey,
                            "has-a closure: PART_OF(team, dept) ∧ PART_OF(dept, org) → PART_OF(team, org)"));

                    Set<String> teamMembers = new LinkedHashSet<>();
                    String leadKey = null;
                    List<String> memberKeys = new ArrayList<>(peoplePerTeam);
                    for (int p = 0; p < peoplePerTeam; p++) {
                        String personKey = "p" + (personCounter++);
                        String name = FIRST[rnd.nextInt(FIRST.length)] + " "
                                + LAST[rnd.nextInt(LAST.length)] + " " + String.format("%03d", personCounter);
                        boolean lead = (p == 0);
                        nodes.add(new ScenarioNode(personKey, name, "Person",
                                lead ? Map.of("role", "lead") : Map.of(), null));
                        addEdge(edges, pairSeen, personKey, teamKey, "MEMBER_OF", jitter(rnd, 0.85, 0.1));
                        teamMembers.add(personKey);
                        memberKeys.add(personKey);
                        if (lead) {
                            leadKey = personKey;
                            if (deptHeadKey == null) {
                                deptHeadKey = personKey;   // first team's lead heads the department
                            } else {
                                addEdge(edges, pairSeen, personKey, deptHeadKey, "REPORTS_TO",
                                        jitter(rnd, 0.85, 0.1));
                            }
                        } else {
                            addEdge(edges, pairSeen, personKey, leadKey, "REPORTS_TO", jitter(rnd, 0.85, 0.1));
                        }
                    }
                    communities.add(teamMembers);

                    // Dense intra-team collaboration (planted community signal).
                    for (int a = 0; a < memberKeys.size(); a++) {
                        for (int b = a + 1; b < memberKeys.size(); b++) {
                            if (rnd.nextDouble() < intraCollab) {
                                addEdge(edges, pairSeen, memberKeys.get(a), memberKeys.get(b),
                                        "COLLABORATES_WITH", jitter(rnd, 0.75, 0.15));
                            }
                        }
                    }
                }
            }
        }

        // Sparse cross-team collaboration inside each org (community noise floor).
        List<String> allPeople = new ArrayList<>();
        for (ScenarioNode n : nodes) {
            if ("Person".equals(n.entityType())) allPeople.add(n.key());
        }
        int crossAttempts = (int) Math.round(interCollab * allPeople.size() * 4);
        for (int i = 0; i < crossAttempts; i++) {
            String a = allPeople.get(rnd.nextInt(allPeople.size()));
            String b = allPeople.get(rnd.nextInt(allPeople.size()));
            if (a.equals(b)) continue;
            addEdge(edges, pairSeen, a, b, "COLLABORATES_WITH", jitter(rnd, 0.6, 0.15));
        }

        GroundTruthManifest truth = new GroundTruthManifest(
                closure, List.of(), communities, List.of(), List.of(), List.of(), Set.of());
        return new ScenarioRun("org-network", seed, Ticks.partition(nodes, edges, nTicks), truth);
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
