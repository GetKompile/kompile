/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An <b>arithmetic rule</b> in PSL: a linear combination of predicate atoms compared via
 * a relational operator {@link RelOp} ({@code =}, {@code <=}, {@code >=}).
 *
 * <p>Arithmetic rules express constraints that logical implication cannot:
 * <ul>
 *   <li><em>Functional</em>: {@code HasType(X, +C) = 1 .} — for each X the truth values
 *       of HasType(X, c) over all constants c must sum to 1.</li>
 *   <li><em>Partial-functional</em>: {@code HasType(X, +C) <= 1 .}</li>
 *   <li><em>Mutual exclusion</em>:
 *       {@code TypeA(X) + TypeB(X) <= 1 .} (no summation variable needed).</li>
 *   <li><em>Symmetry</em>: {@code Knows(A, B) = Knows(B, A) .}</li>
 * </ul>
 *
 * <h3>Summation variables ({@code +X})</h3>
 * A term whose variable is prefixed with {@code +} is a <em>summation variable</em>.
 * During grounding the engine enumerates all bindings of the non-summation variables
 * first (the "outer join"), then, for each outer binding, sums over all constants that
 * can bind to the summation variable to produce a single {@link ArithmeticGroundRule}.
 *
 * <h3>Parsed syntax</h3>
 * <pre>
 *   HasType(X, +C) = 1 .              // hard functional constraint
 *   1.0: HasType(X, +C) <= 1 ^2      // weighted soft partial-functional
 *   TypeA(X) + TypeB(X) <= 1 .       // mutual exclusion (explicit LHS terms)
 *   Knows(A, B) = Knows(B, A) .      // symmetry (predicate on RHS)
 * </pre>
 *
 * @param weight   rule weight (ignored when {@code hard})
 * @param hard     {@code true} for a hard constraint (must be exactly satisfied)
 * @param squared  {@code true} for a squared hinge potential
 * @param lhs      left-hand-side terms (each carries predicate, args, coefficient)
 * @param rhs      right-hand-side terms (atoms/constants); constants are expressed as a
 *                 {@link ArithmeticTerm} whose predicate is {@code null} and coefficient
 *                 is the constant value
 * @param op       relational operator
 * @param filters  optional filter clauses restricting summation-variable domains
 */
