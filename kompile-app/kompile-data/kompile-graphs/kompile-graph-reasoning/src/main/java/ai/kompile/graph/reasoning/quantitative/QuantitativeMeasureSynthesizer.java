/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Synthesizes aggregate ratio measures that source documents define at one grain but never
 * compute at another.
 *
 * <p>Step one learns label-level ratio templates from formulas already in the graph: a rule whose
 * expression is a plain division and whose output/input row labels are distinct teaches, for
 * example, {@code Gross margin % = Gross profit / Net revenue}. Templates require at least two
 * supporting formula instances.</p>
 *
 * <p>Step two finds row grids that carry the ratio and its denominator per row (a SKU table with
 * per-row margin % and revenue) but no aggregate. Because {@code ratio = numerator / denominator}
 * implies {@code numerator_i = ratio_i * denominator_i}, the aggregate follows algebraically as
 * the denominator-weighted rollup {@code sum(ratio_i * denom_i) / sum(denom_i)}. Each rollup is
 * materialized into the graph as an executable rule entity with full provenance, so retrieval,
 * scenario execution, and traces treat it like any other graph-resident model.</p>
 *
 * <p>No domain vocabulary is built in: templates come from graph formulas, and column matching is
 * token-based with generic abbreviation ("rev" ~ "revenue") and acronym ("GM" ~ "gross margin")
 * rules.</p>
 */
public final class QuantitativeMeasureSynthesizer {

    private static final Pattern SIMPLE_DIVISION = Pattern.compile(
            "^\\s*([^\\s()+*/,-]+)\\s*/\\s*([^\\s()+*/,-]+)\\s*$");
    private static final int MINIMUM_TEMPLATE_SUPPORT = 2;
    private static final int MINIMUM_PAIRS = 2;
    private static final double SYNTHESIZED_CONFIDENCE = 0.85;

    private final QuantitativeRuleCatalog catalog;

    public QuantitativeMeasureSynthesizer() {
        this(new GraphQuantitativeRuleCatalog());
    }

    public QuantitativeMeasureSynthesizer(QuantitativeRuleCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public record SynthesizedMeasure(
            String entityId,
            String label,
            String workbook,
            String sheet,
            int pairCount,
            String templateOutputLabel) {
    }

    public List<SynthesizedMeasure> synthesize(MutableReasoningGraph graph) {
        Objects.requireNonNull(graph, "graph");
        List<QuantitativeRule> rules = catalog.rules(graph);
        List<RatioTemplate> templates = learnRatioTemplates(graph, rules);
        if (templates.isEmpty()) {
            return List.of();
        }

        Map<String, List<GraphEntity>> cellsBySheet = new LinkedHashMap<>();
        for (GraphEntity entity : graph.entities()) {
            if (!gridCell(entity)) {
                continue;
            }
            cellsBySheet.computeIfAbsent(sheetKey(entity), ignored -> new ArrayList<>())
                    .add(entity);
        }

        List<SynthesizedMeasure> result = new ArrayList<>();
        for (Map.Entry<String, List<GraphEntity>> sheetEntry : cellsBySheet.entrySet()) {
            Map<String, Map<Integer, GraphEntity>> byColumn = new LinkedHashMap<>();
            for (GraphEntity cell : sheetEntry.getValue()) {
                String columnLabel = QuantitativeGraphSupport.firstString(cell, "columnLabel");
                Integer row = rowIndex(cell);
                if (columnLabel == null || row == null) {
                    continue;
                }
                byColumn.computeIfAbsent(columnLabel, ignored -> new TreeMap<>())
                        .putIfAbsent(row, cell);
            }
            for (RatioTemplate template : templates) {
                result.addAll(synthesizeForSheet(graph, template, byColumn));
            }
        }
        return List.copyOf(result);
    }

    private List<SynthesizedMeasure> synthesizeForSheet(
            MutableReasoningGraph graph,
            RatioTemplate template,
            Map<String, Map<Integer, GraphEntity>> byColumn) {
        List<SynthesizedMeasure> result = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, GraphEntity>> ratioColumn : byColumn.entrySet()) {
            if (!columnMatches(ratioColumn.getKey(), template.outputLabel())) {
                continue;
            }
            Set<String> ratioTemporal =
                    QuantitativeGraphSupport.temporalTokens(ratioColumn.getKey());
            for (Map.Entry<String, Map<Integer, GraphEntity>> denominatorColumn
                    : byColumn.entrySet()) {
                if (denominatorColumn.getKey().equals(ratioColumn.getKey())
                        || !columnMatches(denominatorColumn.getKey(), template.denominatorLabel())
                        || !ratioTemporal.equals(QuantitativeGraphSupport.temporalTokens(
                                denominatorColumn.getKey()))) {
                    continue;
                }
                result.addAll(materialize(graph, template,
                        ratioColumn.getKey(), ratioColumn.getValue(),
                        denominatorColumn.getKey(), denominatorColumn.getValue()));
            }
        }
        return result;
    }

