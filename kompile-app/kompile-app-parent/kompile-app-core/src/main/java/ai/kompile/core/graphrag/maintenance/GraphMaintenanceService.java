/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.graphrag.maintenance;

import ai.kompile.core.graphrag.conformance.GraphConformanceSummary;
import ai.kompile.core.graphrag.maintenance.model.*;
import java.time.Duration;
import java.util.List;

public interface GraphMaintenanceService {

    // ── Pruning ──
    MaintenanceReport runTtlSweep(Long factSheetId, TtlPolicy policy, boolean dryRun);
    OrphanScanResult findOrphans(Long factSheetId);
    MaintenanceReport pruneOrphans(Long factSheetId, Duration gracePeriod, boolean dryRun);
    MaintenanceReport pruneByConfidence(Long factSheetId, ConfidencePrunePolicy policy, boolean dryRun);
    MaintenanceReport pruneSmallComponents(Long factSheetId, ComponentPrunePolicy policy, boolean dryRun);

    // ── Quality ──
    List<Contradiction> detectContradictions(Long factSheetId);
    MaintenanceReport resolveContradictions(Long factSheetId, ContradictionResolutionStrategy strategy, boolean dryRun);
    MaintenanceReport resolveContradictionsByEdgeSelection(Long factSheetId, List<String> staleEdgeIds, boolean dryRun);
    MaintenanceReport reResolveEntities(Long factSheetId, ReResolutionConfig config, boolean dryRun);
    List<ProvenanceCheck> validateProvenance(Long factSheetId);

    /**
     * Validate a fact sheet's knowledge graph against its bound ontology via the
     * {@link ai.kompile.core.graphrag.conformance.GraphConformanceChecker} SPI, when one is wired.
     * The default returns {@link GraphConformanceSummary#notBound} so implementations and runtime
     * contexts without a conformance checker (e.g. the graph layer running without app-main) are
     * unaffected.
     */
    default GraphConformanceSummary checkOntologyConformance(Long factSheetId) {
        return GraphConformanceSummary.notBound(factSheetId);
    }

    // ── Lifecycle ──
    GraphSnapshot createSnapshot(Long factSheetId, String reason);
    MaintenanceReport restoreSnapshot(String snapshotId);
    List<GraphSnapshot> listSnapshots(Long factSheetId);

    // ── Full maintenance ──
    MaintenanceReport runFullMaintenance(Long factSheetId, MaintenanceSchedule schedule);

    // ── History ──
    List<MaintenanceReport> getMaintenanceHistory(Long factSheetId, int limit);
}
