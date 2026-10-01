/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.query;

import ai.kompile.graph.reasoning.psl.BuiltinSimilarityFunctions;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one spelling rule every grounding lookup uses to compare relation predicates.
 *
 * <p>Graphs store relation types in whatever spelling extraction produced ({@code WORKS_FOR},
 * {@code worksFor}, {@code WORKSFOR}, {@code works-for}), and models ask in their own. Two
 * predicates are the same when their {@link #key}s are equal: case is ignored, and so are the
 * separators between words, whether camelCase humps, underscores, spaces, hyphens, or other
 * punctuation. Letters, digits, and combining marks of every script are kept, so non-Latin
 * vocabularies stay distinct. A leading or trailing separator and the {@code NOT_} negation prefix
 * are kept too, so {@code ~worksFor} never collapses into {@code worksFor}, nor {@code NOT_ABLE}
 * into {@code NOTABLE}.</p>
 *
 * <p>{@link #canonical} is the readable form of a predicate ({@code WORKS_FOR}) and {@link #key} its
 * comparison form ({@code WORKSFOR}): match and index on the key, show the canonical form.</p>
 */
public final class PredicateNames {

    /** Minimum normalized edit similarity for a known predicate to be offered as a suggestion. */
    private static final double MIN_SUGGESTION_SIMILARITY = 0.5;

    /** Canonical prefix of a negated relation type, as {@code NOT_WORKS_FOR}. */
    private static final String NEGATION_PREFIX = "NOT_";

    private PredicateNames() {
    }

    /**
     * Upper-case, underscore-separated form of a predicate: {@code worksFor}, {@code works_for},
     * and {@code Works-For} all become {@code WORKS_FOR}. Idempotent; {@code null} becomes "".
     */
    public static String canonical(String name) {
        if (name == null) {
            return "";
        }
        String value = name.trim();
        StringBuilder words = new StringBuilder(value.length() + 8);
        boolean separator = false;
        int previous = -1;
        for (int i = 0; i < value.length(); ) {
            int current = value.codePointAt(i);
            i += Character.charCount(current);
            if (!wordCharacter(current)) {
                separator = true;
                previous = -1;
                continue;
            }
            int next = i < value.length() ? value.codePointAt(i) : -1;
            if (separator || humpBoundary(previous, current, next)) {
                words.append('_');
            }
            words.appendCodePoint(current);
            separator = false;
            previous = current;
        }
        if (separator) {
            words.append('_');
        }
        return words.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * Comparison form of a predicate: its {@link #canonical} form without the separators between
     * words, so {@code worksFor}, {@code WORKS_FOR}, and {@code WORKSFOR} all become
     * {@code WORKSFOR}. Leading and trailing separators and a {@code NOT_} negation prefix are kept,
     * as {@code NOT_WORKSFOR}. Idempotent; {@code null} becomes "".
     */
    public static String key(String name) {
        String canonical = canonical(name);
        int start = 0;
        while (start < canonical.length() && canonical.charAt(start) == '_') {
            start++;
        }
        if (canonical.startsWith(NEGATION_PREFIX, start)
                && canonical.length() > start + NEGATION_PREFIX.length()) {
            start += NEGATION_PREFIX.length();
        }
        int end = canonical.length();
        while (end > start && canonical.charAt(end - 1) == '_') {
            end--;
        }
        return canonical.substring(0, start) + canonical.substring(start, end).replace("_", "")
                + canonical.substring(end);
    }

    /** Whether two predicate spellings name the same relation: their {@link #key}s are equal. */
    public static boolean same(String left, String right) {
        return key(left).equals(key(right));
    }

    /**
     * Known predicates spelled closest to {@code requested}, best first, for a did-you-mean hint.
     * Spellings that share a {@link #key} are reported once. Returns an empty list when nothing is
     * close enough.
     */
    public static List<String> suggestions(String requested, Collection<String> known, int limit) {
        String target = key(requested);
        if (target.isEmpty() || known == null || known.isEmpty() || limit <= 0) {
            return List.of();
        }
        Map<String, String> spellings = new TreeMap<>();
        for (String name : known) {
            if (name != null && !name.isBlank()) {
                String spelling = name.trim();
                spellings.merge(key(spelling), spelling,
                        (kept, candidate) -> kept.compareTo(candidate) <= 0 ? kept : candidate);
            }
        }
        record Candidate(String name, double similarity) {
        }
        return spellings.entrySet().stream()
                .map(entry -> new Candidate(entry.getValue(),
                        BuiltinSimilarityFunctions.NORMALIZED_EDIT.evaluate(target, entry.getKey())))
                .filter(candidate -> candidate.similarity() >= MIN_SUGGESTION_SIMILARITY)
                .sorted(Comparator.comparingDouble(Candidate::similarity).reversed()
                        .thenComparing(Candidate::name))
                .limit(limit)
                .map(Candidate::name)
                .toList();
    }

    /**
     * camelCase word starts: a lower-case letter followed by an upper-case one ({@code worksFor}),
     * or the last capital of an acronym or digit run before a capitalised word
     * ({@code HTTPServer}, {@code ipv4Address}). Only letters with an upper-case form count as
     * lower-case, which keeps {@link #canonical} idempotent.
     */
    private static boolean humpBoundary(int previous, int current, int next) {
        if (previous < 0 || !Character.isUpperCase(current)) {
            return false;
        }
        if (casedLower(previous)) {
            return true;
        }
        return (Character.isUpperCase(previous) || Character.isDigit(previous))
                && next >= 0 && casedLower(next);
    }

    private static boolean casedLower(int codePoint) {
        return Character.isLowerCase(codePoint) && Character.toUpperCase(codePoint) != codePoint;
    }

    /** Letters, digits, and combining marks, so accented and Indic spellings stay one word. */
    private static boolean wordCharacter(int codePoint) {
        if (Character.isLetterOrDigit(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }
}
