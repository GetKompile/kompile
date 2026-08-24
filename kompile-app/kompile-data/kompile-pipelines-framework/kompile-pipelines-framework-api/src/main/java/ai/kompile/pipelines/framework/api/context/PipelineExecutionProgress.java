/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipelines.framework.api.context;

import java.util.LinkedHashMap;
import java.util.Map;

/** A provider-neutral progress snapshot emitted by a running pipeline step. */
public record PipelineExecutionProgress(
        String phase,
        int progressPercent,
        String currentStep,
        String message,
        Integer currentPage,
        Integer totalPages,
        Map<String, Object> metrics) {

    public PipelineExecutionProgress {
        phase = phase == null || phase.isBlank() ? "EXECUTING" : phase.trim();
        progressPercent = Math.max(0, Math.min(100, progressPercent));
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    /** Convert to the extensible v1 pipeline-runtime PROGRESS payload. */
    public Map<String, Object> toMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        payload.put("percent", progressPercent);
        payload.put("progressPercent", progressPercent);
        if (currentStep != null && !currentStep.isBlank()) payload.put("currentStep", currentStep);
        if (message != null && !message.isBlank()) payload.put("message", message);
        if (currentPage != null) payload.put("currentPage", currentPage);
        if (totalPages != null) payload.put("totalPages", totalPages);
        if (!metrics.isEmpty()) payload.put("metrics", metrics);
        return Map.copyOf(payload);
    }
}
