/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.argument;

import java.util.Objects;

/**
 * A node in a {@link Qbaf} (Quantitative Bipolar Argumentation Framework).
 *
 * <p>Each argument has a unique {@code id}, a human-readable {@code label}, a
 * {@code baseScore} in [0, 1] representing its intrinsic strength before considering
 * attack/support from other arguments, and an {@link ArgKind} role.</p>
 *
 * @param id        unique identifier within a QBAF (non-null, non-blank)
 * @param label     human-readable description (non-null)
 * @param baseScore intrinsic strength before dialectical influence, in [0, 1]
 * @param kind      the role this argument plays ({@link ArgKind#CLAIM}, {@link ArgKind#PRO},
 *                  or {@link ArgKind#CON})
 */
public record Argument(String id, String label, double baseScore, ArgKind kind) {

    public Argument {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        if (baseScore < 0.0 || baseScore > 1.0) {
            throw new IllegalArgumentException("baseScore must be in [0,1], got " + baseScore);
        }
    }

    /** Convenience factory for a CLAIM node with the given base score. */
    public static Argument claim(String id, String label, double baseScore) {
        return new Argument(id, label, baseScore, ArgKind.CLAIM);
    }

    /** Convenience factory for a PRO (supporting) argument. */
    public static Argument pro(String id, String label, double baseScore) {
        return new Argument(id, label, baseScore, ArgKind.PRO);
    }

    /** Convenience factory for a CON (attacking) argument. */
    public static Argument con(String id, String label, double baseScore) {
        return new Argument(id, label, baseScore, ArgKind.CON);
    }
}