    private List<SynthesizedMeasure> materialize(
            MutableReasoningGraph graph,
            RatioTemplate template,
            String ratioColumnLabel,
            Map<Integer, GraphEntity> ratioCells,
            String denominatorColumnLabel,
            Map<Integer, GraphEntity> denominatorCells) {
        List<GraphEntity> ratio = new ArrayList<>();
        List<GraphEntity> denominator = new ArrayList<>();
        for (Map.Entry<Integer, GraphEntity> entry : ratioCells.entrySet()) {
            GraphEntity denominatorCell = denominatorCells.get(entry.getKey());
            if (denominatorCell == null || !valueBacked(entry.getValue())
                    || !valueBacked(denominatorCell)) {
                continue;
            }
            ratio.add(entry.getValue());
            denominator.add(denominatorCell);
        }
        if (ratio.size() < MINIMUM_PAIRS) {
            return List.of();
        }

        Map<String, String> inputs = new LinkedHashMap<>();
        StringBuilder numeratorSum = new StringBuilder();
        StringBuilder denominatorSum = new StringBuilder();
        for (int i = 0; i < ratio.size(); i++) {
            String ratioAlias = "R" + i;
            String denominatorAlias = "D" + i;
            inputs.put(ratioAlias, ratio.get(i).id());
            inputs.put(denominatorAlias, denominator.get(i).id());
            if (i > 0) {
                numeratorSum.append(" + ");
                denominatorSum.append(" + ");
            }
            numeratorSum.append(ratioAlias).append(" * ").append(denominatorAlias);
            denominatorSum.append(denominatorAlias);
        }

        List<SynthesizedMeasure> measures = new ArrayList<>();
        // The ratio itself, aggregated as a denominator-weighted rollup.
        measures.add(materializeEntity(graph, template, ratioColumnLabel, denominatorColumnLabel,
                ratio, denominator, inputs, template.outputLabel(),
                "(" + numeratorSum + ") / (" + denominatorSum + ")",
                "ratio-denominator-weighted-rollup"));
        // The implied numerator aggregate: ratio = numerator / denominator means each row's
        // numerator is ratio_i * denominator_i, and the aggregate is their plain sum. This
        // recovers absolute measures (gross profit dollars) that source grids never total.
        measures.add(materializeEntity(graph, template, ratioColumnLabel, denominatorColumnLabel,
                ratio, denominator, inputs, template.numeratorLabel(),
                numeratorSum.toString(),
                "ratio-numerator-sum"));
        return measures;
    }

