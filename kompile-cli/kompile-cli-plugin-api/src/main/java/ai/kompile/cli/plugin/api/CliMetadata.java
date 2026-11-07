/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Shared validation for metadata, not runtime capability or permission checks. */
final class CliMetadata {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

    private CliMetadata() {
    }

    static String id(String value, String label) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must match " + ID.pattern() + ": " + value);
        }
        return value;
    }

    static String nonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    static Set<String> ids(Set<String> values, String label) {
        Objects.requireNonNull(values, label);
        TreeSet<String> copy = new TreeSet<>();
        for (String value : values) {
            copy.add(id(value, label));
        }
        return Collections.unmodifiableSet(copy);
    }
}
