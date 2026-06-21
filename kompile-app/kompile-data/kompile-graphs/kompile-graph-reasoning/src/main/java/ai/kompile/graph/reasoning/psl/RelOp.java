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

/**
 * Relational operator for {@link ArithmeticRule}: equality, less-than-or-equal, or
 * greater-than-or-equal.
 *
 * <p>Arithmetic rules are canonicalised to {@code LHS op RHS}, where the operator is
 * one of the three members of this enum. The rule is satisfied when
 * {@code LHS(y) op RHS(y)} holds. The <em>distance to satisfaction</em> is expressed
 * as {@code max(0, ℓ(y))} where {@code ℓ = LHS − RHS} rearranged so that
 * satisfaction means {@code ℓ ≤ 0}:
 * <ul>
 *   <li>{@link #LEQ}: {@code ℓ = LHS − RHS}; satisfied when {@code LHS ≤ RHS}.</li>
 *   <li>{@link #GEQ}: {@code ℓ = RHS − LHS}; satisfied when {@code LHS ≥ RHS}.</li>
 *   <li>{@link #EQ}:  two half-spaces; the distance is
 *       {@code max(|LHS − RHS| − ε, 0)} — approximated as the maximum of the two
 *       LEQ/GEQ distances for implementation simplicity.</li>
 * </ul>
 */
public enum RelOp {
    /** {@code LHS = RHS} — equality constraint. */
    EQ,
    /** {@code LHS ≤ RHS} — upper-bound constraint. */
    LEQ,
    /** {@code LHS ≥ RHS} — lower-bound constraint. */
    GEQ;

    /** Parse the standard PSL arithmetic operator tokens. */
    public static RelOp parse(String token) {
        return switch (token.trim()) {
            case "=", "==" -> EQ;
            case "<=", "=<" -> LEQ;
            case ">=", "=>" -> GEQ;
            default -> throw new IllegalArgumentException("Unknown relational operator: " + token);
        };
    }

    @Override
    public String toString() {
        return switch (this) {
            case EQ -> "=";
            case LEQ -> "<=";
            case GEQ -> ">=";
        };
    }
}
