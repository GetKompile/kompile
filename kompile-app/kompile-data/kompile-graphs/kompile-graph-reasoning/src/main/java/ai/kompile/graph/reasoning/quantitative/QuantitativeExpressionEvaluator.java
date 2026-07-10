/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic evaluator for graph-resident arithmetic and common spreadsheet formulas.
 *
 * <p>This is deliberately a strict, auditable subset. Unsupported functions fail explicitly so a
 * caller can report a model gap or route to an injected executor. It never asks an LLM to rewrite
 * financial logic.</p>
 */
public final class QuantitativeExpressionEvaluator {

    private static final Pattern QUOTED_SHEET = Pattern.compile("'([^']+)'!");
    private static final Pattern CELL = Pattern.compile("(?:(.+)!)?([A-Z]{1,3})([0-9]+)");

    public double evaluate(String expression, Map<String, Double> variables) {
        if (expression == null || expression.isBlank()) {
            throw new EvaluationException("expression is empty");
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        if (variables != null) {
            for (Map.Entry<String, Double> entry : variables.entrySet()) {
                if (entry.getValue() != null && Double.isFinite(entry.getValue())) {
                    normalized.put(QuantitativeGraphSupport.canonicalIdentifier(entry.getKey()),
                            entry.getValue());
                }
            }
        }
        String source = normalizeExpression(expression);
        Parser parser = new Parser(new Lexer(source).tokens(), normalized);
        double result = parser.parse().scalar();
        if (!Double.isFinite(result)) {
            throw new EvaluationException("expression produced a non-finite result");
        }
        return result;
    }

    private static String normalizeExpression(String expression) {
        String source = expression.trim();
        if (source.startsWith("=")) {
            source = source.substring(1);
        }
        Matcher matcher = QUOTED_SHEET.matcher(source);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            String sheet = matcher.group(1).replaceAll("[^A-Za-z0-9_]", "_");
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(sheet + "!"));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    public static final class EvaluationException extends IllegalArgumentException {
        public EvaluationException(String message) {
            super(message);
        }
    }

    private enum TokenType {
        NUMBER, IDENTIFIER,
        PLUS, MINUS, STAR, SLASH, CARET, PERCENT,
        LPAREN, RPAREN, COMMA, COLON,
        EQ, NE, LT, LE, GT, GE,
        EOF
    }

    private record Token(TokenType type, String text, int offset) {
    }

    private static final class Lexer {
        private final String source;
        private int offset;

        private Lexer(String source) {
            this.source = source;
        }

        private List<Token> tokens() {
            List<Token> result = new ArrayList<>();
            while (offset < source.length()) {
                char c = source.charAt(offset);
                if (Character.isWhitespace(c)) {
                    offset++;
                    continue;
                }
                int start = offset;
                switch (c) {
                    case '+' -> result.add(single(TokenType.PLUS));
                    case '-' -> result.add(single(TokenType.MINUS));
                    case '*' -> result.add(single(TokenType.STAR));
                    case '/' -> result.add(single(TokenType.SLASH));
                    case '^' -> result.add(single(TokenType.CARET));
                    case '%' -> result.add(single(TokenType.PERCENT));
                    case '(' -> result.add(single(TokenType.LPAREN));
                    case ')' -> result.add(single(TokenType.RPAREN));
                    case ',', ';' -> result.add(single(TokenType.COMMA));
                    case ':' -> result.add(single(TokenType.COLON));
                    case '=' -> result.add(single(TokenType.EQ));
                    case '<' -> {
                        offset++;
                        if (peek('=')) {
                            offset++;
                            result.add(new Token(TokenType.LE, "<=", start));
                        } else if (peek('>')) {
                            offset++;
                            result.add(new Token(TokenType.NE, "<>", start));
                        } else {
                            result.add(new Token(TokenType.LT, "<", start));
                        }
                    }
                    case '>' -> {
                        offset++;
                        if (peek('=')) {
                            offset++;
                            result.add(new Token(TokenType.GE, ">=", start));
                        } else {
                            result.add(new Token(TokenType.GT, ">", start));
                        }
                    }
                    case '!' -> {
                        offset++;
                        if (peek('=')) {
                            offset++;
                            result.add(new Token(TokenType.NE, "!=", start));
                        } else {
                            throw error("unexpected '!'", start);
                        }
                    }
                    default -> {
                        if (Character.isDigit(c) || c == '.') {
                            result.add(number());
                        } else if (isIdentifierStart(c)) {
                            result.add(identifier());
                        } else {
                            throw error("unsupported character '" + c + "'", start);
                        }
                    }
                }
            }
            result.add(new Token(TokenType.EOF, "", offset));
            return result;
        }

        private Token single(TokenType type) {
            int start = offset++;
            return new Token(type, source.substring(start, offset), start);
        }

        private Token number() {
            int start = offset;
            boolean dot = false;
            while (offset < source.length()) {
                char c = source.charAt(offset);
                if (Character.isDigit(c)) {
                    offset++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    offset++;
                } else {
                    break;
                }
            }
            if (offset < source.length()
                    && (source.charAt(offset) == 'e' || source.charAt(offset) == 'E')) {
                offset++;
                if (offset < source.length()
                        && (source.charAt(offset) == '+' || source.charAt(offset) == '-')) {
                    offset++;
                }
                while (offset < source.length() && Character.isDigit(source.charAt(offset))) {
                    offset++;
                }
            }
            return new Token(TokenType.NUMBER, source.substring(start, offset), start);
        }

        private Token identifier() {
            int start = offset;
            while (offset < source.length() && isIdentifierPart(source.charAt(offset))) {
                offset++;
            }
            return new Token(TokenType.IDENTIFIER, source.substring(start, offset), start);
        }

        private boolean peek(char expected) {
            return offset < source.length() && source.charAt(offset) == expected;
        }

        private static boolean isIdentifierStart(char c) {
            return Character.isLetter(c) || c == '_' || c == '$';
        }

        private static boolean isIdentifierPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$'
                    || c == '.' || c == '!';
        }

        private static EvaluationException error(String message, int offset) {
            return new EvaluationException(message + " at offset " + offset);
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final Map<String, Double> variables;
        private int current;

        private Parser(List<Token> tokens, Map<String, Double> variables) {
            this.tokens = tokens;
            this.variables = variables;
        }

        private Value parse() {
            Value value = comparison();
            expect(TokenType.EOF, "unexpected trailing input");
            return value;
        }

        private Value comparison() {
            Value left = addition();
            while (match(TokenType.EQ, TokenType.NE, TokenType.LT, TokenType.LE,
                    TokenType.GT, TokenType.GE)) {
                TokenType operator = previous().type();
                double l = left.scalar();
                double r = addition().scalar();
                boolean result = switch (operator) {
                    case EQ -> Double.compare(l, r) == 0;
                    case NE -> Double.compare(l, r) != 0;
                    case LT -> l < r;
                    case LE -> l <= r;
                    case GT -> l > r;
                    case GE -> l >= r;
                    default -> throw new IllegalStateException("unexpected comparison operator");
                };
                left = Value.scalar(result ? 1.0 : 0.0);
            }
            return left;
        }

        private Value addition() {
            Value value = multiplication();
            while (match(TokenType.PLUS, TokenType.MINUS)) {
                TokenType operator = previous().type();
                double right = multiplication().scalar();
                value = Value.scalar(operator == TokenType.PLUS
                        ? value.scalar() + right : value.scalar() - right);
            }
            return value;
        }

        private Value multiplication() {
            Value value = power();
            while (match(TokenType.STAR, TokenType.SLASH)) {
                TokenType operator = previous().type();
                double right = power().scalar();
                if (operator == TokenType.SLASH && right == 0.0) {
                    throw error("division by zero", previous());
                }
                value = Value.scalar(operator == TokenType.STAR
                        ? value.scalar() * right : value.scalar() / right);
            }
            return value;
        }

        private Value power() {
            Value value = unary();
            if (match(TokenType.CARET)) {
                value = Value.scalar(Math.pow(value.scalar(), power().scalar()));
            }
            return value;
        }

        private Value unary() {
            if (match(TokenType.MINUS)) {
                return Value.scalar(-unary().scalar());
            }
            if (match(TokenType.PLUS)) {
                return Value.scalar(unary().scalar());
            }
            return primary();
        }

        private Value primary() {
            Value value;
            if (match(TokenType.NUMBER)) {
                try {
                    value = Value.scalar(Double.parseDouble(previous().text()));
                } catch (NumberFormatException e) {
                    throw error("invalid number", previous());
                }
            } else if (match(TokenType.IDENTIFIER)) {
                Token identifier = previous();
                if (match(TokenType.LPAREN)) {
                    value = function(identifier);
                } else if (identifier.text().equalsIgnoreCase("TRUE")) {
                    value = Value.scalar(1.0);
                } else if (identifier.text().equalsIgnoreCase("FALSE")) {
                    value = Value.scalar(0.0);
                } else if (match(TokenType.COLON)) {
                    Token end = expect(TokenType.IDENTIFIER, "range end must be a cell reference");
                    value = range(identifier, end);
                } else {
                    value = Value.scalar(variable(identifier));
                }
            } else if (match(TokenType.LPAREN)) {
                value = comparison();
                expect(TokenType.RPAREN, "missing ')'");
            } else {
                throw error("expected a number, variable, function, or '('", peek());
            }

            while (match(TokenType.PERCENT)) {
                value = Value.scalar(value.scalar() / 100.0);
            }
            return value;
        }

        private Value function(Token name) {
            List<Value> args = new ArrayList<>();
            if (!check(TokenType.RPAREN)) {
                do {
                    args.add(comparison());
                } while (match(TokenType.COMMA));
            }
            expect(TokenType.RPAREN, "missing ')' after function arguments");
            String function = name.text().toUpperCase(Locale.ROOT);
            List<Double> flat = flatten(args);
            return switch (function) {
                case "SUM" -> Value.scalar(flat.stream().mapToDouble(Double::doubleValue).sum());
                case "AVERAGE", "AVG" -> {
                    requireArgs(function, flat, 1);
                    yield Value.scalar(flat.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
                }
                case "MIN" -> {
                    requireArgs(function, flat, 1);
                    yield Value.scalar(flat.stream().mapToDouble(Double::doubleValue).min().orElseThrow());
                }
                case "MAX" -> {
                    requireArgs(function, flat, 1);
                    yield Value.scalar(flat.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
                }
                case "ABS" -> Value.scalar(Math.abs(scalarArg(function, args, 0, 1)));
                case "SQRT" -> Value.scalar(Math.sqrt(scalarArg(function, args, 0, 1)));
                case "POWER" -> {
                    requireCount(function, args, 2);
                    yield Value.scalar(Math.pow(args.get(0).scalar(), args.get(1).scalar()));
                }
                case "ROUND", "ROUNDUP", "ROUNDDOWN" -> round(function, args);
                case "IF" -> {
                    requireCount(function, args, 3);
                    yield args.get(0).truthy() ? args.get(1) : args.get(2);
                }
                case "AND" -> Value.scalar(args.stream().allMatch(Value::truthy) ? 1.0 : 0.0);
                case "OR" -> Value.scalar(args.stream().anyMatch(Value::truthy) ? 1.0 : 0.0);
                case "NOT" -> Value.scalar(scalarArg(function, args, 0, 1) == 0.0 ? 1.0 : 0.0);
                default -> throw error("unsupported function " + name.text(), name);
            };
        }

        private Value round(String function, List<Value> args) {
            requireCount(function, args, 2);
            double value = args.get(0).scalar();
            int digits = (int) Math.round(args.get(1).scalar());
            double factor = Math.pow(10.0, digits);
            double scaled = value * factor;
            double rounded = switch (function) {
                case "ROUNDUP" -> scaled >= 0.0 ? Math.ceil(scaled) : Math.floor(scaled);
                case "ROUNDDOWN" -> scaled >= 0.0 ? Math.floor(scaled) : Math.ceil(scaled);
                default -> Math.rint(scaled);
            };
            return Value.scalar(rounded / factor);
        }

        private double variable(Token token) {
            String key = QuantitativeGraphSupport.canonicalIdentifier(token.text());
            Double value = variables.get(key);
            if (value == null) {
                throw error("missing variable " + token.text(), token);
            }
            return value;
        }

        private Value range(Token startToken, Token endToken) {
            CellRef start = CellRef.parse(startToken.text());
            CellRef end = CellRef.parse(endToken.text());
            String sheet = start.sheet() != null ? start.sheet() : end.sheet();
            if (start.sheet() != null && end.sheet() != null
                    && !start.sheet().equalsIgnoreCase(end.sheet())) {
                throw error("3D ranges are not supported", startToken);
            }
            int rowStart = Math.min(start.row(), end.row());
            int rowEnd = Math.max(start.row(), end.row());
            int colStart = Math.min(start.column(), end.column());
            int colEnd = Math.max(start.column(), end.column());
            List<Double> values = new ArrayList<>();
            for (int row = rowStart; row <= rowEnd; row++) {
                for (int column = colStart; column <= colEnd; column++) {
                    String reference = (sheet == null ? "" : sheet + "!")
                            + CellRef.columnName(column) + row;
                    String key = QuantitativeGraphSupport.canonicalIdentifier(reference);
                    Double value = variables.get(key);
                    if (value == null) {
                        throw error("missing range value " + reference, startToken);
                    }
                    values.add(value);
                }
            }
            return new Value(values);
        }

        private static List<Double> flatten(List<Value> values) {
            List<Double> result = new ArrayList<>();
            for (Value value : values) {
                result.addAll(value.values());
            }
            return result;
        }

        private static double scalarArg(
                String function, List<Value> args, int index, int count) {
            requireCount(function, args, count);
            return args.get(index).scalar();
        }

        private static void requireArgs(String function, List<Double> args, int minimum) {
            if (args.size() < minimum) {
                throw new EvaluationException(function + " requires at least " + minimum + " argument(s)");
            }
        }

        private static void requireCount(String function, List<Value> args, int count) {
            if (args.size() != count) {
                throw new EvaluationException(function + " requires " + count + " argument(s)");
            }
        }

        private boolean match(TokenType... types) {
            for (TokenType type : types) {
                if (check(type)) {
                    current++;
                    return true;
                }
            }
            return false;
        }

        private Token expect(TokenType type, String message) {
            if (check(type)) {
                return tokens.get(current++);
            }
            throw error(message, peek());
        }

        private boolean check(TokenType type) {
            return peek().type() == type;
        }

        private Token peek() {
            return tokens.get(current);
        }

        private Token previous() {
            return tokens.get(current - 1);
        }

        private static EvaluationException error(String message, Token token) {
            return new EvaluationException(message + " at offset " + token.offset());
        }
    }

    private record Value(List<Double> values) {
        private Value {
            values = List.copyOf(values);
            if (values.isEmpty()) {
                throw new EvaluationException("empty value");
            }
        }

        private static Value scalar(double value) {
            return new Value(List.of(value));
        }

        private double scalar() {
            if (values.size() != 1) {
                throw new EvaluationException("range cannot be used as a scalar");
            }
            return values.get(0);
        }

        private boolean truthy() {
            return scalar() != 0.0;
        }
    }

    private record CellRef(String sheet, int column, int row) {
        private static CellRef parse(String text) {
            String normalized = QuantitativeGraphSupport.canonicalIdentifier(text);
            Matcher matcher = CELL.matcher(normalized);
            if (!matcher.matches()) {
                throw new EvaluationException("invalid cell reference " + text);
            }
            return new CellRef(matcher.group(1), columnIndex(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        }

        private static int columnIndex(String name) {
            int result = 0;
            for (int i = 0; i < name.length(); i++) {
                result = result * 26 + (name.charAt(i) - 'A' + 1);
            }
            return result;
        }

        private static String columnName(int index) {
            StringBuilder result = new StringBuilder();
            int value = index;
            while (value > 0) {
                value--;
                result.append((char) ('A' + value % 26));
                value /= 26;
            }
            return result.reverse().toString();
        }
    }
}
