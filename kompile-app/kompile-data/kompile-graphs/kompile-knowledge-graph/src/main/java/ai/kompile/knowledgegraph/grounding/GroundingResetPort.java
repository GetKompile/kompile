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
package ai.kompile.knowledgegraph.grounding;

/**
 * Port interface that allows {@code kompile-knowledge-graph} components (e.g.
 * {@link ai.kompile.knowledgegraph.maintenance.SnapshotManager}) to trigger a grounding reset
 * and re-cascade after a graph-replacing operation (snapshot restore) <em>without</em> creating
 * an upward dependency on {@code kompile-graph-change-tracking}.
 *
 * <p>The implementation — {@code GroundingCascadeHook} in {@code kompile-graph-change-tracking}
 * — is wired at runtime via Spring's {@code @Autowired(required = false)} injection.
 * When the graph-change-tracking module is not on the classpath (lightweight deployments),
 * the port is simply absent and callers must null-check before calling.</p>
 */
public interface GroundingResetPort {

    /**
     * Drop the cached KB state for {@code factSheetId} (clearing stale in-memory facts) and
     * schedule a full re-ground cascade with the given {@code trigger} label.
     *
     * <p>The method must be non-blocking: the cascade is submitted to an asynchronous executor
     * and this method returns immediately.</p>
     *
     * @param factSheetId the fact sheet whose KB state should be invalidated and re-grounded
     * @param trigger     human-readable trigger label, e.g. {@code "restore:snap-abc123"}
     */
    void invalidateAndReground(long factSheetId, String trigger);

    /**
     * Schedule a (debounced) re-ground cascade for {@code factSheetId} with a log label and trigger.
     * Non-blocking — submits to an async executor and returns immediately. Exposed on the port so
     * consumers (e.g. the graph-mutation listener) depend on the interface, not the concrete hook —
     * the concrete bean may be a proxy under some runtime configurations.
     *
     * @param factSheetId the fact sheet to re-ground
     * @param logLabel    human-readable label for logging (e.g. {@code "NODE:ENTITY"})
     * @param trigger     trigger category (e.g. {@code GroundingProgressEvent.TRIGGER_CASCADE})
     */
    void schedule(long factSheetId, String logLabel, String trigger);
}
