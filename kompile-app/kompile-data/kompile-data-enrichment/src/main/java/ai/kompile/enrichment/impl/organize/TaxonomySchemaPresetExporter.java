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
package ai.kompile.enrichment.impl.organize;

import ai.kompile.enrichment.api.TaxonomyPresetCallback;
import ai.kompile.enrichment.domain.DomainTaxonomy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Exports the discovered taxonomy as a graph schema preset
 * via the TaxonomyPresetCallback (implemented in kompile-app-main).
 */
@Service
public class TaxonomySchemaPresetExporter {
    private static final Logger log = LoggerFactory.getLogger(TaxonomySchemaPresetExporter.class);

    @Autowired(required = false)
    private TaxonomyPresetCallback presetCallback;

    public String exportAsPreset(Long factSheetId, DomainTaxonomy taxonomy) {
        if (presetCallback == null) {
            log.info("TaxonomyPresetCallback not available, skipping schema preset export");
            return null;
        }
        try {
            String presetId = presetCallback.saveAsPreset(factSheetId, taxonomy);
            log.info("Exported taxonomy as schema preset '{}' for factSheet {}", presetId, factSheetId);
            return presetId;
        } catch (Exception e) {
            log.error("Failed to export taxonomy as preset for factSheet {}: {}", factSheetId, e.getMessage());
            return null;
        }
    }
}
