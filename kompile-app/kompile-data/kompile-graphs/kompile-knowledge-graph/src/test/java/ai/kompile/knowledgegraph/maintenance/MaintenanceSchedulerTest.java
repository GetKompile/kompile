/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.GraphMaintenanceService;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceReport;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MaintenanceScheduler}, focused on the B1 all-fact-sheet mode: the scheduler
 * enumerates fact sheets through the store-agnostic {@link KnowledgeGraphService#findFactSheetIds()}
 * (so it works on the active backend) and isolates per-fact-sheet failures.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaintenanceSchedulerTest {

    @Mock private GraphMaintenanceService maintenanceService;
    @Mock private KnowledgeGraphService knowledgeGraphService;

    private MaintenanceScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MaintenanceScheduler(maintenanceService, knowledgeGraphService);
    }

    @Test
    void disabledSchedulerDoesNothing() {
        scheduler.runScheduledMaintenance();
        verify(maintenanceService, never()).runFullMaintenance(any(), any());
    }

    @Test
    void singleFactSheetModeRunsOnlyThatFactSheet() {
        when(maintenanceService.runFullMaintenance(anyLong(), any())).thenReturn(report());
        scheduler.enable(5L);

        scheduler.runScheduledMaintenance();

        verify(maintenanceService, times(1)).runFullMaintenance(eq(5L), any());
        verify(knowledgeGraphService, never()).findFactSheetIds();
    }

    @Test
    void allFactSheetsModeRunsEachEnumeratedFactSheet() {
        when(knowledgeGraphService.findFactSheetIds()).thenReturn(Set.of(1L, 2L, 3L));
        when(maintenanceService.runFullMaintenance(anyLong(), any())).thenReturn(report());
        scheduler.enableAll();

        scheduler.runScheduledMaintenance();

        verify(maintenanceService).runFullMaintenance(eq(1L), any());
        verify(maintenanceService).runFullMaintenance(eq(2L), any());
        verify(maintenanceService).runFullMaintenance(eq(3L), any());
    }

    @Test
    void allFactSheetsModeIsolatesPerFactSheetFailures() {
        when(knowledgeGraphService.findFactSheetIds()).thenReturn(Set.of(1L, 2L, 3L));
        when(maintenanceService.runFullMaintenance(anyLong(), any())).thenReturn(report());
        when(maintenanceService.runFullMaintenance(eq(2L), any())).thenThrow(new RuntimeException("boom"));
        scheduler.enableAll();

        scheduler.runScheduledMaintenance();

        // The failing fact sheet (2) must not prevent 1 and 3 from being maintained.
        verify(maintenanceService).runFullMaintenance(eq(1L), any());
        verify(maintenanceService).runFullMaintenance(eq(3L), any());
    }

    private static MaintenanceReport report() {
        return new MaintenanceReport("r", 1L, Instant.now(), Instant.now(), false, Map.of(), null, null);
    }
}
