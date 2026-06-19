/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.psl;

import java.util.ArrayList;
import java.util.List;

/**
 * A weighted first-order logic rule of the form {@code weight: body -> head}, the
 * template that PSL grounds over the data to produce {@link GroundRule}s.
 *
 * <p>The body is a conjunction of literals; the head is a disjunction. A rule may
 * instead be a hard constraint (infinite weight, always enforced) or a single-side
 * "prior" rule with an empty body ({@code -> head}, i.e. <i>head should hold</i>) or
 * empty head ({@code body ->}, i.e. <i>body should not hold</i>).</p>
 *
 * <h3>Textual syntax</h3>
 * <pre>
 *   2.0: State(X) &amp; Link(X, Y) -&gt; State(Y) ^2     // weighted, squared hinge
 *   1.0: !Risky(N) ^2                              // negative prior (push toward 0)
 *   State(X) &amp; Link(X, Y) -&gt; State(Y) .            // hard constraint (trailing '.')
 *   3: Knows(A, B) &amp; Knows(B, C) &amp; (A != C) -&gt; Knows(A, C) ^2  // with inequality guard
 * </pre>
 *
 * <p>An omitted exponent means a linear hinge ({@code ^1}); {@code ^2} is the squared
 * hinge recommended for smooth, well-conditioned MAP inference.</p>
 *
 * @param weight   rule weight (ignored when {@code hard}); only relative magnitudes matter
 * @param hard     {@code true} for a hard constraint (effectively infinite weight)
 * @param squared  {@code true} for a squared hinge potential, {@code false} for linear
 * @param body     conjunction of body literals (may be empty)
 * @param head     disjunction of head literals (may be empty)
 * @param distinct pairs of variable names constrained to differ ({@code A != B})
 */
public record PslRule(double weight, boolean hard, boolean squared,
                      List<PslAtom> body, List<PslAtom> head, List<String[]> distinct) {

    public PslRule {
        body = List.copyOf(body);
        head = List.copyOf(head);
        distinct = List.copyOf(distinct);
    }

    public static PslRule weighted(double weight, boolean squared, List<PslAtom> body, List<PslAtom> head) {
        return new PslRule(weight, false, squared, body, head, List.of());
    }

    public static PslRule hard(List<PslAtom> body, List<PslAtom> head) {
        return new PslRule(Double.POSITIVE_INFINITY, true, true, body, head, List.of());
    }

    /**
     * Parse a rule string using the PSL-style syntax documented on this class.
     *
     * @throws IllegalArgumentException if the rule cannot be parsed
     */
    public static PslRule parse(String text) {
        String s = text.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Empty rule");

        // 1. Hard constraint? (trailing '.')
        boolean hard = s.endsWith(".");
        if (hard) s = s.substring(0, s.length() - 1).trim();

        // 2. Exponent (^1 / ^2). Default linear unless explicitly squared.
        boolean squared = false;
        if (s.endsWith("^2")) {
            squared = true;
            s = s.substring(0, s.length() - 2).trim();
        } else if (s.endsWith("^1")) {
            s = s.substring(0, s.length() - 2).trim();
        }

        // 3. Weight prefix "w:" for soft rules.
        double weight = 1.0;
        if (!hard) {
            int colon = s.indexOf(':');
            if (colon > 0) {
                String wPart = s.substring(0, colon).trim();
                try {
                    weight = Double.parseDouble(wPart);
                    s = s.substring(colon + 1).trim();
                } catch (NumberFormatException ignored) {
                    // no numeric weight prefix; leave default and treat ':' as part of body
                }
            }
        }
        if (hard) {
            squared = true; // hard constraints are enforced as squared penalties
        }

        // 4. Split body -> head.
        List<PslAtom> body = new ArrayList<>();
        List<PslAtom> head = new ArrayList<>();
        List<String[]> distinct = new ArrayList<>();
        int arrow = s.indexOf("->");
        String bodyStr;
        String headStr;
        if (arrow >= 0) {
            bodyStr = s.substring(0, arrow).trim();
            headStr = s.substring(arrow + 2).trim();
        } else {
            bodyStr = "";
            headStr = s; // "-> head" form: a fact/prior on the head
        }

        parseConjunction(bodyStr, body, distinct); // body conjunction, '&'
        for (String chunk : splitTop(headStr, '|')) {  // head disjunction, '|'
            String c = chunk.trim();
            if (!c.isEmpty()) head.add(PslAtom.parse(c));
        }

        if (body.isEmpty() && head.isEmpty()) {
            throw new IllegalArgumentException("Rule has neither body nor head: " + text);
        }
        return new PslRule(weight, hard, squared, body, head, distinct);
    }

    private static void parseConjunction(String text, List<PslAtom> out, List<String[]> distinct) {
        for (String chunk : splitTop(text, '&')) {
            String c = chunk.trim();
            if (c.isEmpty()) continue;
            // Strip a single surrounding pair of parens, e.g. "(A != B)".
            if (c.startsWith("(") && c.endsWith(")") && c.indexOf('(', 1) < 0) {
                c = c.substring(1, c.length() - 1).trim();
            }
            if (c.contains("!=")) {
                String[] sides = c.split("!=");
                if (sides.length == 2) {
                    distinct.add(new String[]{sides[0].trim(), sides[1].trim()});
                    continue;
                }
            }
            out.add(PslAtom.parse(c));
        }
    }

    /** Split on a separator char that is not nested inside parentheses. */
    private static List<String> splitTop(String text, char sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (ch == sep && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** All atoms (body then head), used by grounding. */
    public List<PslAtom> allAtoms() {
        List<PslAtom> all = new ArrayList<>(body.size() + head.size());
        all.addAll(body);
        all.addAll(head);
        return all;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (!hard) sb.append(trimWeight(weight)).append(": ");
        String bodyStr = renderBody();
        sb.append(bodyStr);
        if (!bodyStr.isEmpty()) sb.append(" -> ");
        sb.append(renderSide(head, " | "));
        sb.append(hard ? " ." : (squared ? " ^2" : " ^1"));
        return sb.toString();
    }

    /** Render the body conjunction followed by any {@code A != B} guards. */
    private String renderBody() {
        StringBuilder sb = new StringBuilder(renderSide(body, " & "));
        for (String[] d : distinct) {
            if (sb.length() > 0) sb.append(" & ");
            sb.append('(').append(d[0]).append(" != ").append(d[1]).append(')');
        }
        return sb.toString();
    }

    private String renderSide(List<PslAtom> atoms, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < atoms.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(atoms.get(i));
        }
        return sb.toString();
    }

    private static String trimWeight(double w) {
        if (w == Math.rint(w) && !Double.isInfinite(w)) return Long.toString((long) w);
        return Double.toString(w);
    }
}
