/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Immutable metadata for an installed CLI extension. Capabilities describe installed
 * components only, never model readiness or permission to execute an operation.
 * IDs (including capabilities and command names) are case-sensitive and match
 * {@code [A-Za-z0-9_][A-Za-z0-9._-]*}; versions are nonblank, not necessarily SemVer.
 * Sets and tools iterate in identifier order. Command names identify contributions
 * in a shared host namespace and must be unique across the catalog.
 */
public record CliExtensionDescriptor(String id, String version, int apiVersion,
                                     Set<String> provides, Set<String> requires,
                                     List<CliToolDescriptor> tools, Set<String> commands) {
    /** The sole extension API version currently supported by {@link CliExtensionCatalog}. */
    public static final int API_VERSION = 1;

    public CliExtensionDescriptor {
        id = CliMetadata.id(id, "Extension id");
        version = CliMetadata.nonBlank(version, "Extension version");
        if (apiVersion < 1) {
            throw new IllegalArgumentException("Extension API version must be positive: " + apiVersion);
        }
        provides = CliMetadata.ids(provides, "Provided capability");
        requires = CliMetadata.ids(requires, "Required capability");
        tools = List.copyOf(tools).stream()
                .sorted(Comparator.comparing(CliToolDescriptor::id)).toList();
        commands = CliMetadata.ids(commands, "Command name");
    }
}
