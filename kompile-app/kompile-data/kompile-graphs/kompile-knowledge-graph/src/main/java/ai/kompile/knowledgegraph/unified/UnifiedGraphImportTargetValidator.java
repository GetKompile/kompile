/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;

/** Validates a resolved unified-graph import destination without mutating live state. */
@FunctionalInterface
public interface UnifiedGraphImportTargetValidator {

    /**
     * Validate the resolved destination. A {@code null} fact-sheet ID denotes the explicit global
     * graph scope. Invalid destinations should throw {@link IllegalArgumentException}.
     */
    void validateTarget(Long factSheetId, UnifiedGraph graph);
}
