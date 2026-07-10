/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.quantitative.ModelRetrieval.ResolvedIntervention;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Executes graph-derived quantitative plans with immutable scenario overlays.
 *
 * <p>Rule execution is selected through {@link QuantitativeRuleExecutor}; host distributions can
 * add spreadsheet or other engines without modifying this class.</p>
 */
public final class QuantitativeScenarioEngine {

    private final Map<String, QuantitativeRuleExecutor> executors;

    public QuantitativeScenarioEngine() {
        this(List.of(new ExpressionExecutor(new QuantitativeExpressionEvaluator())));
    }

    public QuantitativeScenarioEngine(List<QuantitativeRuleExecutor> executorList) {
        Map<String, QuantitativeRuleExecutor> mapped = new HashMap<>();
        if (executorList != null) {
            for (QuantitativeRuleExecutor executor : executorList) {
                for (String engineId : executor.engineIds()) {
                    mapped.put(normalizeEngineId(engineId), executor);
                }
            }
        }
        this.executors = Map.copyOf(mapped);
    }

    public ScenarioResult execute(ReasoningGraph graph, ModelRetrieval retrieval) {
        Objects.requireNonNull(graph, "graph");
        if (retrieval == null || retrieval.plan() == null) {
            return failed(null, "No executable quantitative plan was supplied.");
        }

        ModelRetrieval.Plan plan = retrieval.plan();
        Evaluation baseline = evaluate(graph, plan, List.of());
        Evaluation scenario = evaluate(graph, plan, plan.interventions());
        Double baselineTarget = baseline.values.get(plan.targetEntityId());
        Double scenarioTarget = scenario.values.get(plan.targetEntityId());

        List<String> warnings = new ArrayList<>();
        warnings.addAll(baseline.warnings);
        warnings.addAll(scenario.warnings);
        ScenarioResult.Status status;
        if (baselineTarget == null || scenarioTarget == null) {
            status = ScenarioResult.Status.FAILED;
        } else if (!warnings.isEmpty() || retrieval.status() == ModelRetrieval.Status.PARTIAL) {
            status = ScenarioResult.Status.PARTIAL;
        } else {
            status = ScenarioResult.Status.COMPLETED;
        }

        ReasoningTrace trace = scenarioTrace(plan.targetEntityId(), baseline, scenario,
                baselineTarget, scenarioTarget, status);
        return new ScenarioResult(status, plan.targetEntityId(), baselineTarget, scenarioTarget,
                baselineTarget == null || scenarioTarget == null ? null : scenarioTarget - baselineTarget,
                baseline.values, scenario.values, warnings, trace);
    }

    /**
     * Bounded scalar goal seek. When retrieval resolved a family of candidate controls (one units
     * cell per product, for example), each control is solved independently and every outcome is
     * returned as a ranked alternative; the primary result is the solved control needing the
     * smallest relative change from its baseline. More sophisticated optimizers can call the same
     * immutable overlay evaluator or be supplied by an application-level solver service.
     */
    public GoalSeekResult solveTarget(ReasoningGraph graph, ModelRetrieval retrieval) {
        Objects.requireNonNull(graph, "graph");
        if (retrieval == null || retrieval.plan() == null || retrieval.query() == null
                || retrieval.query().goal() == null) {
            return GoalSeekResult.failed("A retrieved plan and goal are required.");
        }

        ModelRetrieval.Plan plan = retrieval.plan();
        QuantitativeQuery.Goal goal = retrieval.query().goal();
        List<String> controls = plan.goalControlCandidateIds().isEmpty()
                ? (plan.goalControlEntityId() == null
                        ? List.of() : List.of(plan.goalControlEntityId()))
                : plan.goalControlCandidateIds();
        if (controls.isEmpty()) {
            return GoalSeekResult.failed("The goal control was not resolved into the model plan.");
        }

        List<GoalSeekResult> outcomes = new ArrayList<>();
        for (String controlId : controls) {
            outcomes.add(solveSingleControl(graph, plan, goal, controlId));
        }
        outcomes.sort(java.util.Comparator
                .comparing((GoalSeekResult outcome) -> outcome.status() != GoalSeekResult.Status.SOLVED)
                .thenComparingDouble(outcome -> relativeControlChange(graph, outcome)));

        List<GoalSeekResult.Alternative> alternatives = new ArrayList<>();
        for (GoalSeekResult outcome : outcomes) {
            alternatives.add(toAlternative(graph, outcome));
        }
        GoalSeekResult primary = outcomes.get(0);
        return new GoalSeekResult(primary.status(), primary.controlEntityId(),
                primary.controlValue(), primary.targetValue(), primary.achievedValue(),
                primary.iterations(), primary.warnings(), primary.scenario(), primary.trace(),
                List.copyOf(alternatives));
    }