    private SynthesizedMeasure materializeEntity(
            MutableReasoningGraph graph,
            RatioTemplate template,
            String ratioColumnLabel,
            String denominatorColumnLabel,
            List<GraphEntity> ratio,
            List<GraphEntity> denominator,
            Map<String, String> inputs,
            String measureLabel,
            String expression,
            String synthesisMethod) {
        GraphEntity sample = ratio.get(0);
        String workbook = workbook(sample);
        String sheet = QuantitativeGraphSupport.firstString(
                sample, "sheetName", "sheet_name", "sheet");
        String id = "synth:measure:" + QuantitativeGraphSupport.canonicalIdentifier(
                workbook + "!" + sheet + "!" + ratioColumnLabel + "!" + measureLabel)
                .toLowerCase(Locale.ROOT);
        String label = measureLabel + " (weighted rollup) - "
                + (sheet == null ? "" : sheet) + " " + ratioColumnLabel;

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("expression", expression);
        attributes.put("inputs", inputs);
        attributes.put("outputEntityId", id);
        attributes.put("engineId", "expression");
        attributes.put("ruleKind", "SYNTHESIZED_MEASURE");
        attributes.put("semanticLabel", label);
        if (sheet != null) {
            attributes.put("sheetName", sheet);
        }
        if (!workbook.isBlank()) {
            attributes.put("workbook", workbook);
        }
        attributes.put("synthesisMethod", synthesisMethod);
        attributes.put("templateSupport", template.support());
        attributes.put("pairCount", ratio.size());
        // Provenance labels live in a nested map: they must stay inspectable but must NOT join
        // lexical search text, or the ratio and numerator siblings (which share one template)
        // would become indistinguishable to measure resolution.
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put("templateOutputLabel", template.outputLabel());
        provenance.put("templateNumeratorLabel", template.numeratorLabel());
        provenance.put("templateDenominatorLabel", template.denominatorLabel());
        provenance.put("templateSourceRules", String.join(";", template.sourceRuleIds()));
        provenance.put("ratioColumnLabel", ratioColumnLabel);
        provenance.put("denominatorColumnLabel", denominatorColumnLabel);
        attributes.put("synthesis", provenance);

        Map<String, String> dimensions = new LinkedHashMap<>();
        if (sheet != null) {
            dimensions.put("sheet", sheet);
        }
        if (!workbook.isBlank()) {
            dimensions.put("workbook", workbook);
        }
        dimensions.put("column", ratioColumnLabel);
        dimensions.putAll(sharedRowDimensions(ratio));
        attributes.put("dimensions", dimensions);

        graph.addEntity(GraphEntity.builder(id)
                .type("SYNTHESIZED_MEASURE")
                .label(label)
                .confidence(SYNTHESIZED_CONFIDENCE)
                .attributes(attributes)
                .build());
        return new SynthesizedMeasure(
                id, label, workbook, sheet == null ? "" : sheet, ratio.size(), measureLabel);
    }

    // ─── Template learning ───────────────────────────────────────────────────

    record RatioTemplate(
            String outputLabel,
            String numeratorLabel,
            String denominatorLabel,
            int support,
            List<String> sourceRuleIds) {
    }

    List<RatioTemplate> learnRatioTemplates(
            MutableReasoningGraph graph, List<QuantitativeRule> rules) {
        Map<String, List<String>> supportByKey = new LinkedHashMap<>();
        Map<String, String[]> labelsByKey = new LinkedHashMap<>();
        for (QuantitativeRule rule : rules) {
            if (rule.inputs().size() != 2) {
                continue;
            }
            String expression = rule.expression().startsWith("=")
                    ? rule.expression().substring(1) : rule.expression();
            Matcher matcher = SIMPLE_DIVISION.matcher(expression);
            if (!matcher.matches()) {
                continue;
            }
            GraphEntity output = graph.entity(rule.outputEntityId()).orElse(null);
            GraphEntity numerator = inputForReference(graph, rule, matcher.group(1));
            GraphEntity denominator = inputForReference(graph, rule, matcher.group(2));
            String outputLabel = measureLabel(output);
            String numeratorLabel = measureLabel(numerator);
            String denominatorLabel = measureLabel(denominator);
            if (outputLabel == null || numeratorLabel == null || denominatorLabel == null) {
                continue;
            }
            String normalizedOutput = QuantitativeGraphSupport.normalizeText(outputLabel);
            String normalizedNumerator = QuantitativeGraphSupport.normalizeText(numeratorLabel);
            String normalizedDenominator = QuantitativeGraphSupport.normalizeText(denominatorLabel);
            if (normalizedOutput.isEmpty() || normalizedNumerator.isEmpty()
                    || normalizedDenominator.isEmpty()
                    || normalizedNumerator.equals(normalizedDenominator)
                    || normalizedOutput.equals(normalizedNumerator)) {
                continue;
            }
            String key = normalizedOutput + "|" + normalizedNumerator + "|" + normalizedDenominator;
            supportByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rule.id());
            labelsByKey.putIfAbsent(key,
                    new String[] {outputLabel, numeratorLabel, denominatorLabel});
        }

