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

/**
 * The role an {@link Argument} plays in a {@link Qbaf}.
 *
 * <ul>
 *   <li>{@link #CLAIM} — the central thesis being adjudicated; exactly one per QBAF.</li>
 *   <li>{@link #PRO}   — a supporting argument (acts as a supporter of a target argument).</li>
 *   <li>{@link #CON}   — an attacking argument (acts as an attacker of a target argument).</li>
 * </ul>
 */
public enum ArgKind {
    /** The single conclusion node whose final strength is the verdict. */
    CLAIM,
    /** A supporter: edges from PRO nodes are SUPPORT edges. */
    PRO,
    /** An attacker: edges from CON nodes are ATTACK edges. */
    CON
}
