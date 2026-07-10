/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services;

import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphAnalysisAssetStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FactSheetGraphCleanupService}.
 *
 * <p>Verifies that on sheet deletion, the service orchestrates clean-up of:
 * <ul>
 *   <li>Matrix/vector graph segment (via {@link KnowledgeGraphService#deleteByFactSheetId})</li>
 *   <li>UnifiedGraph analysis-asset snapshot (via {@link UnifiedGraphAnalysisAssetStore#removeForFactSheet})</li>
 *   <li>KB reasoning state AND observed-fact journal (via {@link KbGroundingService#destroyFactSheet})</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FactSheetGraphCleanupService")
class FactSheetGraphCleanupServiceTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;
    @Mock
    private UnifiedGraphAnalysisAssetStore assetStore;
    @Mock
    private KbGroundingService kbGroundingService;

    private FactSheetGraphCleanupService service;

    @BeforeEach
    void setUp() {
        service = new FactSheetGraphCleanupService(knowledgeGraphService, assetStore, kbGroundingService);
    }

    @Test
    @DisplayName("deleteGraphDataForSheet calls deleteByFactSheetId on graph service")
    void deletesGraphSegment() {
        service.deleteGraphDataForSheet(42L);

        verify(knowledgeGraphService).deleteByFactSheetId(42L);
    }

    @Test
    @DisplayName("deleteGraphDataForSheet evicts the analysis-asset snapshot")
    void evictsAssetSnapshot() {
        service.deleteGraphDataForSheet(42L);

        verify(assetStore).removeForFactSheet(42L);
    }

    @Test
    @DisplayName("deleteGraphDataForSheet destroys KB state and journal via destroyFactSheet")
    void destroysKbStateAndJournal() {
        service.deleteGraphDataForSheet(42L);

        verify(kbGroundingService).destroyFactSheet(42L);
    }

    @Test
    @DisplayName("all three cleanup hooks fire for the same factSheetId")
    void allHooksFire() {
        service.deleteGraphDataForSheet(7L);

        verify(knowledgeGraphService).deleteByFactSheetId(7L);
        verify(assetStore).removeForFactSheet(7L);
        verify(kbGroundingService).destroyFactSheet(7L);
        verifyNoMoreInteractions(knowledgeGraphService, assetStore, kbGroundingService);
    }

    @Test
    @DisplayName("null factSheetId is a no-op — does not call any service")
    void nullId_isNoOp() {
        service.deleteGraphDataForSheet(null);

        verifyNoInteractions(knowledgeGraphService, assetStore, kbGroundingService);
    }

    @Test
    @DisplayName("exception from graph-segment delete does not propagate (best-effort)")
    void graphSegmentThrows_doesNotPropagate() {
        doThrow(new RuntimeException("store error")).when(knowledgeGraphService)
                .deleteByFactSheetId(anyLong());

        // Must not throw
        assertDoesNotThrow(() -> service.deleteGraphDataForSheet(5L));

        // Asset store and KB state cleanup should still be attempted
        verify(assetStore).removeForFactSheet(5L);
        verify(kbGroundingService).destroyFactSheet(5L);
    }

    @Test
    @DisplayName("exception from asset-store eviction does not propagate (best-effort)")
    void assetStoreThrows_doesNotPropagate() {
        doThrow(new RuntimeException("evict error")).when(assetStore)
                .removeForFactSheet(anyLong());

        assertDoesNotThrow(() -> service.deleteGraphDataForSheet(5L));

        verify(knowledgeGraphService).deleteByFactSheetId(5L);
        verify(kbGroundingService).destroyFactSheet(5L);
    }

    @Test
    @DisplayName("exception from KB destroy does not propagate (best-effort)")
    void kbDestroyThrows_doesNotPropagate() {
        doThrow(new RuntimeException("destroy error")).when(kbGroundingService)
                .destroyFactSheet(anyLong());

        assertDoesNotThrow(() -> service.deleteGraphDataForSheet(5L));

        verify(knowledgeGraphService).deleteByFactSheetId(5L);
        verify(assetStore).removeForFactSheet(5L);
    }
}