    private double relativeControlChange(ReasoningGraph graph, GoalSeekResult outcome) {
        if (outcome.controlValue() == null || outcome.controlEntityId() == null) {
            return Double.MAX_VALUE;
        }
        OptionalDouble baseline = QuantitativeGraphSupport.numericValue(
                graph.entity(outcome.controlEntityId()).orElse(null));
        if (baseline.isEmpty()) {
            return Math.abs(outcome.controlValue());
        }
        double reference = Math.max(1.0, Math.abs(baseline.getAsDouble()));
        return Math.abs(outcome.controlValue() - baseline.getAsDouble()) / reference;
    }

    private GoalSeekResult.Alternative toAlternative(
            ReasoningGraph graph, GoalSeekResult outcome) {
        GraphEntity control = outcome.controlEntityId() == null
                ? null : graph.entity(outcome.controlEntityId()).orElse(null);
        OptionalDouble baseline = QuantitativeGraphSupport.numericValue(control);
        return new GoalSeekResult.Alternative(
                outcome.controlEntityId(),
                control == null ? null : control.label(),
                outcome.status(),
                baseline.isPresent() ? baseline.getAsDouble() : null,
                outcome.controlValue(),
                outcome.achievedValue(),
                outcome.iterations());
    }

    private GoalSeekResult solveSingleControl(
            ReasoningGraph graph,
            ModelRetrieval.Plan plan,
            QuantitativeQuery.Goal goal,
            String controlId) {
        List<ResolvedIntervention> fixed = plan.interventions().stream()
                .filter(intervention -> !intervention.entityId().equals(controlId))
                .toList();
        Evaluation baseline = evaluate(graph, plan, fixed);
        Evaluation lower = evaluate(graph, plan, withControl(fixed, controlId, goal.minimum()));
        Evaluation upper = evaluate(graph, plan, withControl(fixed, controlId, goal.maximum()));
        Double lowerValue = lower.values.get(plan.targetEntityId());
        Double upperValue = upper.values.get(plan.targetEntityId());
        if (lowerValue == null || upperValue == null) {
            return GoalSeekResult.failed("The target could not be evaluated at the goal bounds.");
        }

        double lowerResidual = lowerValue - goal.targetValue();
        double upperResidual = upperValue - goal.targetValue();
        if (Math.abs(lowerResidual) <= goal.tolerance()) {
            return solvedGoal(plan, baseline, lower, controlId, goal.minimum(),
                    goal.targetValue(), 0);
        }
        if (Math.abs(upperResidual) <= goal.tolerance()) {
            return solvedGoal(plan, baseline, upper, controlId, goal.maximum(),
                    goal.targetValue(), 0);
        }
        if (Math.signum(lowerResidual) == Math.signum(upperResidual)) {
            ReasoningTrace trace = optimizationTrace(plan, controlId, goal, null, 0,
                    "Target is not bracketed by the control bounds.", lower, upper);
            return new GoalSeekResult(GoalSeekResult.Status.INFEASIBLE, controlId, null,
                    goal.targetValue(), null, 0,
                    List.of("Target is not bracketed by the requested control bounds."), null, trace);
        }

        double low = goal.minimum();
        double high = goal.maximum();
        Evaluation candidate = null;
        double control = Double.NaN;
        int iterations = 0;
        for (; iterations < goal.maxIterations(); iterations++) {
            control = low + (high - low) / 2.0;
            candidate = evaluate(graph, plan, withControl(fixed, controlId, control));
            Double value = candidate.values.get(plan.targetEntityId());
            if (value == null) {
                return GoalSeekResult.failed("The target became unevaluable during goal seek.");
            }
            double residual = value - goal.targetValue();
            if (Math.abs(residual) <= goal.tolerance()
                    || Math.abs(high - low) <= goal.tolerance()) {
                break;
            }
            if (Math.signum(residual) == Math.signum(lowerResidual)) {
                low = control;
                lowerResidual = residual;
            } else {
                high = control;
                upperResidual = residual;
            }
        }

        if (candidate == null) {
            return GoalSeekResult.failed("Goal seek did not evaluate a candidate.");
        }
        return solvedGoal(plan, baseline, candidate, controlId, control,
                goal.targetValue(), iterations + 1);
    }

