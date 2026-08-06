/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.process.discovery.evaluation;

/** Aggregate threshold-hit rate. An empty expected set is defined as 0/0 = 1.0. */
public record ProcessHitRate(int hits, int expected, double rate) {

    public ProcessHitRate {
        if (hits < 0 || expected < 0 || hits > expected) {
            throw new IllegalArgumentException("Hit counts must satisfy 0 <= hits <= expected");
        }
        double definedRate = expected == 0 ? 1.0 : (double) hits / expected;
        if (Double.compare(rate, definedRate) != 0) {
            throw new IllegalArgumentException("rate must equal hits / expected");
        }
    }

    public static ProcessHitRate of(int hits, int expected) {
        return new ProcessHitRate(hits, expected, expected == 0 ? 1.0 : (double) hits / expected);
    }
}
