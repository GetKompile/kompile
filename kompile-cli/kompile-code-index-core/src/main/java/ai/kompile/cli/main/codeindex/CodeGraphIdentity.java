/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.codeindex;

import ai.kompile.utils.HashUtils;

import java.util.Objects;
import java.util.Set;

/**
 * Canonical identity shared by local-code-index writers and readers of the projected KGraph.
 */
public final class CodeGraphIdentity {

    private static final Set<String> SIGNATURE_SCOPED_TYPES =
            Set.of("METHOD", "CONSTRUCTOR", "FUNCTION");

    private CodeGraphIdentity() {
    }

    public static String entityId(String projectId, String type, String fullyQualifiedName,
                                  String signature) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fullyQualifiedName, "fullyQualifiedName");
        String identity = declarationIdentity(type, fullyQualifiedName, signature);
        return "code:" + HashUtils.sha256Hex(projectId + "\n" + type + "\n" + identity);
    }

    public static String declarationIdentity(String type, String fullyQualifiedName,
                                             String signature) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fullyQualifiedName, "fullyQualifiedName");
        return SIGNATURE_SCOPED_TYPES.contains(type)
                && signature != null && !signature.isBlank()
                ? fullyQualifiedName + "\n" + signature.replaceAll("\\s+", " ").trim()
                : fullyQualifiedName;
    }
}