    private GoalSeekResult solvedGoal(
            ModelRetrieval.Plan plan,
            Evaluation baseline,
            Evaluation candidate,
            String controlId,
            double controlValue,
            double targetValue,
            int iterations) {
        Double baselineTarget = baseline.values.get(plan.targetEntityId());
        Double achieved = candidate.values.get(plan.targetEntityId());
        List<String> warnings = new ArrayList<>();
        warnings.addAll(baseline.warnings);
        warnings.addAll(candidate.warnings);
        ScenarioResult scenario = new ScenarioResult(
                warnings.isEmpty() ? ScenarioResult.Status.COMPLETED : ScenarioResult.Status.PARTIAL,
                plan.targetEntityId(), baselineTarget, achieved,
                baselineTarget == null || achieved == null ? null : achieved - baselineTarget,
                baseline.values, candidate.values, warnings,
                scenarioTrace(plan.targetEntityId(), baseline, candidate, baselineTarget, achieved,
                        warnings.isEmpty() ? ScenarioResult.Status.COMPLETED : ScenarioResult.Status.PARTIAL));
        ReasoningTrace trace = optimizationTrace(plan, controlId, null, controlValue, iterations,
                "Solved bounded scalar target.", baseline, candidate);
        return new GoalSeekResult(GoalSeekResult.Status.SOLVED, controlId, controlValue,
                targetValue, achieved, iterations, warnings, scenario, trace);
    }

