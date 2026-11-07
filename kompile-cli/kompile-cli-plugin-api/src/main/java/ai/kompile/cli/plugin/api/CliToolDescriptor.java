/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable installed-capability requirements for a tool and its declared actions.
 * Tool, action and capability IDs use the syntax documented by {@link CliExtensionDescriptor}.
 * A nonempty action map declares the complete set of named actions. Requirements may
 * be unmet at catalog creation so the host can diagnose unavailable actions.
 * Neither these requirements nor their satisfaction grant execution authorization
 * or establish model readiness. Maps and sets iterate in identifier order.
 */
public record CliToolDescriptor(String id, Set<String> requires,
                                Map<String, Set<String>> actionRequirements,
                                Map<String, Set<String>> conditionalCapabilities) {
    public CliToolDescriptor(String id, Set<String> requires,
                             Map<String, Set<String>> actionRequirements) {
        this(id, requires, actionRequirements, Map.of());
    }

    public CliToolDescriptor {
        id = CliMetadata.id(id, "Tool id");
        requires = CliMetadata.ids(requires, "Required tool capability");
        actionRequirements = copyActions(actionRequirements, "Action requirements");
        conditionalCapabilities = copyActions(conditionalCapabilities, "Conditional capabilities");
    }

    /** Capabilities that configuration/background work may use, not mandatory prerequisites. */
    private static Map<String, Set<String>> copyActions(Map<String, Set<String>> values, String label) {
        Objects.requireNonNull(values, label);
        Map<String, Set<String>> copy = new TreeMap<>();
        values.forEach((action, requirements) -> copy.put(
                CliMetadata.id(action, "Action name"),
                CliMetadata.ids(requirements, "Required action capability")));
        return Collections.unmodifiableMap(copy);
    }
}
