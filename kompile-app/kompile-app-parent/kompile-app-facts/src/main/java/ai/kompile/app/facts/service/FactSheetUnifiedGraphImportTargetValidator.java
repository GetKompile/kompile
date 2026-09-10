/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.facts.service;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.UnifiedGraphImportTargetValidator;
import org.springframework.stereotype.Component;

/** Fail-closed destination validation for managed fact-sheet graph imports. */
@Component
public final class FactSheetUnifiedGraphImportTargetValidator
        implements UnifiedGraphImportTargetValidator {

    private final FactSheetService factSheetService;

    public FactSheetUnifiedGraphImportTargetValidator(FactSheetService factSheetService) {
        this.factSheetService = factSheetService;
    }

    @Override
    public void validateTarget(Long factSheetId, UnifiedGraph graph) {
        if (factSheetId == null) return;
        factSheetService.getSheetById(factSheetId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fact sheet does not exist: " + factSheetId));
    }
}