    private Evaluation evaluate(
            ReasoningGraph graph,
            ModelRetrieval.Plan plan,
            List<ResolvedIntervention> overlay) {
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, ReasoningTrace.Step> valueSteps = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        Set<String> seedIds = new LinkedHashSet<>(plan.leafEntityIds());
        for (QuantitativeRule rule : plan.rules()) {
            for (QuantitativeRule.Input input : rule.inputs()) {
                if (graph.entity(input.entityId()).flatMap(entity -> {
                    OptionalDouble value = QuantitativeGraphSupport.numericValue(entity);
                    return value.isPresent() ? java.util.Optional.of(value.getAsDouble())
                            : java.util.Optional.empty();
                }).isPresent()) {
                    seedIds.add(input.entityId());
                }
            }
        }
        for (String entityId : seedIds) {
            GraphEntity entity = graph.entity(entityId).orElse(null);
            OptionalDouble value = QuantitativeGraphSupport.numericValue(entity);
            if (value.isPresent()) {
                values.put(entityId, value.getAsDouble());
                valueSteps.put(entityId, factStep(entity, value.getAsDouble()));
            }
        }

        Set<String> overridden = new LinkedHashSet<>();
        for (ResolvedIntervention intervention : overlay) {
            Double before = values.get(intervention.entityId());
            Double after = apply(intervention, before, warnings);
            if (after == null) {
                continue;
            }
            values.put(intervention.entityId(), after);
            overridden.add(intervention.entityId());
            ReasoningTrace.Step premise = valueSteps.get(intervention.entityId());
            valueSteps.put(intervention.entityId(), new ReasoningTrace.Step(
                    ReasoningTrace.StepKind.ASSUMPTION,
                    intervention.entityId() + " = " + format(after),
                    intervention.operation().name().toLowerCase(Locale.ROOT),
                    1.0, "scenario-overlay",
                    premise == null ? List.of() : List.of(premise), null,
                    Map.of("requestedTarget", String.valueOf(intervention.requestedTarget()),
                            "inputValue", format(intervention.value()),
                            "priorValue", before == null ? "missing" : format(before))));
        }

        for (QuantitativeRule rule : plan.rules()) {
            if (overridden.contains(rule.outputEntityId())) {
                continue;
            }
            List<ReasoningTrace.Step> premises = new ArrayList<>();
            boolean missing = false;
            for (QuantitativeRule.Input input : rule.inputs()) {
                if (!values.containsKey(input.entityId())) {
                    if (input.required()) {
                        warnings.add("Missing input " + input.entityId() + " for rule " + rule.id());
                        missing = true;
                    }
                } else if (valueSteps.containsKey(input.entityId())) {
                    premises.add(valueSteps.get(input.entityId()));
                }
            }
            if (missing) {
                continue;
            }

            QuantitativeRuleExecutor executor = executors.get(normalizeEngineId(rule.engineId()));
            if (executor == null) {
                warnings.add("No executor is registered for engine " + rule.engineId()
                        + " used by rule " + rule.id());
                continue;
            }
            Map<String, Double> variables = variables(graph, values, rule);
            try {
                double output = executor.execute(rule, variables);
                if (!Double.isFinite(output)) {
                    throw new IllegalArgumentException("non-finite result");
                }
                values.put(rule.outputEntityId(), output);
                double confidence = premises.stream()
                        .mapToDouble(ReasoningTrace.Step::confidence)
                        .min().orElse(1.0);
                confidence = Math.min(confidence,
                        Math.min(rule.confidence(), rule.validationScore()));
                ReasoningTrace.Step step = new ReasoningTrace.Step(
                        ReasoningTrace.StepKind.CALCULATION,
                        rule.outputEntityId() + " = " + format(output),
                        rule.expression(), QuantitativeGraphSupport.clamp01(confidence),
                        rule.sourceEntityId(), premises, null,
                        Map.of("ruleId", rule.id(), "engineId", rule.engineId(),
                                "unit", rule.unit() == null ? "" : rule.unit(),
                                "value", format(output)));
                valueSteps.put(rule.outputEntityId(), step);
            } catch (RuntimeException e) {
                warnings.add("Rule " + rule.id() + " failed: " + e.getMessage());
            }
        }

        ReasoningTrace.Step root = valueSteps.get(plan.targetEntityId());
        if (root == null) {
            root = new ReasoningTrace.Step(
                    ReasoningTrace.StepKind.REBUTTAL,
                    "Target " + plan.targetEntityId() + " was not calculated",
                    "quantitative_execution_gap", 1.0, null,
                    List.copyOf(valueSteps.values()), null,
                    Map.of("warningCount", String.valueOf(warnings.size())));
        }
        return new Evaluation(Map.copyOf(values), List.copyOf(warnings), ReasoningTrace.of(root));
    }

    private static Map<String, Double> variables(
            ReasoningGraph graph,
            Map<String, Double> values,
            QuantitativeRule rule) {
        Map<String, Double> variables = new LinkedHashMap<>();
        for (Map.Entry<String, Double> value : values.entrySet()) {
            GraphEntity entity = graph.entity(value.getKey()).orElse(null);
            variables.put(QuantitativeGraphSupport.canonicalIdentifier(value.getKey()), value.getValue());
            if (entity != null) {
                for (String alias : QuantitativeGraphSupport.aliases(entity)) {
                    variables.put(alias, value.getValue());
                }
            }
        }
        // Spreadsheet formulas reference same-sheet cells without qualification ("G4*J4"), while
        // input aliases are sheet-qualified ("DETAIL!G4"). Bind the bare reference too, but only
        // for inputs on the rule's own sheet, matching spreadsheet resolution semantics.
        String ruleSheet = ruleSheet(graph, rule);
        for (QuantitativeRule.Input input : rule.inputs()) {
            Double value = values.get(input.entityId());
            if (value == null) {
                continue;
            }
            String alias = QuantitativeGraphSupport.canonicalIdentifier(input.alias());
            variables.put(alias, value);
            int separator = alias.lastIndexOf('!');
            if (separator > 0 && separator + 1 < alias.length() && ruleSheet != null
                    && alias.substring(0, separator).equals(ruleSheet)) {
                variables.put(alias.substring(separator + 1), value);
            }
        }
        return variables;
    }

