/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects graph-resident formula and calculation entities into executable rules.
 *
 * <p>Both explicit rule vocabulary and the spreadsheet crawler's existing FORMULA_CELL +
 * DEPENDS_ON representation are supported. Relation labels are normalized, so stores may use
 * spaces, hyphens, or underscores without source-specific adapters.</p>
 */
public final class GraphQuantitativeRuleCatalog implements QuantitativeRuleCatalog {

    private static final Set<String> RULE_TYPES = Set.of(
            "CALCULATION_RULE", "FORMULA", "FORMULA_CELL", "AGGREGATION_RULE",
            "CONVERSION_RULE", "QUANTITATIVE_RULE", "METRIC_DEFINITION");

    private static final Set<String> OUTPUT_RELATIONS = Set.of(
            "PRODUCES", "OUTPUT", "OUTPUTS", "HAS_OUTPUT", "COMPUTES", "CALCULATES");

    private static final Set<String> INPUT_RELATIONS = Set.of(
            "REQUIRES", "INPUT", "HAS_INPUT", "DEPENDS_ON", "RANGE_INPUT",
            "CROSS_SHEET_DEPENDS_ON", "NAMED_RANGE_INPUT");

    private static final Set<String> INCOMING_INPUT_RELATIONS = Set.of(
            "INPUT_TO", "FEEDS", "FEEDS_INTO", "USED_BY");

    private static final Set<String> COMPUTED_BY_RELATIONS = Set.of(
            "COMPUTED_BY", "CALCULATED_BY", "PRODUCED_BY_RULE");

    @Override
    public List<QuantitativeRule> rules(ReasoningGraph graph) {
        Objects.requireNonNull(graph, "graph");
        List<QuantitativeRule> rules = new ArrayList<>();
        for (GraphEntity entity : graph.entities()) {
            if (!isRuleEntity(entity)) {
                continue;
            }
            String expression = expression(graph, entity);
            if (QuantitativeGraphSupport.blank(expression)) {
                continue;
            }
            List<String> outputs = outputEntityIds(graph, entity);
            if (outputs.isEmpty() && isFormulaCell(entity)) {
                outputs = List.of(entity.id());
            }
            if (outputs.isEmpty()) {
                continue;
            }

            List<QuantitativeRule.Input> inputs = inputs(graph, entity);
            for (String outputId : outputs) {
                GraphEntity output = graph.entity(outputId).orElse(null);
                if (output == null) {
                    continue;
                }
                Map<String, String> dimensions = new LinkedHashMap<>(
                        QuantitativeGraphSupport.dimensions(output));
                dimensions.putAll(QuantitativeGraphSupport.dimensions(entity));
                String unit = firstNonBlank(
                        QuantitativeGraphSupport.firstString(entity, "unit", "unitCode", "quantityUnit"),
                        QuantitativeGraphSupport.unit(output));
                String engineId = firstNonBlank(
                        QuantitativeGraphSupport.firstString(entity, "engineId", "executorId", "engine"),
                        isFormulaCell(entity) ? "excel-formula" : "expression");
                String kind = firstNonBlank(
                        QuantitativeGraphSupport.firstString(entity, "ruleKind", "kind"),
                        QuantitativeGraphSupport.normalizeType(entity.type()));
                double confidence = Math.min(entity.confidence(), output.confidence());
                String ruleId = outputs.size() == 1 && outputId.equals(entity.id())
                        ? entity.id() : entity.id() + "->" + outputId;
                rules.add(new QuantitativeRule(
                        ruleId,
                        entity.id(),
                        kind,
                        outputId,
                        inputs,
                        expression,
                        engineId,
                        unit,
                        dimensions,
                        confidence,
                        validationScore(entity),
                        entity.attributes()));
            }
        }
        rules.sort(Comparator.comparing(QuantitativeRule::id));
        return List.copyOf(rules);
    }

