/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api;

import picocli.CommandLine;

/**
 * A host-selected CLI contribution, compatible with build-time/native-image composition.
 * This contract does not imply arbitrary JVM class loading or dependency installation.
 * Hosts must validate a {@link CliExtensionCatalog} before registering commands or
 * constructing contributed tools. Descriptors must be side-effect-free metadata.
 */
public interface CliExtension {
    /** Returns installed-capability metadata, not model readiness or authorization. */
    CliExtensionDescriptor descriptor();

    /** Called explicitly by the host after catalog validation; tools-only extensions may omit it. */
    default void registerCommands(CommandLine root) {
    }
}