    private static String ruleSheet(ReasoningGraph graph, QuantitativeRule rule) {
        GraphEntity source = graph.entity(rule.sourceEntityId()).orElse(null);
        String sheet = QuantitativeGraphSupport.firstString(
                source, "sheetName", "sheet_name", "sheet");
        return sheet == null ? null : QuantitativeGraphSupport.canonicalIdentifier(sheet);
    }

    private static Double apply(
            ResolvedIntervention intervention,
            Double baseline,
            List<String> warnings) {
        return switch (intervention.operation()) {
            case SET -> intervention.value();
            case ADD -> {
                if (baseline == null) {
                    warnings.add("ADD intervention requires a baseline for " + intervention.entityId());
                    yield null;
                }
                yield baseline + intervention.value();
            }
            case SCALE -> {
                if (baseline == null) {
                    warnings.add("SCALE intervention requires a baseline for " + intervention.entityId());
                    yield null;
                }
                yield baseline * (1.0 + intervention.value());
            }
        };
    }

    private static ReasoningTrace.Step factStep(GraphEntity entity, double value) {
        String id = entity == null ? "unknown" : entity.id();
        String source = entity == null
                ? "graph" : firstNonBlank(
                        QuantitativeGraphSupport.firstString(entity, "source", "sourcePath", "provenance"),
                        entity.id());
        String unit = entity == null ? null : QuantitativeGraphSupport.unit(entity);
        return new ReasoningTrace.Step(
                ReasoningTrace.StepKind.FACT,
                id + " = " + format(value),
                "graph_observation",
                entity == null ? 0.0 : entity.confidence(),
                source, List.of(), null,
                Map.of("entityId", id, "value", format(value),
                        "unit", unit == null ? "" : unit));
    }

