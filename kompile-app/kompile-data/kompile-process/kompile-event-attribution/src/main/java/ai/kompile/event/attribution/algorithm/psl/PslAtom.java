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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A (possibly non-ground) predicate atom with an optional negation, e.g.
 * {@code State(X)}, {@code Link(a, b)}, or {@code ~Risky(N)}.
 *
 * <p>An atom is the unit of soft truth in PSL: when fully ground it denotes a random
 * variable whose value lives in {@code [0, 1]}. The same type is reused as a template
 * (with {@link Term} variables) inside {@link PslRule}s and, once
 * {@link #ground(Map) grounded}, as the identity of a ground atom via {@link #key()}.</p>
 *
 * @param predicate the predicate name (case-sensitive)
 * @param args      ordered arguments
 * @param negated   whether this literal is negated ({@code ~}/{@code !}); truth = {@code 1 - value}
 */
public record PslAtom(String predicate, List<Term> args, boolean negated) {

    public PslAtom {
        args = List.copyOf(args);
    }

    public static PslAtom of(String predicate, boolean negated, Term... args) {
        return new PslAtom(predicate, List.of(args), negated);
    }

    /** Build a ground atom from constant string arguments. */
    public static PslAtom ground(String predicate, String... constants) {
        List<Term> terms = new ArrayList<>(constants.length);
        for (String c : constants) terms.add(Term.con(c));
        return new PslAtom(predicate, terms, false);
    }

    /**
     * Canonical identity of the ground atom, ignoring negation:
     * {@code predicate(arg1, arg2, ...)}. Two literals over the same ground atom
     * (one negated, one not) share a key — they are the same random variable.
     */
    public String key() {
        StringBuilder sb = new StringBuilder(predicate).append('(');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(args.get(i).name());
        }
        return sb.append(')').toString();
    }

    public boolean isGround() {
        for (Term t : args) if (t.variable()) return false;
        return true;
    }

    /** Names of the free variables appearing in this atom (insertion order). */
    public Set<String> variables() {
        Set<String> vars = new LinkedHashSet<>();
        for (Term t : args) if (t.variable()) vars.add(t.name());
        return vars;
    }

    /**
     * Substitute variables using {@code binding}, returning a new atom. Constants are
     * preserved; a variable with no binding is left as-is (the result is then non-ground).
     */
    public PslAtom ground(Map<String, String> binding) {
        List<Term> grounded = new ArrayList<>(args.size());
        for (Term t : args) {
            if (t.variable() && binding.containsKey(t.name())) {
                grounded.add(Term.con(binding.get(t.name())));
            } else {
                grounded.add(t);
            }
        }
        return new PslAtom(predicate, grounded, negated);
    }

    public PslAtom withNegated(boolean neg) {
        return new PslAtom(predicate, args, neg);
    }

    /** Parse a single literal such as {@code ~State(X)} or {@code Link(a, b)} or {@code Active(n)}. */
    public static PslAtom parse(String text) {
        String s = text.trim();
        boolean neg = false;
        while (s.startsWith("~") || s.startsWith("!")) {
            neg = !neg;
            s = s.substring(1).trim();
        }
        int lp = s.indexOf('(');
        if (lp < 0) {
            // 0-arity predicate
            return new PslAtom(s, List.of(), neg);
        }
        int rp = s.lastIndexOf(')');
        if (rp <= lp) {
            throw new IllegalArgumentException("Malformed atom (unbalanced parentheses): " + text);
        }
        String predicate = s.substring(0, lp).trim();
        String inside = s.substring(lp + 1, rp).trim();
        List<Term> terms = new ArrayList<>();
        if (!inside.isEmpty()) {
            for (String tok : inside.split(",")) {
                terms.add(Term.parse(tok));
            }
        }
        return new PslAtom(predicate, terms, neg);
    }

    @Override
    public String toString() {
        return (negated ? "~" : "") + key();
    }
}
