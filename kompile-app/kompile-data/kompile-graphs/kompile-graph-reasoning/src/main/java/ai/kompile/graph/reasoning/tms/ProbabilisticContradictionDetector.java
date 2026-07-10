/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects fuzzy contradictions in probabilistic inference output.
 *
 * <p>This complements the hard FOL/PSL contradiction checks: it looks for high posterior mass on
 * states that should be mutually exclusive for the same entity. Typical inputs are MEBN posterior
 * maps plus per-variable metadata such as {@code entityId}, {@code rvName}, {@code state}, and
 * {@code exclusiveGroup}.</p>
 */
public final class ProbabilisticContradictionDetector {

    private ProbabilisticContradictionDetector() {}

    public static List<ProbabilisticContradiction> detect(
            Map<String, Double> posteriors,
            Map<String, Map<String, String>> variableToMebnMeta) {
        return detect(posteriors, Map.of(), variableToMebnMeta, List.of(), Policy.defaults());
    }

    public static List<ProbabilisticContradiction> detect(
            Map<String, Double> posteriors,
            Map<String, Double> priors,
            Map<String, Map<String, String>> variableToMebnMeta) {
        return detect(posteriors, priors, variableToMebnMeta, List.of(), Policy.defaults());
    }

    public static List<ProbabilisticContradiction> detect(
            Map<String, Double> posteriors,
            Map<String, Double> priors,
            Map<String, Map<String, String>> variableToMebnMeta,
            Collection<MutualExclusion> mutualExclusions,
            Policy policy) {
        if (posteriors == null || posteriors.isEmpty()) {
            return List.of();
        }
        Policy effectivePolicy = policy == null ? Policy.defaults() : policy;
        Map<String, Double> safePriors = priors == null ? Map.of() : priors;
        Map<String, Map<String, String>> safeMeta = variableToMebnMeta == null ? Map.of() : variableToMebnMeta;
        List<MutualExclusion> exclusions = mutualExclusions == null ? List.of() : List.copyOf(mutualExclusions);

        List<VariableState> states = posteriors.entrySet().stream()
                .map(e -> VariableState.from(e.getKey(), e.getValue(), safePriors.get(e.getKey()), safeMeta.get(e.getKey())))
                .flatMap(Optional::stream)
                .toList();

        Map<String, List<VariableState>> byGroup = states.stream()
                .filter(s -> !s.entityId().isBlank())
                .filter(s -> !s.groupKey().isBlank())
                .collect(Collectors.groupingBy(
                        s -> s.entityId() + "\u0000" + normalize(s.groupKey()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        LinkedHashMap<String, ProbabilisticContradiction> out = new LinkedHashMap<>();
        for (List<VariableState> group : byGroup.values()) {
            double exclusiveMass = group.stream().mapToDouble(VariableState::posterior).sum();
            double entropy = normalizedEntropy(group);
            forEachPair(group, (a, b) -> {
                if (sameState(a, b)) return;
                boolean explicit = explicitlyDisjoint(a, b, exclusions);
                double joint = a.posterior() * b.posterior();
                boolean highPair = a.posterior() >= effectivePolicy.minPosterior()
                        && b.posterior() >= effectivePolicy.minPosterior()
                        && joint >= effectivePolicy.minJointConflict();
                boolean massOverflow = exclusiveMass > 1.0 + effectivePolicy.minExclusiveMassOverflow()
                        && Math.min(a.posterior(), b.posterior()) >= effectivePolicy.minPosterior() * 0.75;
                if (!explicit && !highPair && !massOverflow) return;
                put(out, a, b, joint, exclusiveMass, entropy,
                        explicit ? "EXPLICIT_DISJOINT_POSTERIOR" : "MUTUALLY_EXCLUSIVE_POSTERIOR");
            });
        }

        if (!exclusions.isEmpty()) {
            forEachPair(states, (a, b) -> {
                if (sameState(a, b) || !explicitlyDisjoint(a, b, exclusions)) return;
                double joint = a.posterior() * b.posterior();
                if (joint < effectivePolicy.minJointConflict()
                        && (a.posterior() < effectivePolicy.minPosterior() || b.posterior() < effectivePolicy.minPosterior())) {
                    return;
                }
                put(out, a, b, joint, a.posterior() + b.posterior(), normalizedEntropy(List.of(a, b)),
                        "EXPLICIT_DISJOINT_POSTERIOR");
            });
        }

        return out.values().stream()
                .sorted(Comparator
                        .comparingDouble(ProbabilisticContradiction::jointConflict)
                        .reversed()
                        .thenComparing(ProbabilisticContradiction::entityId)
                        .thenComparing(ProbabilisticContradiction::groupKey)
                        .thenComparing(ProbabilisticContradiction::stateA)
                        .thenComparing(ProbabilisticContradiction::stateB))
                .toList();
    }

    private static void put(LinkedHashMap<String, ProbabilisticContradiction> out,
                            VariableState a,
                            VariableState b,
                            double joint,
                            double exclusiveMass,
                            double entropy,
                            String reason) {
        String key = List.of(a.variable(), b.variable()).stream().sorted().collect(Collectors.joining("|"));
        out.putIfAbsent(key, new ProbabilisticContradiction(
                a.variable(),
                b.variable(),
                firstText(a.entityId(), b.entityId(), ""),
                firstText(a.groupKey(), b.groupKey(), ""),
                a.state(),
                b.state(),
                a.posterior(),
                b.posterior(),
                a.prior(),
                b.prior(),
                clamp01(joint),
                Math.max(0.0, exclusiveMass),
                clamp01(entropy),
                reason));
    }

    private static boolean explicitlyDisjoint(VariableState a, VariableState b, Collection<MutualExclusion> exclusions) {
        if (a.disjointWith().contains(normalize(b.state()))
                || b.disjointWith().contains(normalize(a.state()))
                || a.disjointWith().contains(normalize(b.variable()))
                || b.disjointWith().contains(normalize(a.variable()))) {
            return true;
        }
        for (MutualExclusion exclusion : exclusions) {
            if (exclusion.matches(a, b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameState(VariableState a, VariableState b) {
        return Objects.equals(normalize(a.state()), normalize(b.state()))
                || Objects.equals(a.variable(), b.variable());
    }

    private static double normalizedEntropy(List<VariableState> states) {
        if (states == null || states.size() <= 1) return 0.0;
        double total = states.stream().mapToDouble(VariableState::posterior).filter(v -> v > 0.0).sum();
        if (total <= 0.0) return 0.0;
        double entropy = 0.0;
        for (VariableState state : states) {
            if (state.posterior() <= 0.0) continue;
            double p = state.posterior() / total;
            entropy -= p * Math.log(p);
        }
        return entropy / Math.log(states.size());
    }

    private static Optional<ParsedVariable> parseVariable(String variable) {
        if (variable == null || variable.isBlank()) return Optional.empty();
        String text = variable.trim();
        int lp = text.indexOf('(');
        int rp = text.lastIndexOf(')');
        if (lp < 0 || rp <= lp) {
            return Optional.of(new ParsedVariable(text, List.of()));
        }
        String predicate = text.substring(0, lp).trim();
        String inside = text.substring(lp + 1, rp).trim();
        List<String> args = inside.isBlank()
                ? List.of()
                : Arrays.stream(inside.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        return Optional.of(new ParsedVariable(predicate, args));
    }

    private static Set<String> stringSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split("[,;|]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(ProbabilisticContradictionDetector::normalize)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String inferGroup(String predicate) {
        String normalized = normalize(predicate);
        if (normalized.startsWith("TYPE_") || normalized.startsWith("IS_A_")) {
            return "TYPE";
        }
        return "";
    }

    private static String inferState(String predicate) {
        String normalized = normalize(predicate);
        if (normalized.startsWith("TYPE_")) return normalized.substring("TYPE_".length());
        if (normalized.startsWith("IS_A_")) return normalized.substring("IS_A_".length());
        return predicate == null ? "" : predicate;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String meta(Map<String, String> metadata, String... keys) {
        if (metadata == null || metadata.isEmpty()) return null;
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static double posterior(Double value) {
        return clamp01(value == null ? 0.0 : value);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .replaceAll("[^A-Za-z0-9_]", "")
                .replaceAll("_+", "_")
                .toUpperCase(Locale.ROOT);
    }

    private static <T> void forEachPair(List<T> items, PairConsumer<T> consumer) {
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                consumer.accept(items.get(i), items.get(j));
            }
        }
    }

    @FunctionalInterface
    private interface PairConsumer<T> {
        void accept(T first, T second);
    }

    public record Policy(
            double minPosterior,
            double minJointConflict,
            double minExclusiveMassOverflow
    ) {
        public Policy {
            minPosterior = clamp01(minPosterior);
            minJointConflict = clamp01(minJointConflict);
            minExclusiveMassOverflow = Math.max(0.0, minExclusiveMassOverflow);
        }

        public static Policy defaults() {
            return new Policy(0.55, 0.30, 0.05);
        }
    }

    public record MutualExclusion(
            String entityId,
            String groupKey,
            String stateA,
            String stateB
    ) {
        boolean matches(VariableState a, VariableState b) {
            if (entityId != null && !entityId.isBlank()
                    && !Objects.equals(normalize(entityId), normalize(a.entityId()))) {
                return false;
            }
            if (groupKey != null && !groupKey.isBlank()
                    && !Objects.equals(normalize(groupKey), normalize(a.groupKey()))) {
                return false;
            }
            String left = normalize(stateA);
            String right = normalize(stateB);
            String aState = normalize(a.state());
            String bState = normalize(b.state());
            return (Objects.equals(left, aState) && Objects.equals(right, bState))
                    || (Objects.equals(left, bState) && Objects.equals(right, aState));
        }
    }

    public record ProbabilisticContradiction(
            String variableA,
            String variableB,
            String entityId,
            String groupKey,
            String stateA,
            String stateB,
            double posteriorA,
            double posteriorB,
            Double priorA,
            Double priorB,
            double jointConflict,
            double exclusiveMass,
            double entropy,
            String reason
    ) {}

    private record VariableState(
            String variable,
            String entityId,
            String groupKey,
            String state,
            double posterior,
            Double prior,
            Set<String> disjointWith
    ) {
        static Optional<VariableState> from(String variable,
                                            Double posterior,
                                            Double prior,
                                            Map<String, String> metadata) {
            ParsedVariable parsed = parseVariable(variable).orElse(new ParsedVariable(variable, List.of()));
            String entityId = firstText(
                    meta(metadata, "entityId", "entity", "nodeId", "subjectId"),
                    parsed.args().isEmpty() ? null : parsed.args().get(0));
            Set<String> disjointWith = stringSet(meta(
                    metadata,
                    "disjointWith",
                    "mebn.disjointWith",
                    "mutuallyExclusiveWith",
                    "owl.disjointWith"));
            String groupKey = firstText(
                    meta(metadata,
                            "exclusiveGroup",
                            "mebn.exclusiveGroup",
                            "mutuallyExclusiveGroup",
                            "typeGroup",
                            "rvName"),
                    inferGroup(parsed.predicate()),
                    disjointWith.isEmpty() ? null : "DISJOINT_SCHEMA");
            String state = firstText(
                    meta(metadata, "state", "mebn.state", "class", "type", "target", "targetId", "targetTitle"),
                    inferState(parsed.predicate()),
                    variable);

            if (entityId == null || entityId.isBlank() || groupKey == null || groupKey.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new VariableState(
                    variable,
                    entityId,
                    groupKey,
                    state == null ? variable : state,
                    ProbabilisticContradictionDetector.posterior(posterior),
                    prior == null ? null : clamp01(prior),
                    disjointWith));
        }
    }

    private record ParsedVariable(String predicate, List<String> args) {}
}
