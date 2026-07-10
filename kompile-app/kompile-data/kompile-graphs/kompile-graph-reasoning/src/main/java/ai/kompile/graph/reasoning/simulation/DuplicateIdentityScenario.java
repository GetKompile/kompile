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

import ai.kompile.graph.reasoning.simulation.ScenarioDescriptor.ParamSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Entity-resolution stress test: the same real-world people/organizations appear under multiple
 * surface forms (case changes, initials, corporate-suffix variants), optionally sharing identity
 * signals (emails) and shared graph context (projects, cities) that resolution/compaction can
 * corroborate on.
 *
 * <p>Planted truth: {@link GroundTruthManifest#duplicateSets()} — the variant groups that denote
 * one entity. Recovery = the resolution/compaction stack merges them or links them with
 * {@code RESOLVES_TO}.</p>
 */
public final class DuplicateIdentityScenario implements GraphScenario {

    static final ParamSpec ENTITIES = ParamSpec.ofInt("entities", "Real entities", 24, 4, 200);
    static final ParamSpec DUPLICATE_RATE =
            ParamSpec.ofDouble("duplicateRate", "Fraction with duplicate surface forms", 0.5, 0.1, 1.0);
    static final ParamSpec MAX_VARIANTS = ParamSpec.ofInt("maxVariants", "Max variants per entity", 3, 2, 5);
    static final ParamSpec SHARED_EMAIL_RATE =
            ParamSpec.ofDouble("sharedEmailRate", "Variants sharing the identity email", 0.8, 0.0, 1.0);
    static final ParamSpec TICKS = ParamSpec.ofInt("ticks", "Hydration ticks", 3, 1, 20);

    private static final String[] FIRST = {
            "Ana", "Bela", "Ciro", "Dina", "Egon", "Fola", "Gus", "Hoda",
            "Iris", "Jon", "Kian", "Lena", "Milo", "Nia", "Oskar", "Pia"};
    private static final String[] LAST = {
            "Adler", "Bianchi", "Castro", "Dahl", "Egede", "Ferrer", "Grosz", "Horvat",
            "Ito", "Jung", "Keller", "Lund", "Meier", "Nagy", "Orta", "Pavic"};
    private static final String[] ORG_STEM = {"Acme", "Vertex", "Helios", "Borealis", "Quanta",
            "Meridian", "Kestrel", "Cobalt"};
    private static final String[] PROJECT_STEM = {"Zeta", "Falcon", "Mosaic", "Anchor", "Beacon",
            "Cinder", "Drift", "Ember"};
    private static final String[] CITY = {"Berlin", "Tokyo", "Nairobi", "Lima", "Oslo", "Porto"};

    @Override
    public ScenarioDescriptor describe() {
        return new ScenarioDescriptor(
                "duplicate-identity",
                "Duplicate Identity",
                "The same people/organizations appear under multiple surface forms (case, initials, "
                        + "corporate suffixes) with shared identity emails and shared project/city context. "
                        + "Planted truth: the duplicate groups. Recovery = entity resolution / compaction "
                        + "merges them or links them with RESOLVES_TO.",
                List.of("resolutions"),
                List.of(ENTITIES, DUPLICATE_RATE, MAX_VARIANTS, SHARED_EMAIL_RATE, TICKS));
    }

    @Override
    public ScenarioRun generate(long seed, Map<String, Object> params) {
        int nEntities = ScenarioParams.intParam(params, ENTITIES);
        double duplicateRate = ScenarioParams.doubleParam(params, DUPLICATE_RATE);
        int maxVariants = ScenarioParams.intParam(params, MAX_VARIANTS);
        double sharedEmailRate = ScenarioParams.doubleParam(params, SHARED_EMAIL_RATE);
        int nTicks = ScenarioParams.intParam(params, TICKS);

        Random rnd = new Random(seed);

        List<ScenarioNode> nodes = new ArrayList<>();
        List<ScenarioEdge> edges = new ArrayList<>();
        List<Set<String>> duplicateSets = new ArrayList<>();
        Set<String> pairSeen = new HashSet<>();

        // Shared context nodes every variant can attach to.
        List<String> projectKeys = new ArrayList<>();
        for (int i = 0; i < Math.max(3, nEntities / 4); i++) {
            String key = "proj" + i;
            projectKeys.add(key);
            nodes.add(ScenarioNode.of(key, "Project " + PROJECT_STEM[i % PROJECT_STEM.length]
                    + " " + (i + 1), "Project"));
        }
        List<String> cityKeys = new ArrayList<>();
        for (int i = 0; i < CITY.length; i++) {
            String key = "city" + i;
            cityKeys.add(key);
            nodes.add(ScenarioNode.of(key, CITY[i], "City"));
        }

        for (int e = 0; e < nEntities; e++) {
            boolean person = rnd.nextBoolean();
            String canonicalName;
            String email;
            if (person) {
                canonicalName = FIRST[rnd.nextInt(FIRST.length)] + " "
                        + LAST[rnd.nextInt(LAST.length)] + " " + String.format("%02d", e + 1);
                email = canonicalName.toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.com";
            } else {
                canonicalName = ORG_STEM[rnd.nextInt(ORG_STEM.length)] + " Dynamics " + (e + 1);
                email = "contact@" + canonicalName.toLowerCase(Locale.ROOT).replace(' ', '-') + ".example.com";
            }
            String type = person ? "Person" : "Organization";

            int variants = (rnd.nextDouble() < duplicateRate)
                    ? 2 + rnd.nextInt(Math.max(1, maxVariants - 1))
                    : 1;
            Set<String> group = new LinkedHashSet<>();

            // Each entity anchors to shared context so compaction has corroborating structure.
            String project = projectKeys.get(rnd.nextInt(projectKeys.size()));
            String city = cityKeys.get(rnd.nextInt(cityKeys.size()));

            for (int v = 0; v < variants; v++) {
                String key = "e" + e + "_v" + v;
                group.add(key);
                Map<String, Object> meta = new LinkedHashMap<>();
                boolean shareEmail = (v == 0) || rnd.nextDouble() < sharedEmailRate;
                meta.put("email", shareEmail ? email : ("alt." + v + "." + email));
                nodes.add(new ScenarioNode(key, variantName(canonicalName, person, v), type, meta, null));
                addEdge(edges, pairSeen, key, project, person ? "WORKS_ON" : "SPONSORS",
                        jitter(rnd, 0.8, 0.1));
                addEdge(edges, pairSeen, key, city, "LOCATED_IN", jitter(rnd, 0.8, 0.1));
            }
            if (group.size() > 1) {
                duplicateSets.add(group);
            }
        }

        GroundTruthManifest truth = new GroundTruthManifest(
                List.of(), duplicateSets, List.of(), List.of(), List.of(), List.of(), Set.of());
        return new ScenarioRun("duplicate-identity", seed, Ticks.partition(nodes, edges, nTicks), truth);
    }

    /** Deterministic surface-form variants: v0 = canonical. */
    private static String variantName(String canonical, boolean person, int v) {
        if (v == 0) return canonical;
        if (person) {
            String[] parts = canonical.split(" ");
            return switch (v % 3) {
                case 1 -> parts[0].charAt(0) + ". " + parts[1] + " " + parts[2];      // "A. Adler 01"
                case 2 -> canonical.toLowerCase(Locale.ROOT);                          // "ana adler 01"
                default -> parts[1] + ", " + parts[0] + " " + parts[2];                // "Adler, Ana 01"
            };
        }
        return switch (v % 3) {
            case 1 -> canonical + " Corporation";
            case 2 -> canonical.toUpperCase(Locale.ROOT);
            default -> canonical.replace(" Dynamics", " Dyn.");
        };
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
