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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Built-in {@link ExternalFunction} implementations for string and set similarity,
 * following the functions used in the PSL knowledge-graph-identification examples
 * (Pujara et al., ISWC 2013) and the Probabilistic Similarity Logic paper
 * (Broecheler et al., UAI 2010).
 *
 * <p>All functions take exactly the number of {@code String} arguments indicated by their
 * arity, return a value in {@code [0, 1]}, and are pure (stateless, thread-safe).</p>
 *
 * <h3>Available functions</h3>
 * <ul>
 *   <li>{@link #JACCARD} — Jaccard similarity on whitespace-split token sets (2-arity)</li>
 *   <li>{@link #JARO_WINKLER} — Jaro-Winkler string similarity (2-arity)</li>
 *   <li>{@link #COSINE_BINARY} — Cosine similarity on whitespace-split token sets (2-arity);
 *       equivalent to Dice for binary feature vectors</li>
 *   <li>{@link #NORMALIZED_EDIT} — Normalised edit (Levenshtein) distance → similarity (2-arity)</li>
 * </ul>
 */
public final class BuiltinSimilarityFunctions {

    private BuiltinSimilarityFunctions() {}

    // ─── Jaccard ─────────────────────────────────────────────────────────────

    /**
     * Jaccard similarity on whitespace-tokenised sets: {@code |A ∩ B| / |A ∪ B|}.
     * Returns {@code 0} when both sets are empty.
     *
     * <p>Example: {@code "cat dog bird"} vs {@code "dog bird fish"} → 2/4 = 0.5</p>
     */
    public static final ExternalFunction JACCARD = args -> {
        requireArity(args, 2, "Jaccard");
        Set<String> a = tokenSet(args[0]);
        Set<String> b = tokenSet(args[1]);
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    };

    // ─── Jaro-Winkler ─────────────────────────────────────────────────────────

    /**
     * Jaro-Winkler string similarity in {@code [0, 1]}.
     *
     * <p>The Jaro measure counts matching characters (within a window of
     * {@code max(|s|,|t|)/2 − 1}) and transpositions; Winkler adds a prefix bonus of
     * up to 4 characters with boost factor {@code p = 0.1}.</p>
     */
    public static final ExternalFunction JARO_WINKLER = args -> {
        requireArity(args, 2, "JaroWinkler");
        return jaroWinkler(args[0], args[1]);
    };

    private static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;
        int matchWindow = Math.max(0, Math.max(s1.length(), s2.length()) / 2 - 1);
        boolean[] m1 = new boolean[s1.length()];
        boolean[] m2 = new boolean[s2.length()];
        int matches = 0;
        for (int i = 0; i < s1.length(); i++) {
            int lo = Math.max(0, i - matchWindow);
            int hi = Math.min(s2.length() - 1, i + matchWindow);
            for (int j = lo; j <= hi; j++) {
                if (!m2[j] && s1.charAt(i) == s2.charAt(j)) {
                    m1[i] = m2[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) return 0.0;
        double trans = 0;
        int k = 0;
        for (int i = 0; i < s1.length(); i++) {
            if (!m1[i]) continue;
            while (!m2[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) trans++;
            k++;
        }
        double jaro = (matches / (double) s1.length()
                + matches / (double) s2.length()
                + (matches - trans / 2.0) / matches) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(s1.length(), s2.length())); i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    // ─── Cosine (binary / Dice-equivalent) ───────────────────────────────────

    /**
     * Cosine similarity on binary (0/1) token-occurrence feature vectors, equivalent to
     * the Dice coefficient: {@code 2 |A ∩ B| / (|A| + |B|)}.
     *
     * <p>Returns {@code 0} when either set is empty.</p>
     */
    public static final ExternalFunction COSINE_BINARY = args -> {
        requireArity(args, 2, "CosineBinary");
        Set<String> a = tokenSet(args[0]);
        Set<String> b = tokenSet(args[1]);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return 2.0 * intersection.size() / (a.size() + b.size());
    };

    // ─── Normalised edit distance ─────────────────────────────────────────────

    /**
     * Normalised edit (Levenshtein) similarity: {@code 1 - distance / max(|s|, |t|)}.
     * Returns {@code 0.0} when both are empty or completely different;
     * {@code 1.0} when the strings are equal and non-empty.
     */
    public static final ExternalFunction NORMALIZED_EDIT = args -> {
        requireArity(args, 2, "NormalizedEdit");
        String s = args[0];
        String t = args[1];
        int maxLen = Math.max(s.length(), t.length());
        if (maxLen == 0) return 0.0;  // both empty → no similarity evidence
        if (s.equals(t)) return 1.0;
        return 1.0 - (double) levenshtein(s, t) / maxLen;
    };

    private static int levenshtein(String s, String t) {
        int m = s.length(), n = t.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = s.charAt(i - 1) == t.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Set<String> tokenSet(String s) {
        Set<String> set = new HashSet<>();
        for (String tok : s.trim().split("\\s+")) {
            if (!tok.isEmpty()) set.add(tok.toLowerCase());
        }
        return set;
    }

    private static void requireArity(String[] args, int expected, String name) {
        if (args.length != expected) {
            throw new IllegalArgumentException(name + " requires " + expected
                    + " arguments but got " + args.length);
        }
    }
}
