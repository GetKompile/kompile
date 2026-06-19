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
package ai.kompile.app.web.dto.ontology;

import java.util.List;

/**
 * Request to derive an {@code OntologySchema} from the knowledge graph of a fact sheet.
 *
 * <p>A single endpoint serves both derivation modes:</p>
 * <ul>
 *   <li><b>LLM prompt mode</b> — the user picks a fact sheet and (optionally) supplies free-text
 *       {@link #guidance}; the LLM derives the whole schema in one shot.</li>
 *   <li><b>Wizard mode</b> — the user selects grounded {@link #seedEntityTypes} surfaced from the
 *       crawl graph plus structural {@code include*}/{@code focus} options; those constrain the LLM
 *       (or drive a deterministic structural derivation when no LLM is configured).</li>
 * </ul>
 *
 * <p>All fields except {@link #factSheetId} are optional; the service applies sensible defaults.</p>
 *
 * @param factSheetId             the fact sheet whose crawl graph is the derivation source (required)
 * @param name                    desired ontology name; defaults to "{FactSheet} Ontology"
 * @param guidance                free-text instructions for the LLM (LLM-prompt mode)
 * @param maxEntityTypes          upper bound on entity types to emit (default 12)
 * @param includeRelationships    whether to emit relationship types (default true)
 * @param includeValidationRules  whether to emit validation rules (default true)
 * @param focusClassifications    restrict entity types to these {@code EntityClassification} values
 * @param seedEntityTypes         entity-type names the model must define (from the wizard selection)
 * @param maxConcepts             how many top graph concepts to feed as context (default 60)
 * @param modelProvider           provider id to generate with (a CLI agent like {@code "claude-cli"},
 *                                a kompile-hosted/served provider, or {@code "default"}/null for the
 *                                default {@code LLMChat}); resolved via {@code ExtractionLlmServiceRegistry}
 * @param modelName               model id to use within {@link #modelProvider} (null = provider default)
 */
public record DeriveOntologyRequest(
        Long factSheetId,
        String name,
        String guidance,
        Integer maxEntityTypes,
        Boolean includeRelationships,
        Boolean includeValidationRules,
        List<String> focusClassifications,
        List<String> seedEntityTypes,
        Integer maxConcepts,
        String modelProvider,
        String modelName
) {
}
