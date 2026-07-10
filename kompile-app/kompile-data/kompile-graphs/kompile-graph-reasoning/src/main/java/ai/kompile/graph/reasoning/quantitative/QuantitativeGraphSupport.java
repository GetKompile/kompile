/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.model.GraphEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QuantitativeGraphSupport {

    private static final List<String> VALUE_KEYS = List.of(
            "numericValue", "evaluatedNumericValue", "rawValue", "value", "amount", "displayValue");
    private static final List<String> UNIT_KEYS = List.of(
            "unit", "unitCode", "quantityUnit", "currency");
    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?");
    private static final Pattern YEAR_TOKEN = Pattern.compile("(?:19|20)[0-9]{2}");

    private QuantitativeGraphSupport() {
    }

    static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
    }

    static String firstString(GraphEntity entity, String... keys) {
        if (entity == null) {
            return null;
        }
        for (String key : keys) {
            Object value = entity.attributes().get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    static OptionalDouble numericValue(GraphEntity entity) {
        if (entity == null) {
            return OptionalDouble.empty();
        }
        for (String key : VALUE_KEYS) {
            Object value = entity.attributes().get(key);
            OptionalDouble parsed = parseNumber(value);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return OptionalDouble.empty();
    }

    static OptionalDouble parseNumber(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        }
        if (raw == null) {
            return OptionalDouble.empty();
        }
        String normalized = String.valueOf(raw).trim().replace(",", "");
        if (normalized.isEmpty()) {
            return OptionalDouble.empty();
        }
        boolean negativeParentheses = normalized.startsWith("(") && normalized.endsWith(")");
        if (negativeParentheses) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        boolean percent = normalized.endsWith("%");
        if (percent) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        normalized = normalized.replaceAll("\\s+", "");
        normalized = stripCurrencySymbols(normalized);
        Matcher matcher = NUMBER.matcher(normalized);
        if (!matcher.matches()) {
            return OptionalDouble.empty();
        }
        try {
            double value = Double.parseDouble(normalized);
            if (negativeParentheses) {
                value = -Math.abs(value);
            }
            if (percent) {
                value /= 100.0;
            }
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException ignored) {
            return OptionalDouble.empty();
        }
    }

    static String unit(GraphEntity entity) {
        if (entity == null) {
            return null;
        }
        for (String key : UNIT_KEYS) {
            Object value = entity.attributes().get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    static Map<String, String> dimensions(GraphEntity entity) {
        Map<String, String> result = new LinkedHashMap<>();
        if (entity == null) {
            return result;
        }
        Object raw = entity.attributes().get("dimensions");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        for (Map.Entry<String, Object> entry : entity.attributes().entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith("dimension.") && entry.getValue() != null) {
                result.put(key.substring("dimension.".length()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    static String dimension(GraphEntity entity, Map<String, String> projected, String key) {
        if (key == null) {
            return null;
        }
        String fromMap = getIgnoreCase(projected, key);
        if (fromMap != null) {
            return fromMap;
        }
        for (Map.Entry<String, Object> entry : entity.attributes().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)
                    || entry.getKey().equalsIgnoreCase("dimension." + key)) {
                return entry.getValue() == null ? null : String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    static double selectorScore(GraphEntity entity, QuantitativeQuery.MeasureSelector selector) {
        if (entity == null || selector == null || !selector.specified()) {
            return 0.0;
        }
        if (!blank(selector.entityId())) {
            return entity.id().equalsIgnoreCase(selector.entityId().trim()) ? 1.0 : 0.0;
        }

        double score = 0.0;
        if (!blank(selector.text())) {
            String query = normalizeText(selector.text());
            String label = normalizeText(entity.label());
            String id = normalizeText(entity.id());
            if (label.equals(query)) {
                score = 1.0;
            } else if (id.equals(query)) {
                score = 0.98;
            } else {
                Set<String> queryTokens = tokens(query);
                Set<String> entityTokens = tokens(entitySearchText(entity));
                if (!queryTokens.isEmpty()) {
                    long overlap = queryTokens.stream()
                            .filter(token -> matchesAnyToken(token, entityTokens)).count();
                    double coverage = (double) overlap / queryTokens.size();
                    double jaccard = entityTokens.isEmpty() ? 0.0
                            : (double) overlap / unionSize(queryTokens, entityTokens);
                    score = Math.max(score, 0.75 * coverage + 0.25 * jaccard);
                }
                if (!query.isEmpty() && !tokens(label).isEmpty()
                        && (containsPhrase(label, query) || containsPhrase(query, label))) {
                    score = Math.max(score, 0.85);
                }
            }
        } else {
            score = 0.6;
        }

        if (!blank(selector.type())) {
            boolean typeMatch = entity.typeMemberships().stream()
                    .anyMatch(type -> type.equalsIgnoreCase(selector.type()));
            if (!typeMatch) {
                return 0.0;
            }
            score = Math.min(1.0, score + 0.15);
        }
        if (!blank(selector.unit())) {
            String entityUnit = unit(entity);
            if (entityUnit != null && entityUnit.equalsIgnoreCase(selector.unit())) {
                score = Math.min(1.0, score + 0.05);
            }
        }
        return clamp01(score);
    }

    static boolean quantitativeCandidate(GraphEntity entity) {
        if (entity == null) {
            return false;
        }
        if (!blank(firstString(entity, "formula", "expression"))) {
            return true;
        }
        String cellType = normalizeType(firstString(
                entity, "cellType", "evaluatedCellType", "valueType"));
        if (cellType.contains("STRING") || cellType.contains("BOOLEAN")
                || cellType.contains("ERROR")) {
            return false;
        }
        if (numericValue(entity).isPresent()
                || cellType.contains("NUMERIC") || cellType.contains("FORMULA")) {
            return true;
        }
        return entity.typeMemberships().stream()
                .map(QuantitativeGraphSupport::normalizeType)
                .anyMatch(type -> type.contains("MEASURE") || type.contains("METRIC")
                        || type.contains("QUANTITY") || type.contains("FORMULA"));
    }

    static String requestedTarget(QuantitativeQuery.MeasureSelector selector) {
        if (selector == null) {
            return "";
        }
        if (!blank(selector.entityId())) {
            return selector.entityId();
        }
        if (!blank(selector.text())) {
            return selector.text();
        }
        return selector.type() == null ? "" : selector.type();
    }

    static String preferredAlias(GraphEntity entity) {
        String explicit = firstString(entity, "symbol", "alias", "cell_reference", "cellReference");
        if (!blank(explicit)) {
            return canonicalIdentifier(explicit);
        }
        int cellMarker = entity.id().lastIndexOf("cell:");
        if (cellMarker >= 0 && cellMarker + 5 < entity.id().length()) {
            return canonicalIdentifier(entity.id().substring(cellMarker + 5));
        }
        if (!blank(entity.label())) {
            return canonicalIdentifier(entity.label());
        }
        return canonicalIdentifier(entity.id());
    }

    static Set<String> aliases(GraphEntity entity) {
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(canonicalIdentifier(entity.id()));
        aliases.add(canonicalIdentifier(entity.label()));
        for (String key : List.of("symbol", "alias", "cell_reference", "cellReference",
                "namedRangeName", "externalId")) {
            Object value = entity.attributes().get(key);
            if (value != null) {
                aliases.add(canonicalIdentifier(String.valueOf(value)));
            }
        }
        aliases.remove("");
        return aliases;
    }

    static String canonicalIdentifier(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace("$", "");
        if (normalized.startsWith("'") && normalized.contains("'!")) {
            int end = normalized.indexOf("'!");
            String sheet = normalized.substring(1, end).replaceAll("[^A-Za-z0-9_]", "_");
            normalized = sheet + normalized.substring(end + 1);
        }
        normalized = normalized.replaceAll("[^A-Za-z0-9_.!:]", "_")
                .replaceAll("_+", "_");
        return normalized.toUpperCase(Locale.ROOT);
    }

    static String entitySearchText(GraphEntity entity) {
        List<String> parts = new ArrayList<>();
        parts.add(entity.id());
        parts.add(entity.label());
        parts.add(entity.type());
        parts.addAll(entity.typeMemberships());
        parts.addAll(entity.tags());
        for (Map.Entry<String, Object> attribute : entity.attributes().entrySet()) {
            // A sheet title describes every cell on the sheet ("net revenue and EBIT, USD"), so
            // treating it as measure identity would make unrelated rows lexically identical. It
            // stays available to contextMatches for dimension-scope evidence only.
            if ("sheetTitle".equals(attribute.getKey())) {
                continue;
            }
            Object value = attribute.getValue();
            if (value instanceof String || value instanceof Number || value instanceof Enum<?>) {
                parts.add(String.valueOf(value));
            } else if (value instanceof Collection<?> collection) {
                collection.forEach(item -> parts.add(String.valueOf(item)));
            }
        }
        return normalizeText(String.join(" ", parts));
    }

    static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
    }

    static Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : normalizeText(value).split(" ")) {
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                result.add(canonicalToken(token));
            }
        }
        return result;
    }

    static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static boolean containsPhrase(String text, String phrase) {
        return !(text == null || phrase == null || phrase.isEmpty())
                && (" " + text + " ").contains(" " + phrase + " ");
    }

    static boolean matchesAnyToken(String token, Set<String> candidates) {
        if (candidates.contains(token)) {
            return true;
        }
        for (String candidate : candidates) {
            if (abbreviationMatch(token, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spreadsheet labels routinely abbreviate ("rev" for revenue, "fcst" for forecast). One token
     * matches another when the shorter (at least three characters) is a prefix of the longer.
     */
    static boolean abbreviationMatch(String left, String right) {
        if (left == null || right == null || left.equals(right)) {
            return left != null && left.equals(right);
        }
        String shorter = left.length() <= right.length() ? left : right;
        String longer = left.length() <= right.length() ? right : left;
        return shorter.length() >= 3 && longer.startsWith(shorter);
    }

    static boolean temporalToken(String token) {
        return token.startsWith("month_") || YEAR_TOKEN.matcher(token).matches();
    }

    /** Ordered non-temporal tokens of a label; the facet identity left after removing periods. */
    static String nonTemporalSignature(String label) {
        List<String> kept = new ArrayList<>();
        for (String token : tokens(label)) {
            if (!temporalToken(token)) {
                kept.add(token);
            }
        }
        return String.join(" ", kept);
    }

    static Set<String> temporalTokens(String label) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : tokens(label)) {
            if (temporalToken(token)) {
                result.add(token);
            }
        }
        return result;
    }

    enum DimensionMatch {
        MATCH,
        CONTEXT_MATCH,
        MISSING,
        CONFLICT
    }

    /**
     * Hard-constraint dimension check with two evidence sources: an explicit dimension value on the
     * entity, or positive contextual evidence (workbook, sheet, titles, labels) containing the
     * requested member. Alias groups learned from graph taxonomy tables count as equality. Without
     * positive evidence the entity stays incompatible; requested scope is never assumed.
     */
    static DimensionMatch dimensionCompatibility(
            GraphEntity entity,
            Map<String, String> projected,
            String key,
            String requestedValue,
            DimensionAliasCatalog aliases) {
        String actual = dimension(entity, projected, key);
        if (!blank(actual)) {
            return valuesMatch(actual, requestedValue, aliases)
                    ? DimensionMatch.MATCH : DimensionMatch.CONFLICT;
        }
        return contextMatches(entity, requestedValue, aliases)
                ? DimensionMatch.CONTEXT_MATCH : DimensionMatch.MISSING;
    }

    static boolean valuesMatch(String actual, String requested, DimensionAliasCatalog aliases) {
        String left = normalizeText(actual);
        String right = normalizeText(requested);
        if (left.equals(right)) {
            return true;
        }
        return aliases != null && aliases.sameGroup(left, right);
    }

    static boolean contextMatches(
            GraphEntity entity, String requestedValue, DimensionAliasCatalog aliases) {
        String requested = normalizeText(requestedValue);
        if (requested.isEmpty()) {
            return false;
        }
        List<String> contexts = new ArrayList<>();
        for (String key : List.of("workbook", "sheetName", "sheet_name", "sheet", "sheetTitle",
                "sectionLabel", "rowLabel", "semanticLabel", "path", "source", "sourcePath")) {
            String value = firstString(entity, key);
            if (value != null) {
                contexts.add(value);
            }
        }
        contexts.addAll(dimensions(entity).values());
        for (String context : contexts) {
            String normalized = normalizeText(context);
            if (containsPhrase(normalized, requested)) {
                return true;
            }
            if (aliases != null) {
                for (String token : normalized.split(" ")) {
                    if (!token.isEmpty() && aliases.sameGroup(token, requested)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String stripCurrencySymbols(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.getType(value.charAt(start))
                == Character.CURRENCY_SYMBOL) {
            start++;
        }
        while (end > start && Character.getType(value.charAt(end - 1))
                == Character.CURRENCY_SYMBOL) {
            end--;
        }
        return value.substring(start, end);
    }

    private static String canonicalToken(String token) {
        return MONTH_TOKENS.getOrDefault(token, token);
    }

    private static int unionSize(Set<String> left, Set<String> right) {
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return Math.max(1, union.size());
    }

    private static String getIgnoreCase(Map<String, String> values, String key) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static final Map<String, String> MONTH_TOKENS = Map.ofEntries(
            Map.entry("jan", "month_01"), Map.entry("january", "month_01"),
            Map.entry("feb", "month_02"), Map.entry("february", "month_02"),
            Map.entry("mar", "month_03"), Map.entry("march", "month_03"),
            Map.entry("apr", "month_04"), Map.entry("april", "month_04"),
            Map.entry("may", "month_05"),
            Map.entry("jun", "month_06"), Map.entry("june", "month_06"),
            Map.entry("jul", "month_07"), Map.entry("july", "month_07"),
            Map.entry("aug", "month_08"), Map.entry("august", "month_08"),
            Map.entry("sep", "month_09"), Map.entry("sept", "month_09"),
            Map.entry("september", "month_09"),
            Map.entry("oct", "month_10"), Map.entry("october", "month_10"),
            Map.entry("nov", "month_11"), Map.entry("november", "month_11"),
            Map.entry("dec", "month_12"), Map.entry("december", "month_12"));

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "into", "what", "does", "happen",
            "change", "changes", "scenario", "calculate", "show", "find");
}
