/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.facts.service;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FactSheetUnifiedGraphImportTargetValidatorTest {

    @Test
    void acceptsExplicitGlobalScopeWithoutFactSheetLookup() {
        FactSheetService sheets = mock(FactSheetService.class);
        FactSheetUnifiedGraphImportTargetValidator validator =
                new FactSheetUnifiedGraphImportTargetValidator(sheets);

        assertDoesNotThrow(() -> validator.validateTarget(null, new UnifiedGraph()));
        verifyNoInteractions(sheets);
    }

    @Test
    void acceptsExistingFactSheetAndRejectsMissingDestination() {
        FactSheetService sheets = mock(FactSheetService.class);
        when(sheets.getSheetById(7L)).thenReturn(Optional.of(mock(FactSheet.class)));
        when(sheets.getSheetById(9L)).thenReturn(Optional.empty());
        FactSheetUnifiedGraphImportTargetValidator validator =
                new FactSheetUnifiedGraphImportTargetValidator(sheets);

        assertDoesNotThrow(() -> validator.validateTarget(7L, new UnifiedGraph()));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateTarget(9L, new UnifiedGraph()));
    }
}