    private static boolean isRuleEntity(GraphEntity entity) {
        if (!QuantitativeGraphSupport.blank(
                QuantitativeGraphSupport.firstString(entity, "expression", "formula", "calculation"))) {
            return true;
        }
        for (String type : entity.typeMemberships()) {
            if (RULE_TYPES.contains(QuantitativeGraphSupport.normalizeType(type))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFormulaCell(GraphEntity entity) {
        return entity.typeMemberships().stream()
                .map(QuantitativeGraphSupport::normalizeType)
                .anyMatch(type -> type.equals("FORMULA_CELL") || type.equals("FORMULA"));
    }

    private static String expression(ReasoningGraph graph, GraphEntity rule) {
        String direct = QuantitativeGraphSupport.firstString(
                rule, "expression", "formula", "calculation", "rule.expression");
        if (!QuantitativeGraphSupport.blank(direct)) {
            return direct;
        }
        for (GraphRelation relation : graph.relationsOf(rule.id())) {
            String formula = relation.stringAttribute("formula");
            if (!QuantitativeGraphSupport.blank(formula)) {
                return formula;
            }
        }
        return null;
    }

    private static List<String> outputEntityIds(ReasoningGraph graph, GraphEntity rule) {
        Set<String> outputs = new LinkedHashSet<>();
        addEntityIds(outputs, rule.attributes().get("outputEntityId"));
        addEntityIds(outputs, rule.attributes().get("outputEntityIds"));
        addEntityIds(outputs, rule.attributes().get("outputs"));

        for (GraphRelation relation : graph.outgoing(rule.id())) {
            if (OUTPUT_RELATIONS.contains(QuantitativeGraphSupport.normalizeType(relation.type()))) {
                outputs.add(relation.targetId());
            }
        }
        for (GraphRelation relation : graph.incoming(rule.id())) {
            if (COMPUTED_BY_RELATIONS.contains(
                    QuantitativeGraphSupport.normalizeType(relation.type()))) {
                outputs.add(relation.sourceId());
            }
        }
        return List.copyOf(outputs);
    }

    private static List<QuantitativeRule.Input> inputs(ReasoningGraph graph, GraphEntity rule) {
        Map<String, QuantitativeRule.Input> byEntity = new LinkedHashMap<>();

        Object rawInputs = rule.attributes().get("inputs");
        if (rawInputs instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    String entityId = String.valueOf(entry.getValue());
                    byEntity.put(entityId, new QuantitativeRule.Input(
                            QuantitativeGraphSupport.canonicalIdentifier(String.valueOf(entry.getKey())),
                            entityId, true));
                }
            }
        } else {
            Set<String> ids = new LinkedHashSet<>();
            addEntityIds(ids, rawInputs);
            addEntityIds(ids, rule.attributes().get("inputEntityIds"));
            for (String entityId : ids) {
                GraphEntity input = graph.entity(entityId).orElse(null);
                String alias = input == null
                        ? QuantitativeGraphSupport.canonicalIdentifier(entityId)
                        : QuantitativeGraphSupport.preferredAlias(input);
                byEntity.put(entityId, new QuantitativeRule.Input(alias, entityId, true));
            }
        }

        for (GraphRelation relation : graph.outgoing(rule.id())) {
            if (!INPUT_RELATIONS.contains(QuantitativeGraphSupport.normalizeType(relation.type()))) {
                continue;
            }
            addRelationInput(graph, byEntity, relation, relation.targetId());
        }
        for (GraphRelation relation : graph.incoming(rule.id())) {
            if (!INCOMING_INPUT_RELATIONS.contains(
                    QuantitativeGraphSupport.normalizeType(relation.type()))) {
                continue;
            }
            addRelationInput(graph, byEntity, relation, relation.sourceId());
        }
        return List.copyOf(byEntity.values());
    }

    private static void addRelationInput(
            ReasoningGraph graph,
            Map<String, QuantitativeRule.Input> byEntity,
            GraphRelation relation,
            String entityId) {
        GraphEntity input = graph.entity(entityId).orElse(null);
        String alias = firstNonBlank(
                relation.stringAttribute("alias"),
                relation.stringAttribute("inputAlias"),
                relation.stringAttribute("symbol"),
                input == null ? null : QuantitativeGraphSupport.preferredAlias(input),
                QuantitativeGraphSupport.canonicalIdentifier(entityId));
        boolean required = !(relation.attributes().get("required") instanceof Boolean value) || value;
        byEntity.putIfAbsent(entityId, new QuantitativeRule.Input(alias, entityId, required));
    }

    private static double validationScore(GraphEntity rule) {
        Object validated = rule.attributes().get("validated");
        if (validated instanceof Boolean value) {
            return value ? 1.0 : 0.0;
        }
        String status = QuantitativeGraphSupport.firstString(
                rule, "validationStatus", "validation_status", "replayStatus");
        if (status != null) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (Set.of("VERIFIED", "VALID", "PASSED", "PASS", "REPLAYED").contains(normalized)) {
                return 1.0;
            }
            if (Set.of("FAILED", "INVALID", "REJECTED").contains(normalized)) {
                return 0.0;
            }
        }
        for (String key : List.of("replayError", "replay_error", "validationError")) {
            Object raw = rule.attributes().get(key);
            var error = QuantitativeGraphSupport.parseNumber(raw);
            if (error.isPresent()) {
                return 1.0 / (1.0 + Math.abs(error.getAsDouble()));
            }
        }
        return 0.5;
    }

    private static void addEntityIds(Set<String> output, Object raw) {
        if (raw instanceof String value) {
            if (!value.isBlank()) {
                output.add(value.trim());
            }
        } else if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    output.add(String.valueOf(item).trim());
                }
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    output.add(String.valueOf(item).trim());
                }
            }
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!QuantitativeGraphSupport.blank(value)) {
                return value;
            }
        }
        return null;
    }
}
