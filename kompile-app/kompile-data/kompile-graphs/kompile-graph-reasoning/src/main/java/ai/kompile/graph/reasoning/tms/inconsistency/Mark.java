/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms.inconsistency;

/**
 * Belnap 4-valued truth marking for a ground atom (Belnap 1977, "A useful four-valued logic").
 *
 * <p>The four values correspond to the information a paraconsistent knowledge base provides about
 * a ground atom:</p>
 * <ul>
 *   <li>{@link #T} — the atom is supported with strong positive evidence (value ≥ threshold) and
 *       no strong negative or conflicting evidence.</li>
 *   <li>{@link #F} — the atom has only strong negated/refuting evidence and no positive support.</li>
 *   <li>{@link #B} — the atom has strong evidence for <em>both</em> it and its negation ("both" /
 *       over-determined); this is the paraconsistent case where classical logic would derive
 *       everything, but B-logic allows the contradiction to be isolated.</li>
 *   <li>{@link #N} — neither strong positive nor strong negative evidence exists for the atom
 *       ("neither" / under-determined); it sits in the gap.</li>
 * </ul>
 *
 * <p>The standard lattice order on information content is N ≤ T, N ≤ F, T ≤ B, F ≤ B;
 * and on truth B ≤ T, B ≤ F, F ≤ N, T ≤ N (the bilattice FOUR).</p>
 *
 * <p>See: Belnap, "A useful four-valued logic", in Dunn &amp; Epstein (eds.), <em>Modern Uses of
 * Multiple-Valued Logic</em>, 1977. For the inconsistency-measure context, see
 * Hunter &amp; Konieczny, "Measuring Inconsistency through Minimal Inconsistent Sets",
 * KR 2008 / AIJ 2010; and Thimm, <em>Inconsistency Measurement</em>, 2019.</p>
 */
public enum Mark {

    /**
     * True — atom has strong positive evidence only.
     */
    T,

    /**
     * False — atom has strong negated/refuting evidence only.
     */
    F,

    /**
     * Both — atom has strong evidence for both itself and its negation (paraconsistent conflict).
     */
    B,

    /**
     * Neither — no strong evidence in either direction.
     */
    N
}
