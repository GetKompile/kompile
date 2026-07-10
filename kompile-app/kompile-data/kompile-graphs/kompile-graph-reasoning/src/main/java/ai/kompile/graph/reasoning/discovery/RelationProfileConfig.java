/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.discovery;

import ai.kompile.graph.reasoning.discovery.RelationCandidateDiscovery.RelationProfile;
import ai.kompile.graph.reasoning.discovery.RelationCandidateDiscovery.TermPenalty;
import ai.kompile.graph.reasoning.discovery.RelationCandidateDiscovery.TermRule;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** JSON config for graph-only relation candidate discovery profiles. */
public record RelationProfileConfig(List<RelationProfile> profiles) {

    public static final String DEFAULT_ARTIFACT = "relation-candidate-profiles.json";

    public RelationProfileConfig {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    public Optional<RelationProfile> profile(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return Optional.empty();
        }
        for (RelationProfile profile : profiles) {
            if (relationType.equalsIgnoreCase(profile.relationType())) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        List<Object> jsonProfiles = new ArrayList<>();
        for (RelationProfile profile : profiles) {
            jsonProfiles.add(toMap(profile));
        }
        root.put("profiles", jsonProfiles);
        return MiniJson.write(root);
    }

    public void putArtifact(UnifiedGraph graph) {
        putArtifact(graph, DEFAULT_ARTIFACT);
    }

    public void putArtifact(UnifiedGraph graph, String artifactName) {
        Objects.requireNonNull(graph, "graph");
        graph.putArtifactText(artifactName, toJson());
    }

    public static RelationProfileConfig fromArtifact(UnifiedGraph graph) {
        return fromArtifact(graph, DEFAULT_ARTIFACT);
    }

    public static RelationProfileConfig fromArtifact(UnifiedGraph graph, String artifactName) {
        Objects.requireNonNull(graph, "graph");
        String json = graph.artifactText(artifactName);
        if (json == null || json.isBlank()) {
            return new RelationProfileConfig(List.of());
        }
        return fromJson(json);
    }

    public static RelationProfileConfig fromJson(String json) {
        Map<String, Object> root = MiniJson.parseObject(json);
        Object profilesValue = root.get("profiles");
        List<RelationProfile> profiles = new ArrayList<>();
        if (profilesValue instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Map<?, ?> profileMap) {
                    profiles.add(fromMap(profileMap));
                }
            }
        } else if (root.containsKey("relationType")) {
            profiles.add(fromMap(root));
        }
        return new RelationProfileConfig(profiles);
    }

    private static RelationProfile fromMap(Map<?, ?> raw) {
        String relationType = string(raw, "relationType", string(raw, "name", null));
        RelationProfile.Builder builder = RelationProfile.builder(relationType)
                .sourceTypes(strings(raw.get("sourceTypes")).toArray(String[]::new))
                .targetTypes(strings(raw.get("targetTypes")).toArray(String[]::new))
                .relationTerms(strings(raw.get("relationTerms")).toArray(String[]::new))
                .weights(number(raw, "semanticWeight", 0.35),
                        number(raw, "lexicalWeight", 0.20),
                        number(raw, "termWeight", 0.35),
                        number(raw, "priorWeight", 0.10))
                .minScore(number(raw, "minScore", 0.50))
                .topKPerSource((int) Math.round(number(raw, "topKPerSource", 1.0)))
                .skipSourcesWithExistingRelation(bool(raw, "skipSourcesWithExistingRelation", false))
                .excludeExistingPairs(bool(raw, "excludeExistingPairs", true));

        for (Object item : list(raw.get("boosts"))) {
            if (item instanceof Map<?, ?> boost) {
                builder.boost(string(boost, "name", "boost"), number(boost, "weight", 0.0),
                        strings(boost.get("sourceTerms")), strings(boost.get("targetTerms")));
            }
        }
        for (Object item : list(raw.get("penalties"))) {
            if (item instanceof Map<?, ?> penalty) {
                Set<String> targetTerms = strings(penalty.get("targetTerms"));
                Set<String> exemptSourceTerms = strings(penalty.get("exemptSourceTerms"));
                if (exemptSourceTerms.isEmpty()) {
                    builder.penalizeTarget(string(penalty, "name", "penalty"),
                            number(penalty, "penalty", number(penalty, "weight", 0.0)), targetTerms);
                } else {
                    builder.penalizeTargetUnlessSource(string(penalty, "name", "penalty"),
                            number(penalty, "penalty", number(penalty, "weight", 0.0)), targetTerms, exemptSourceTerms);
                }
            }
        }
        return builder.build();
    }

    private static Map<String, Object> toMap(RelationProfile profile) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("relationType", profile.relationType());
        out.put("sourceTypes", new ArrayList<>(profile.sourceTypes()));
        out.put("targetTypes", new ArrayList<>(profile.targetTypes()));
        out.put("relationTerms", new ArrayList<>(profile.relationTerms()));
        out.put("semanticWeight", profile.semanticWeight());
        out.put("lexicalWeight", profile.lexicalWeight());
        out.put("termWeight", profile.termWeight());
        out.put("priorWeight", profile.priorWeight());
        out.put("minScore", profile.minScore());
        out.put("topKPerSource", profile.topKPerSource());
        out.put("skipSourcesWithExistingRelation", profile.skipSourcesWithExistingRelation());
        out.put("excludeExistingPairs", profile.excludeExistingPairs());

        List<Object> boosts = new ArrayList<>();
        for (TermRule rule : profile.termRules()) {
            Map<String, Object> boost = new LinkedHashMap<>();
            boost.put("name", rule.name());
            boost.put("sourceTerms", new ArrayList<>(rule.sourceTerms()));
            boost.put("targetTerms", new ArrayList<>(rule.targetTerms()));
            boost.put("weight", rule.weight());
            boosts.add(boost);
        }
        out.put("boosts", boosts);

        List<Object> penalties = new ArrayList<>();
        for (TermPenalty penalty : profile.penalties()) {
            Map<String, Object> penaltyMap = new LinkedHashMap<>();
            penaltyMap.put("name", penalty.name());
            penaltyMap.put("targetTerms", new ArrayList<>(penalty.targetTerms()));
            penaltyMap.put("exemptSourceTerms", new ArrayList<>(penalty.exemptSourceTerms()));
            penaltyMap.put("penalty", penalty.penalty());
            penalties.add(penaltyMap);
        }
        out.put("penalties", penalties);
        return out;
    }

    private static String string(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static double number(Map<?, ?> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static boolean bool(Map<?, ?> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    private static List<Object> list(Object value) {
        List<Object> out = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                out.add(item);
            }
        }
        return out;
    }

    private static Set<String> strings(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof String s) {
            out.add(s);
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        out.removeIf(s -> s == null || s.isBlank());
        return out;
    }
}