    private static ReasoningTrace scenarioTrace(
            String targetId,
            Evaluation baseline,
            Evaluation scenario,
            Double baselineValue,
            Double scenarioValue,
            ScenarioResult.Status status) {
        List<ReasoningTrace.Step> premises = List.of(
                new ReasoningTrace.Step(
                        ReasoningTrace.StepKind.CALCULATION,
                        "baseline " + targetId + " = " + nullable(baselineValue),
                        "baseline_evaluation",
                        baselineValue == null ? 0.0 : baseline.trace.conclusion().confidence(),
                        "graph", List.of(baseline.trace.conclusion()), null, Map.of()),
                new ReasoningTrace.Step(
                        ReasoningTrace.StepKind.CALCULATION,
                        "scenario " + targetId + " = " + nullable(scenarioValue),
                        "scenario_overlay_evaluation",
                        scenarioValue == null ? 0.0 : scenario.trace.conclusion().confidence(),
                        "graph", List.of(scenario.trace.conclusion()), null, Map.of()));
        double confidence = premises.stream().mapToDouble(ReasoningTrace.Step::confidence)
                .min().orElse(0.0);
        return ReasoningTrace.of(ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.CALCULATION,
                "Scenario " + status + " for " + targetId,
                "quantitative_scenario",
                confidence, null,
                Map.of("status", status.name(),
                        "baseline", nullable(baselineValue),
                        "scenario", nullable(scenarioValue)),
                premises));
    }

    private static ReasoningTrace optimizationTrace(
            ModelRetrieval.Plan plan,
            String controlId,
            QuantitativeQuery.Goal goal,
            Double controlValue,
            int iterations,
            String conclusion,
            Evaluation... evaluations) {
        List<ReasoningTrace.Step> premises = new ArrayList<>();
        for (Evaluation evaluation : evaluations) {
            premises.add(evaluation.trace.conclusion());
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("targetEntityId", plan.targetEntityId());
        meta.put("controlEntityId", controlId);
        meta.put("controlValue", controlValue == null ? "unresolved" : format(controlValue));
        meta.put("iterations", String.valueOf(iterations));
        if (goal != null) {
            meta.put("targetValue", format(goal.targetValue()));
            meta.put("minimum", format(goal.minimum()));
            meta.put("maximum", format(goal.maximum()));
        }
        return ReasoningTrace.of(ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.OPTIMIZATION,
                conclusion,
                "bounded_scalar_goal_seek",
                controlValue == null ? 0.0 : 1.0,
                null, meta, premises));
    }

    private static List<ResolvedIntervention> withControl(
            List<ResolvedIntervention> fixed, String controlId, double value) {
        List<ResolvedIntervention> result = new ArrayList<>(fixed);
        result.add(new ResolvedIntervention(
                controlId, QuantitativeQuery.Operation.SET, value, "goalControl"));
        return List.copyOf(result);
    }

    private static ScenarioResult failed(String targetId, String warning) {
        ReasoningTrace trace = ReasoningTrace.of(new ReasoningTrace.Step(
                ReasoningTrace.StepKind.REBUTTAL,
                warning, "quantitative_execution_gap", 1.0,
                null, List.of(), null, Map.of()));
        return new ScenarioResult(ScenarioResult.Status.FAILED, targetId,
                null, null, null, Map.of(), Map.of(), List.of(warning), trace);
    }

    private static String normalizeEngineId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String nullable(Double value) {
        return value == null ? "unavailable" : format(value);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.10g", value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!QuantitativeGraphSupport.blank(value)) {
                return value;
            }
        }
        return "graph";
    }

    private record Evaluation(
            Map<String, Double> values,
            List<String> warnings,
            ReasoningTrace trace) {
    }

    private static final class ExpressionExecutor implements QuantitativeRuleExecutor {
        private final QuantitativeExpressionEvaluator evaluator;

        private ExpressionExecutor(QuantitativeExpressionEvaluator evaluator) {
            this.evaluator = evaluator;
        }

        @Override
        public Set<String> engineIds() {
            return Set.of("expression", "excel-formula", "spreadsheet-formula");
        }

        @Override
        public double execute(QuantitativeRule rule, Map<String, Double> variables) {
            return evaluator.evaluate(rule.expression(), variables);
        }
    }

    public record GoalSeekResult(
            Status status,
            String controlEntityId,
            Double controlValue,
            Double targetValue,
            Double achievedValue,
            int iterations,
            List<String> warnings,
            ScenarioResult scenario,
            ReasoningTrace trace,
            List<Alternative> alternatives) {

        public GoalSeekResult {
            status = status == null ? Status.FAILED : status;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        public GoalSeekResult(
                Status status,
                String controlEntityId,
                Double controlValue,
                Double targetValue,
                Double achievedValue,
                int iterations,
                List<String> warnings,
                ScenarioResult scenario,
                ReasoningTrace trace) {
            this(status, controlEntityId, controlValue, targetValue, achievedValue,
                    iterations, warnings, scenario, trace, List.of());
        }

        /** One solved (or attempted) control instance from a multi-candidate goal seek. */
        public record Alternative(
                String controlEntityId,
                String controlLabel,
                Status status,
                Double baselineControlValue,
                Double controlValue,
                Double achievedValue,
                int iterations) {
        }

        public static GoalSeekResult failed(String warning) {
            ReasoningTrace trace = ReasoningTrace.of(new ReasoningTrace.Step(
                    ReasoningTrace.StepKind.REBUTTAL,
                    warning, "goal_seek_gap", 1.0, null,
                    List.of(), null, Map.of()));
            return new GoalSeekResult(Status.FAILED, null, null, null, null,
                    0, List.of(warning), null, trace);
        }

        public enum Status {
            SOLVED,
            INFEASIBLE,
            FAILED
        }
    }
}
