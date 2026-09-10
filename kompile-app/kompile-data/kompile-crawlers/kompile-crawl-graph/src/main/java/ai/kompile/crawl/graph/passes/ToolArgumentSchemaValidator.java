/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph.passes;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates model-authored tool arguments against the JSON-schema subset used by extraction tools.
 *
 * <p>The validator is intentionally non-mutating: it never renames, drops, coerces, or supplies a
 * model-authored field. Invalid arguments must be returned to the model as explicit retry feedback
 * instead of being converted into a different executable call.</p>
 */
final class ToolArgumentSchemaValidator {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();
    private static final int MAX_ERRORS = 32;

    private ToolArgumentSchemaValidator() {
    }

    static ValidationResult validate(
            Map<String, Object> schema,
            Map<String, Object> arguments) {
        if (schema == null || schema.isEmpty()) {
            return ValidationResult.success();
        }
        List<String> errors = new ArrayList<>();
        JsonNode schemaNode = MAPPER.valueToTree(schema);
        JsonNode value = MAPPER.valueToTree(arguments == null ? Map.of() : arguments);
        validateValue(value, schemaNode, "$", errors);
        return errors.isEmpty()
                ? ValidationResult.success()
                : new ValidationResult(false, errors);
    }

    private static void validateValue(
            JsonNode value,
            JsonNode schema,
            String path,
            List<String> errors) {
        if (atLimit(errors) || schema == null || !schema.isObject()) {
            return;
        }

        validateCompositions(value, schema, path, errors);
        if (atLimit(errors)) {
            return;
        }

        JsonNode constant = schema.get("const");
        if (constant != null && !schemaValueEquals(constant, value)) {
            add(errors, path + " must equal " + constant);
        }

        JsonNode allowed = schema.get("enum");
        if (allowed != null && allowed.isArray()) {
            boolean match = false;
            for (JsonNode candidate : allowed) {
                if (schemaValueEquals(candidate, value)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                add(errors, path + " must be one of " + allowed);
            }
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode != null && !matchesDeclaredType(value, typeNode)) {
            add(errors, path + " must be " + typeDescription(typeNode)
                    + " but was " + valueType(value));
            return;
        }

        if (value != null && value.isObject()) {
            validateObject(value, schema, path, errors);
        } else if (value != null && value.isArray()) {
            validateArray(value, schema, path, errors);
        } else if (value != null && value.isNumber()) {
            validateNumber(value, schema, path, errors);
        } else if (value != null && value.isTextual()) {
            validateString(value, schema, path, errors);
        }
    }

    private static void validateCompositions(
            JsonNode value,
            JsonNode schema,
            String path,
            List<String> errors) {
        JsonNode allOf = schema.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (JsonNode child : allOf) {
                validateValue(value, child, path, errors);
            }
        }

        validateAlternative(value, schema.get("anyOf"), path, errors, false);
        validateAlternative(value, schema.get("oneOf"), path, errors, true);
    }

    private static void validateAlternative(
            JsonNode value,
            JsonNode alternatives,
            String path,
            List<String> errors,
            boolean exactlyOne) {
        if (alternatives == null || !alternatives.isArray()) {
            return;
        }
        int matches = 0;
        for (JsonNode alternative : alternatives) {
            List<String> candidateErrors = new ArrayList<>();
            validateValue(value, alternative, path, candidateErrors);
            if (candidateErrors.isEmpty()) {
                matches++;
            }
        }
        if ((exactlyOne && matches != 1) || (!exactlyOne && matches == 0)) {
            add(errors, path + (exactlyOne
                    ? " must match exactly one declared schema alternative"
                    : " must match at least one declared schema alternative"));
        }
    }

