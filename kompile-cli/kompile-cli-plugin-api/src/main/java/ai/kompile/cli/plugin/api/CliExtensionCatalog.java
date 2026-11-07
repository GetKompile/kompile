/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Validated, immutable metadata snapshot of host-selected extensions.
 * Construction reads each descriptor exactly once and never registers commands,
 * constructs tools, loads classes or installs dependencies. Hosts must construct
 * this catalog successfully before invoking contribution hooks.
 * All exposed collections iterate in identifier order. Installed capabilities are
 * the union of host capabilities and extension provides; they are not model readiness
 * or authorization. Extension requirements are mandatory; tool/action requirements
 * are diagnostic and may remain unmet.
 */
public final class CliExtensionCatalog {
    private final List<CliExtension> extensions;
    private final Map<String, CliExtensionDescriptor> descriptors;
    private final Set<String> capabilities;
    private final Map<String, String> toolOwners;
    private final Map<String, CliToolDescriptor> tools;
    private final Map<CliExtension, CliExtensionDescriptor> instanceDescriptors;

    public CliExtensionCatalog(Collection<? extends CliExtension> extensions, Set<String> hostCapabilities) {
        Objects.requireNonNull(extensions, "Extensions");
        TreeSet<String> available = new TreeSet<>(CliMetadata.ids(hostCapabilities, "Host capability"));
        Map<String, CliExtension> byId = new TreeMap<>();
        Map<String, CliExtensionDescriptor> snapshots = new TreeMap<>();
        Map<String, String> owners = new TreeMap<>();
        Map<String, CliToolDescriptor> toolMetadata = new TreeMap<>();
        Map<String, String> commandOwners = new TreeMap<>();
        Map<CliExtension, CliExtensionDescriptor> captured = new IdentityHashMap<>();

        for (CliExtension extension : extensions) {
            Objects.requireNonNull(extension, "Extension");
            CliExtensionDescriptor descriptor = Objects.requireNonNull(extension.descriptor(), "Extension descriptor");
            if (descriptor.apiVersion() != CliExtensionDescriptor.API_VERSION) {
                throw new IllegalArgumentException("Unsupported extension API version " + descriptor.apiVersion()
                        + " for " + descriptor.id() + "; supported: " + CliExtensionDescriptor.API_VERSION);
            }
            if (snapshots.putIfAbsent(descriptor.id(), descriptor) != null) {
                throw new IllegalArgumentException("Duplicate extension id: " + descriptor.id());
            }
            byId.put(descriptor.id(), extension);
            captured.put(extension, descriptor);
            available.addAll(descriptor.provides());
            for (CliToolDescriptor tool : descriptor.tools()) {
                String previous = owners.putIfAbsent(tool.id(), descriptor.id());
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate tool id " + tool.id()
                            + " contributed by " + previous + " and " + descriptor.id());
                }
                toolMetadata.put(tool.id(), tool);
            }
            for (String command : descriptor.commands()) {
                String previous = commandOwners.putIfAbsent(command, descriptor.id());
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate command name " + command
                            + " contributed by " + previous + " and " + descriptor.id());
                }
            }
        }
        for (CliExtensionDescriptor descriptor : snapshots.values()) {
            TreeSet<String> missing = new TreeSet<>(descriptor.requires());
            missing.removeAll(available);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("Extension " + descriptor.id() + " requires missing capabilities: " + missing);
            }
        }
        this.extensions = List.copyOf(byId.values());
        this.descriptors = Collections.unmodifiableMap(snapshots);
        this.capabilities = Collections.unmodifiableSet(available);
        this.toolOwners = Collections.unmodifiableMap(owners);
        this.tools = Collections.unmodifiableMap(toolMetadata);
        this.instanceDescriptors = Collections.unmodifiableMap(captured);
    }

    /** Host-selected instances in extension-id order; descriptor reads are not repeated. */
    public List<CliExtension> extensions() {
        return extensions;
    }

    public Set<String> capabilities() {
        return capabilities;
    }

    /** Captured metadata for this exact selected instance; never calls its provider again. */
    public CliExtensionDescriptor descriptorOf(CliExtension extension) {
        CliExtensionDescriptor descriptor = instanceDescriptors.get(extension);
        if (descriptor == null) throw new IllegalArgumentException("Extension instance is not in this catalog");
        return descriptor;
    }

    public List<CliExtensionDescriptor> descriptors() {
        return List.copyOf(descriptors.values());
    }

    public Set<String> toolIds() {
        return tools.keySet();
    }

    /** Returns the captured owner descriptor, or empty for a valid but unknown tool id. */
    public Optional<CliExtensionDescriptor> ownerOfTool(String toolId) {
        return Optional.ofNullable(toolOwners.get(CliMetadata.id(toolId, "Tool id"))).map(descriptors::get);
    }

    /**
     * Returns unmet tool requirements plus those of the requested declared action.
     * A null/blank action checks only the tool. A nonblank unknown action is rejected
     * when the tool declares any actions; an empty action map imposes no action restriction.
     *
     * @throws IllegalArgumentException for unknown tools or undeclared named actions
     */
    public Set<String> missingCapabilities(String toolId, String action) {
        CliToolDescriptor tool = tools.get(CliMetadata.id(toolId, "Tool id"));
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool id: " + toolId);
        }
        TreeSet<String> missing = new TreeSet<>(tool.requires());
        if (action != null && !action.isBlank() && !tool.actionRequirements().isEmpty()) {
            Set<String> actionRequirements = tool.actionRequirements().get(action);
            if (actionRequirements == null) {
                throw new IllegalArgumentException("Unknown action " + action + " for tool " + toolId
                        + "; declared actions: " + tool.actionRequirements().keySet());
            }
            missing.addAll(actionRequirements);
        }
        missing.removeAll(capabilities);
        return Collections.unmodifiableSet(missing);
    }
}
