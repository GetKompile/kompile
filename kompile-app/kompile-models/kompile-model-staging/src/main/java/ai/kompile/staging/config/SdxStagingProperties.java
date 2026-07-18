/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.staging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Host-side SDX compiler settings used only when staging a mobile .kproject.
 *
 * <p>The command is an argument vector, not a shell command. This preserves paths
 * containing spaces and keeps target compilation reproducible and injection-safe.</p>
 */
@Component
@ConfigurationProperties(prefix = "kompile.staging.sdx")
public class SdxStagingProperties {
    private Path cacheDir;
    private List<String> compilerCommand = new ArrayList<>();
    private String compilerId;
    private String compilerVersion;
    private String compilerFingerprint;
    private int maxKnowledgeFiles = 100_000;
    private long maxKnowledgeBytes = 2L * 1024L * 1024L * 1024L;

    public Path getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public List<String> getCompilerCommand() {
        return Collections.unmodifiableList(new ArrayList<>(compilerCommand));
    }

    public void setCompilerCommand(List<String> compilerCommand) {
        this.compilerCommand = compilerCommand == null
                ? new ArrayList<>()
                : new ArrayList<>(compilerCommand);
    }

    public String getCompilerId() {
        return compilerId;
    }

    public void setCompilerId(String compilerId) {
        this.compilerId = compilerId;
    }

    public String getCompilerVersion() {
        return compilerVersion;
    }

    public void setCompilerVersion(String compilerVersion) {
        this.compilerVersion = compilerVersion;
    }

    public String getCompilerFingerprint() {
        return compilerFingerprint;
    }

    public void setCompilerFingerprint(String compilerFingerprint) {
        this.compilerFingerprint = compilerFingerprint;
    }

    public int getMaxKnowledgeFiles() {
        return maxKnowledgeFiles;
    }

    public void setMaxKnowledgeFiles(int maxKnowledgeFiles) {
        this.maxKnowledgeFiles = maxKnowledgeFiles;
    }

    public long getMaxKnowledgeBytes() {
        return maxKnowledgeBytes;
    }

    public void setMaxKnowledgeBytes(long maxKnowledgeBytes) {
        this.maxKnowledgeBytes = maxKnowledgeBytes;
    }
}
