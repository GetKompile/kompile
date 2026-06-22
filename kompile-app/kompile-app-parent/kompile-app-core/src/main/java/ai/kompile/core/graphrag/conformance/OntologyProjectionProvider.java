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
package ai.kompile.core.graphrag.conformance;

import java.util.List;

/**
 * SPI that projects the ontology bound to a fact sheet into lightweight, infra-free views the
 * extraction / enrichment / reasoning pipeline can consume <b>without</b> depending on
 * process-engine's {@code OntologySchema} model.
 *
 * <p>This is the read side of the same bridge that implements {@link GraphConformanceChecker}:
 * {@code kompile-knowledge-graph} and {@code kompile-crawl-graph} see this interface (via
 * {@code kompile-app-core}) but not the ontology model itself; only {@code kompile-app-main}
 * resolves {@code NamedGraph.ontologySchemaId} to a real schema and implements this.</p>
 *
 * <p><b>Unbound is permissive, never restrictive.</b> When a fact sheet has no bound ontology —
 * or no implementation is wired (tests) — every list method returns empty and
 * {@link #hasBoundOntology(Long)} is {@code false}. Callers MUST treat an empty result as
 * "no ontology governs this fact sheet" (extract/infer freely), never as "deny everything".</p>
 */
public interface OntologyProjectionProvider {

    /** True iff the fact sheet has a bound, resolvable ontology. */
    boolean hasBoundOntology(Long factSheetId);

    /** Allowed entity-type names for the bound ontology; empty when unbound. */
    List<String> allowedEntityTypes(Long factSheetId);

    /** Allowed relationship-type names for the bound ontology; empty when unbound. */
    List<String> allowedRelationshipTypes(Long factSheetId);

    /** DOMAIN/RANGE axioms derived from the bound ontology's relationship types; empty when unbound. */
    List<OntologyAxiom> ontologyAxioms(Long factSheetId);
}