public record ArithmeticRule(double weight, boolean hard, boolean squared,
                             List<ArithmeticTerm> lhs, List<ArithmeticTerm> rhs,
                             RelOp op, List<FilterClause> filters) implements Serializable {

    public ArithmeticRule {
        if (Double.isNaN(weight) || weight < 0.0) {
            throw new IllegalArgumentException("Rule weight must be non-negative, got: " + weight);
        }
        if (weight == Double.POSITIVE_INFINITY) {
            hard = true;
            squared = true;
        } else if (hard) {
            weight = Double.POSITIVE_INFINITY;
            squared = true;
        }
        lhs = List.copyOf(lhs);
        rhs = List.copyOf(rhs);
        filters = List.copyOf(filters);
    }

    // ─── Inner types ─────────────────────────────────────────────────────────

    /**
     * One term in an arithmetic rule: {@code coefficient * predicate(args)}.
     * If {@code predicate} is {@code null} this is a numeric constant ({@code coefficient}).
     */
    public record ArithmeticTerm(String predicate, List<Term> args,
                                 double coefficient, boolean summationVariable) implements Serializable {
        public ArithmeticTerm {
            args = (args == null) ? List.of() : List.copyOf(args);
        }

        /** {@code true} when this term represents a numeric constant, not a predicate atom. */
        public boolean isConstant() { return predicate == null; }

        /** The canonical atom key for a fully ground term (no summation variables). */
        public String groundKey(Map<String, String> binding) {
            if (isConstant()) throw new IllegalStateException("Cannot key a numeric constant term");
            StringBuilder sb = new StringBuilder(predicate).append('(');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                Term t = args.get(i);
                String val = (t.variable() && binding.containsKey(t.name())) ? binding.get(t.name()) : t.name();
                sb.append(val);
            }
            return sb.append(')').toString();
        }
    }

    /**
     * A filter clause {@code {V: Predicate(V)}} that restricts the domain of a
     * summation variable to constants for which the given predicate is true.
     */
    public record FilterClause(String variable, PslAtom condition) implements Serializable {}

    // ─── Factory methods ──────────────────────────────────────────────────────

    public static ArithmeticRule hard(List<ArithmeticTerm> lhs, List<ArithmeticTerm> rhs, RelOp op) {
        return new ArithmeticRule(Double.POSITIVE_INFINITY, true, true, lhs, rhs, op, List.of());
    }

    public static ArithmeticRule weighted(double weight, boolean squared,
                                          List<ArithmeticTerm> lhs, List<ArithmeticTerm> rhs, RelOp op) {
        return new ArithmeticRule(weight, false, squared, lhs, rhs, op, List.of());
    }

    // ─── Parser ───────────────────────────────────────────────────────────────

    /**
     * Parse an arithmetic rule string.  The recogniser accepts:
     * <ul>
     *   <li>Optional weight prefix: {@code w:}</li>
     *   <li>Optional exponent suffix: {@code ^2} / {@code ^1}</li>
     *   <li>Hard-constraint marker: trailing {@code .}</li>
     *   <li>Relational operator: {@code =}, {@code <=}, {@code >=}</li>
     *   <li>Summation variables: {@code +X} inside predicate arguments</li>
     *   <li>Scalar numeric RHS/LHS: e.g. {@code 1}, {@code 0.5}</li>
     *   <li>Predicate terms with optional numeric coefficient: {@code 2.0 * Foo(X, Y)}</li>
     * </ul>
     */
    public static ArithmeticRule parse(String text) {
        String s = text.trim();

        // 1. Hard constraint?
        boolean hard = s.endsWith(".");
        if (hard) s = s.substring(0, s.length() - 1).trim();

        // 2. Exponent suffix
        boolean squared = hard; // hard constraints are squared by convention
        if (s.endsWith("^2")) { squared = true; s = s.substring(0, s.length() - 2).trim(); }
        else if (s.endsWith("^1")) { s = s.substring(0, s.length() - 2).trim(); }

        // 3. Filter clauses {V: Pred(V)} at end
        List<FilterClause> filters = new ArrayList<>();
        s = parseFilters(s, filters);

        // 4. Weight prefix
        double weight = 1.0;
        if (!hard) {
            int colon = s.indexOf(':');
            if (colon > 0) {
                String wPart = s.substring(0, colon).trim();
                try { weight = Double.parseDouble(wPart); s = s.substring(colon + 1).trim(); }
                catch (NumberFormatException ignored) { /* no weight prefix */ }
            }
        }

        // 5. Split on relational operator — find the outermost =, <=, >=
        int[] opPos = findRelOp(s);
        if (opPos == null) throw new IllegalArgumentException("No relational operator in arithmetic rule: " + text);
        String lhsStr = s.substring(0, opPos[0]).trim();
        String opStr = s.substring(opPos[0], opPos[1]).trim();
        String rhsStr = s.substring(opPos[1]).trim();

        RelOp op = RelOp.parse(opStr);
        List<ArithmeticTerm> lhs = parseTermList(lhsStr);
        List<ArithmeticTerm> rhs = parseTermList(rhsStr);

        return new ArithmeticRule(weight, hard, squared, lhs, rhs, op, filters);
    }

    /** Find the first relational operator (<=, >=, =) not inside parens; return [start, end] or null. */
    private static int[] findRelOp(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0) {
                if (i + 1 < s.length() && s.charAt(i + 1) == '=' && (c == '<' || c == '>')) {
                    return new int[]{i, i + 2};
                }
                if (c == '=' && (i == 0 || s.charAt(i - 1) != '<' && s.charAt(i - 1) != '>')) {
                    // plain '=' — but not part of <= or >=
                    return new int[]{i, i + 1};
                }
            }
        }
        return null;
    }

    /** Parse a filter section like "{V: Nice(V)} {W: Active(W)}" from the end of text. */
    private static String parseFilters(String text, List<FilterClause> out) {
        // Simple greedy: match all {...} blocks at the right end of the string
        int last = text.length();
        while (last > 0 && text.charAt(last - 1) == '}') {
            int open = text.lastIndexOf('{', last - 1);
            if (open < 0) break;
            String block = text.substring(open + 1, last - 1).trim();
            // block format: "V: Pred(V)"
            int col = block.indexOf(':');
            if (col > 0) {
                String var = block.substring(0, col).trim();
                PslAtom cond = PslAtom.parse(block.substring(col + 1).trim());
                out.add(0, new FilterClause(var, cond));
            }
            last = open;
            // skip whitespace before the '{'
            while (last > 0 && Character.isWhitespace(text.charAt(last - 1))) last--;
        }
        return text.substring(0, last).trim();
    }

    /**
     * Parse a LHS or RHS expression into a list of {@link ArithmeticTerm}s.
     *
     * <p>Supported forms:
     * <ul>
     *   <li>Numeric literal: {@code 1}, {@code 0.5}</li>
     *   <li>Predicate atom: {@code HasType(X, +C)}</li>
     *   <li>Coefficient * atom: {@code 2.0 * HasType(X, C)} or {@code 2.0*HasType(X,C)}</li>
     *   <li>Sum of terms separated by {@code +} or {@code -} (top-level only)</li>
     * </ul>
     */
    static List<ArithmeticTerm> parseTermList(String text) {
        List<ArithmeticTerm> terms = new ArrayList<>();
        // Split into additive tokens at top-level '+' and '-' (but not inside parens)
        List<String> tokens = splitAdditive(text);
        for (String tok : tokens) {
            tok = tok.trim();
            if (tok.isEmpty()) continue;
            terms.add(parseSingleTerm(tok));
        }
        return terms;
    }

    /**
     * Split on top-level '+'/'-' while preserving the sign as part of each token.
     * E.g. "A(X) + B(Y) - 1" → ["A(X)", " + B(Y)", " - 1"] → handled by parseSingleTerm.
     */
    private static List<String> splitAdditive(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && (c == '+' || c == '-') && i > start) {
                // Don't split on a '+' that is the prefix of a term like "+C" (summation var)
                // Only split if there is non-whitespace content before this position
                String before = s.substring(start, i).trim();
                if (!before.isEmpty()) {
                    parts.add(before);
                    start = i;
                }
            }
        }
        parts.add(s.substring(start).trim());
        return parts;
    }

    /** Pattern for optional coefficient: {@code 2.0 * Pred(...)} or {@code 2.0*Pred(...)} */
    private static final Pattern COEF_PATTERN =
            Pattern.compile("^([+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*\\*\\s*(.+)$");

    private static ArithmeticTerm parseSingleTerm(String tok) {
        // Leading sign
        double sign = 1.0;
        if (tok.startsWith("-")) { sign = -1.0; tok = tok.substring(1).trim(); }
        else if (tok.startsWith("+")) { tok = tok.substring(1).trim(); }

        // Explicit coefficient?
        Matcher m = COEF_PATTERN.matcher(tok);
        double coef = sign;
        String rest = tok;
        if (m.matches()) {
            coef = sign * Double.parseDouble(m.group(1));
            rest = m.group(2).trim();
        }

        // Numeric constant?
        try {
            double v = Double.parseDouble(rest);
            return new ArithmeticTerm(null, List.of(), sign * v, false);
        } catch (NumberFormatException ignored) { /* not a plain number */ }

        // Predicate atom, possibly with summation variables
        int lp = rest.indexOf('(');
        if (lp < 0) {
            // bare predicate name (0-arity)
            return new ArithmeticTerm(rest, List.of(), coef, false);
        }
        int rp = rest.lastIndexOf(')');
        if (rp <= lp) throw new IllegalArgumentException("Malformed arithmetic term: " + tok);
        String pred = rest.substring(0, lp).trim();
        String inside = rest.substring(lp + 1, rp).trim();

        List<Term> args = new ArrayList<>();
        boolean hasSummation = false;
        if (!inside.isEmpty()) {
            for (String arg : inside.split(",")) {
                arg = arg.trim();
                boolean summation = arg.startsWith("+");
                if (summation) { arg = arg.substring(1).trim(); hasSummation = true; }
                // Parse the arg as a Term (variable/constant by capitalisation)
                boolean isVar = !arg.isEmpty() && Character.isUpperCase(arg.charAt(0));
                args.add(new Term(arg, isVar));
            }
        }
        return new ArithmeticTerm(pred, args, coef, hasSummation);
    }

    // ─── toString ─────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (!hard) sb.append(trimWeight(weight)).append(": ");
        sb.append(renderTermList(lhs));
        sb.append(' ').append(op).append(' ');
        sb.append(renderTermList(rhs));
        if (!filters.isEmpty()) {
            for (FilterClause f : filters) sb.append(" {").append(f.variable()).append(": ").append(f.condition()).append('}');
        }
        sb.append(hard ? " ." : (squared ? " ^2" : " ^1"));
        return sb.toString();
    }

    private static String renderTermList(List<ArithmeticTerm> terms) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            ArithmeticTerm t = terms.get(i);
            if (i > 0 && t.coefficient() >= 0) sb.append(" + ");
            else if (i > 0) sb.append(" - ");
            if (t.isConstant()) {
                sb.append(i > 0 ? Math.abs(t.coefficient()) : t.coefficient());
            } else {
                double absCoef = Math.abs(t.coefficient());
                if (i == 0 && t.coefficient() < 0) sb.append('-');
                if (absCoef != 1.0) sb.append(trimWeight(absCoef)).append(" * ");
                sb.append(t.predicate()).append('(');
                for (int j = 0; j < t.args().size(); j++) {
                    if (j > 0) sb.append(", ");
                    Term a = t.args().get(j);
                    // Mark summation variables with '+' only if this term has summation
                    if (t.summationVariable() && a.variable()) sb.append('+');
                    sb.append(a.name());
                }
                sb.append(')');
            }
        }
        return sb.toString();
    }

    private static String trimWeight(double w) {
        if (w == Math.rint(w) && !Double.isInfinite(w)) return Long.toString((long) w);
        return Double.toString(w);
    }
}