        List<RatioTemplate> templates = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : supportByKey.entrySet()) {
            if (entry.getValue().size() < MINIMUM_TEMPLATE_SUPPORT) {
                continue;
            }
            String[] labels = labelsByKey.get(entry.getKey());
            templates.add(new RatioTemplate(labels[0], labels[1], labels[2],
                    entry.getValue().size(), List.copyOf(entry.getValue())));
        }
        templates.sort((left, right) -> Integer.compare(right.support(), left.support()));
        return templates;
    }

    private static GraphEntity inputForReference(
            MutableReasoningGraph graph, QuantitativeRule rule, String reference) {
        String canonical = QuantitativeGraphSupport.canonicalIdentifier(reference);
        if (canonical.isEmpty()) {
            return null;
        }
        for (QuantitativeRule.Input input : rule.inputs()) {
            String alias = QuantitativeGraphSupport.canonicalIdentifier(input.alias());
            if (alias.equals(canonical) || alias.endsWith("!" + canonical)
                    || canonical.endsWith("!" + alias)) {
                return graph.entity(input.entityId()).orElse(null);
            }
        }
        return null;
    }

    private static String measureLabel(GraphEntity entity) {
        if (entity == null) {
            return null;
        }
        String rowLabel = QuantitativeGraphSupport.firstString(entity, "rowLabel");
        if (rowLabel != null) {
            return rowLabel;
        }
        return QuantitativeGraphSupport.blank(entity.label()) ? null : entity.label();
    }

    // ─── Column matching ─────────────────────────────────────────────────────

    /**
     * A grid column carries a template measure when every non-temporal column token matches a
     * template-label token exactly, by abbreviation, or as the template label's acronym.
     */
    static boolean columnMatches(String columnLabel, String templateLabel) {
        Set<String> columnTokens = QuantitativeGraphSupport.tokens(columnLabel).stream()
                .filter(token -> !QuantitativeGraphSupport.temporalToken(token))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (columnTokens.isEmpty()) {
            return false;
        }
        Set<String> templateTokens = QuantitativeGraphSupport.tokens(templateLabel).stream()
                .filter(token -> !QuantitativeGraphSupport.temporalToken(token))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String acronym = acronym(templateTokens);
        for (String columnToken : columnTokens) {
            boolean matched = columnToken.equals(acronym)
                    || QuantitativeGraphSupport.matchesAnyToken(columnToken, templateTokens);
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static String acronym(Set<String> tokens) {
        StringBuilder result = new StringBuilder();
        for (String token : tokens) {
            result.append(token.charAt(0));
        }
        return result.length() >= 2 ? result.toString() : "";
    }

    // ─── Grid helpers ────────────────────────────────────────────────────────

    private static boolean gridCell(GraphEntity entity) {
        if (entity.id().startsWith("synth:")) {
            return false;
        }
        String cellType = QuantitativeGraphSupport.normalizeType(
                QuantitativeGraphSupport.firstString(entity, "cellType", "valueType"));
        if (cellType.contains("STRING") || cellType.contains("BOOLEAN")
                || cellType.contains("ERROR")) {
            return false;
        }
        return QuantitativeGraphSupport.numericValue(entity).isPresent()
                || !QuantitativeGraphSupport.blank(
                        QuantitativeGraphSupport.firstString(entity, "formula"));
    }

    private static boolean valueBacked(GraphEntity entity) {
        return QuantitativeGraphSupport.numericValue(entity).isPresent()
                || !QuantitativeGraphSupport.blank(
                        QuantitativeGraphSupport.firstString(entity, "formula"));
    }

    private static Integer rowIndex(GraphEntity entity) {
        Object raw = entity.attributes().get("row");
        var parsed = QuantitativeGraphSupport.parseNumber(raw);
        return parsed.isPresent() ? (int) parsed.getAsDouble() : null;
    }

    private static String sheetKey(GraphEntity entity) {
        return workbook(entity) + " " + QuantitativeGraphSupport.firstString(
                entity, "sheetName", "sheet_name", "sheet");
    }

    private static String workbook(GraphEntity entity) {
        String direct = QuantitativeGraphSupport.firstString(entity, "workbook");
        if (direct != null) {
            return direct;
        }
        String fromDimensions = QuantitativeGraphSupport.dimensions(entity).get("workbook");
        return fromDimensions == null ? "" : fromDimensions;
    }

    private static Map<String, String> sharedRowDimensions(List<GraphEntity> cells) {
        Map<String, String> shared = null;
        for (GraphEntity cell : cells) {
            Map<String, String> dimensions =
                    new LinkedHashMap<>(QuantitativeGraphSupport.dimensions(cell));
            dimensions.keySet().removeAll(Set.of("sheet", "workbook", "row", "column"));
            if (shared == null) {
                shared = dimensions;
            } else {
                shared.entrySet().removeIf(entry ->
                        !entry.getValue().equals(dimensions.get(entry.getKey())));
            }
            if (shared.isEmpty()) {
                return Map.of();
            }
        }
        return shared == null ? Map.of() : shared;
    }
}
