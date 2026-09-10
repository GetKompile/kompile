/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

/**
 * Explicit persistence compatibility boundary for {@code .kgraph} producers.
 *
 * <p>{@link #PORTABLE_V2} is the deliberate interchange default for external exports and older
 * deployed/mobile readers. {@link #COMPACT_V3} is the canonical local/server format: it adds
 * dictionary-encoded topology plus mmap CSR adjacency and is required for bounded archive-native
 * traversal, global rank, and copy-on-write mutation.</p>
 */
public enum KGraphCompatibilityPolicy {
    PORTABLE_V2(UnifiedGraphFormat.FORMAT_VERSION, false),
    COMPACT_V3(UnifiedGraphFormat.CURRENT_VERSION, true);

    private final int formatVersion;
    private final boolean archiveNative;

    KGraphCompatibilityPolicy(int formatVersion, boolean archiveNative) {
        this.formatVersion = formatVersion;
        this.archiveNative = archiveNative;
    }

    public int formatVersion() { return formatVersion; }

    public boolean supportsArchiveNativeAccess() { return archiveNative; }

    /** The compatibility-safe policy retained by {@link UnifiedGraph#save(java.nio.file.Path)}. */
    public static KGraphCompatibilityPolicy portableDefault() { return PORTABLE_V2; }

    /** The policy for canonical project-local/server persistence. */
    public static KGraphCompatibilityPolicy canonicalLocal() { return COMPACT_V3; }
}
