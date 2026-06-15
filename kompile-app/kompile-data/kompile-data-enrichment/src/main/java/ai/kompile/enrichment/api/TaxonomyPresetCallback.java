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
package ai.kompile.enrichment.api;

import ai.kompile.enrichment.domain.DomainTaxonomy;

/**
 * Callback for exporting a discovered taxonomy as a graph schema preset.
 * Implemented in kompile-app-main (where GraphSchemaPresetService lives)
 * to avoid circular dependency.
 */
public interface TaxonomyPresetCallback {

    /**
     * Save the taxonomy as a graph schema preset.
     *
     * @param factSheetId the fact sheet scope
     * @param taxonomy    the discovered taxonomy
     * @return the preset ID that was created/updated
     */
    String saveAsPreset(Long factSheetId, DomainTaxonomy taxonomy);
}
