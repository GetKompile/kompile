/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.download;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Immutable-revision Hugging Face repository discovery result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HuggingFaceDiscovery {
    private String repository;
    private String requestedRevision;
    private String resolvedRevision;
    private String requestedPath;
    private String referenceType;
    private TextModelAssetMap discoveredAssets;
    @Builder.Default
    private List<ModelCandidate> modelCandidates = List.of();
    private boolean requiresModelSelection;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelCandidate {
        private String path;
        private long size;
        private String format;
        private String quantizationHint;
    }
}
