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
package ai.kompile.app.services;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.enrichment.api.TaxonomyPresetCallback;
import ai.kompile.enrichment.domain.DomainTaxonomy;
import ai.kompile.enrichment.domain.TaxonomyNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the data-enrichment module's TaxonomyPresetCallback to the
 * app-main GraphSchemaPresetService, converting a DomainTaxonomy into
 * a named GraphSchema preset.
 */
@Service
public class TaxonomyPresetCallbackImpl implements TaxonomyPresetCallback {
    private static final Logger log = LoggerFactory.getLogger(TaxonomyPresetCallbackImpl.class);

    private final GraphSchemaPresetService presetService;
    private final ObjectMapper objectMapper;

    public TaxonomyPresetCallbackImpl(GraphSchemaPresetService presetService, ObjectMapper objectMapper) {
        this.presetService = presetService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String saveAsPreset(Long factSheetId, DomainTaxonomy taxonomy) {
        if (taxonomy == null || taxonomy.getTaxonomyJson() == null) return null;

        String presetId = "auto-taxonomy-" + factSheetId + "-v" + taxonomy.getVersion();

        try {
            List<TaxonomyNode> nodes = objectMapper.readValue(
                    taxonomy.getTaxonomyJson(), new TypeReference<List<TaxonomyNode>>() {});

            // Convert taxonomy entity types to GraphSchema NodeTypes
            List<NodeType> nodeTypes = new ArrayList<>();
            for (TaxonomyNode node : nodes) {
                if (node.getLevel() == TaxonomyNode.TaxonomyLevel.ENTITY_TYPE) {
                    NodeType nt = new NodeType();
                    nt.setLabel(node.getLabel());
                    nt.setDescription(node.getDescription() != null ? node.getDescription() : "");
                    nodeTypes.add(nt);
                }
            }

            GraphSchema schema = new GraphSchema();
            schema.setNodeTypes(nodeTypes);

            GraphSchemaPresetService.PresetEntry entry = new GraphSchemaPresetService.PresetEntry();
            entry.id = presetId;
            entry.name = "Auto-Taxonomy (FactSheet " + factSheetId + " v" + taxonomy.getVersion() + ")";
            entry.description = "Auto-generated from taxonomy discovery";
            entry.version = taxonomy.getVersion();
            entry.schema = schema;

            presetService.savePreset(presetId, entry);
            log.info("Saved taxonomy preset '{}' with {} node types", presetId, nodeTypes.size());
            return presetId;
        } catch (Exception e) {
            log.error("Failed to save taxonomy as preset: {}", e.getMessage());
            return null;
        }
    }
}
