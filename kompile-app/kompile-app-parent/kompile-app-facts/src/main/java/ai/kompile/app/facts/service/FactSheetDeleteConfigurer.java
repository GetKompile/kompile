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
package ai.kompile.app.facts.service;

/**
 * SPI for cleaning up graph and analysis-asset data when a fact sheet is deleted.
 *
 * <p>Declaring this interface here — in the facts module — inverts what would otherwise
 * be a direct {@code facts → kompile-knowledge-graph} dependency, which would create a
 * module cycle. {@link FactSheetService} depends only on this interface (optionally
 * injected), while the concrete implementation lives in kompile-app-main and is wired
 * in by Spring at runtime.  When no implementation is present (e.g. tests that only
 * test the facts module) the optional injection is {@code null} and callers skip graph
 * cleanup.</p>
 */
public interface FactSheetDeleteConfigurer {

    /**
     * Delete all graph segments, analysis-asset snapshots, KB-state, and derived journal
     * files associated with the given fact sheet ID.
     *
     * <p>Implementations MUST be best-effort: they MUST NOT throw checked or unchecked
     * exceptions that would bubble up and abort the enclosing sheet-delete transaction.
     * Any failures should be logged at WARN level.</p>
     *
     * @param factSheetId the ID of the fact sheet that was (or is about to be) deleted
     */
    void deleteGraphDataForSheet(Long factSheetId);
}