    private static void validateObject(
            JsonNode value,
            JsonNode schema,
            String path,
            List<String> errors) {
        JsonNode properties = schema.get("properties");
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                if (field.isTextual() && !value.has(field.asText())) {
                    add(errors, childPath(path, field.asText()) + " is required");
                }
            }
        }

        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        TreeSet<String> names = new TreeSet<>();
        while (fields.hasNext()) {
            names.add(fields.next().getKey());
        }
        JsonNode additional = schema.get("additionalProperties");
        for (String name : names) {
            if (atLimit(errors)) {
                return;
            }
            JsonNode fieldValue = value.get(name);
            if (properties != null && properties.isObject() && properties.has(name)) {
                validateValue(fieldValue, properties.get(name), childPath(path, name), errors);
            } else if (additional != null && additional.isObject()) {
                validateValue(fieldValue, additional, childPath(path, name), errors);
            } else if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
                add(errors, childPath(path, name) + " is not declared");
            }
        }

        int size = value.size();
        if (schema.has("minProperties") && size < schema.path("minProperties").asInt()) {
            add(errors, path + " must contain at least "
                    + schema.path("minProperties").asInt() + " properties");
        }
        if (schema.has("maxProperties") && size > schema.path("maxProperties").asInt()) {
            add(errors, path + " must contain at most "
                    + schema.path("maxProperties").asInt() + " properties");
        }
    }

    private static void validateArray(
            JsonNode value,
            JsonNode schema,
            String path,
            List<String> errors) {
        if (schema.has("minItems") && value.size() < schema.path("minItems").asInt()) {
            add(errors, path + " must contain at least "
                    + schema.path("minItems").asInt() + " items");
        }
        if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) {
            add(errors, path + " must contain at most "
                    + schema.path("maxItems").asInt() + " items");
        }
        JsonNode prefixItems = schema.get("prefixItems");
        int prefixCount = prefixItems != null && prefixItems.isArray()
                ? prefixItems.size() : 0;
        for (int index = 0; index < Math.min(value.size(), prefixCount)
                && !atLimit(errors); index++) {
            validateValue(value.get(index), prefixItems.get(index), path + "[" + index + "]", errors);
        }

        JsonNode itemSchema = schema.get("items");
        if (itemSchema != null && itemSchema.isObject()) {
            for (int index = prefixCount; index < value.size() && !atLimit(errors); index++) {
                validateValue(value.get(index), itemSchema, path + "[" + index + "]", errors);
            }
        } else if (itemSchema != null && itemSchema.isBoolean() && !itemSchema.asBoolean()
                && value.size() > prefixCount) {
            add(errors, path + " must not contain items beyond the " + prefixCount
                    + " positional entries");
        }

        if (schema.path("uniqueItems").asBoolean(false)) {
            Set<JsonNode> unique = new HashSet<>();
            for (int index = 0; index < value.size() && !atLimit(errors); index++) {
                if (!unique.add(value.get(index))) {
                    add(errors, path + "[" + index + "] duplicates an earlier item");
                }
            }
        }
    }

    private static void validateNumber(
            JsonNode value,
            JsonNode schema,
            String path,
            List<String> errors) {
        double number = value.asDouble();
        if (schema.has("minimum") && number < schema.path("minimum").asDouble()) {
            add(errors, path + " must be >= " + schema.get("minimum"));
        }
        if (schema.has("maximum") && number > schema.path("maximum").asDouble()) {
            add(errors, path + " must be <= " + schema.get("maximum"));
        }
        if (schema.has("exclusiveMinimum") && number <= schema.path("exclusiveMinimum").asDouble()) {
            add(errors, path + " must be > " + schema.get("exclusiveMinimum"));
        }
        if (schema.has("exclusiveMaximum") && number >= schema.path("exclusiveMaximum").asDouble()) {
            add(errors, path + " must be < " + schema.get("exclusiveMaximum"));
        }
    }

    private static void validateString(
            JsonNode value,
            JsonNode schema,
            String path,
            List<String> errors) {
        String text = value.asText();
        if (schema.has("minLength") && text.length() < schema.path("minLength").asInt()) {
            add(errors, path + " must contain at least "
                    + schema.path("minLength").asInt() + " characters");
        }
        if (schema.has("maxLength") && text.length() > schema.path("maxLength").asInt()) {
            add(errors, path + " must contain at most "
                    + schema.path("maxLength").asInt() + " characters");
        }
        if (schema.has("pattern")) {
            try {
                if (!Pattern.compile(schema.path("pattern").asText()).matcher(text).find()) {
                    add(errors, path + " must match pattern " + schema.path("pattern").asText());
                }
            } catch (PatternSyntaxException e) {
                add(errors, path + " has an invalid declared schema pattern: " + e.getMessage());
            }
        }
    }

    private static boolean matchesDeclaredType(JsonNode value, JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return matchesType(value, typeNode.asText());
        }
        if (typeNode.isArray()) {
            for (JsonNode type : typeNode) {
                if (type.isTextual() && matchesType(value, type.asText())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value != null && value.isObject();
            case "array" -> value != null && value.isArray();
            case "string" -> value != null && value.isTextual();
            case "integer" -> isJsonSchemaInteger(value);
            case "number" -> value != null && value.isNumber();
            case "boolean" -> value != null && value.isBoolean();
            case "null" -> value == null || value.isNull();
            default -> true;
        };
    }

    private static String typeDescription(JsonNode typeNode) {
        return typeNode.isTextual() ? typeNode.asText() : typeNode.toString();
    }

    private static String valueType(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isObject()) return "object";
        if (value.isArray()) return "array";
        if (value.isTextual()) return "string";
        if (isJsonSchemaInteger(value)) return "integer";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        return value.getNodeType().name().toLowerCase();
    }

    /**
     * JSON Schema defines an integer by mathematical value, not by the parser's storage type.
     * Consequently {@code 1}, {@code 1.0}, and {@code 1.00} are all integers, while
     * {@code 1.5} is not. Jackson represents the decimal spellings as floating-point nodes, so
     * {@link JsonNode#isIntegralNumber()} alone is too strict for model-authored tool arguments.
     */
    private static boolean isJsonSchemaInteger(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return false;
        }
        try {
            return value.decimalValue().stripTrailingZeros().scale() <= 0;
        } catch (ArithmeticException | NumberFormatException invalidNumber) {
            return false;
        }
    }

    private static boolean schemaValueEquals(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected.isNumber() && actual.isNumber()) {
            try {
                return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
            } catch (ArithmeticException | NumberFormatException invalidNumber) {
                return false;
            }
        }
        return expected.equals(actual);
    }

    private static String childPath(String path, String field) {
        return path + "." + field;
    }

    private static boolean atLimit(List<String> errors) {
        return errors.size() >= MAX_ERRORS;
    }

    private static void add(List<String> errors, String error) {
        if (!atLimit(errors)) {
            errors.add(error);
        }
    }

    record ValidationResult(boolean valid, List<String> errors) {
        ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }
    }
}
