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

/**
 * An argument of a {@link PslAtom}: either a logic {@code variable} (e.g. {@code X})
 * or a ground {@code constant} (e.g. a knowledge-graph entity id).
 *
 * <p>Following the PSL/Prolog convention, a token parsed from a rule is treated as a
 * variable when it starts with an upper-case letter and is not quoted; everything else
 * (quoted strings, lower-case tokens, numbers) is a constant.</p>
 *
 * @param name     the identifier (variable name or constant value)
 * @param variable {@code true} for a free variable, {@code false} for a ground constant
 */
public record Term(String name, boolean variable) {

    public static Term var(String name) {
        return new Term(name, true);
    }

    public static Term con(String value) {
        return new Term(value, false);
    }

    /** Parse a single rule token into a Term using the upper-case-first variable convention. */
    public static Term parse(String token) {
        String t = token.trim();
        if (t.length() >= 2 && ((t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'')
                || (t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"'))) {
            return con(t.substring(1, t.length() - 1)); // quoted ⇒ constant
        }
        boolean isVar = !t.isEmpty() && Character.isUpperCase(t.charAt(0));
        return new Term(t, isVar);
    }

    @Override
    public String toString() {
        return name;
    }
}
