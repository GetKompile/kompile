/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.core.graphrag.conformance;

/**
 * SPI for provisioning a governing ontology for a fact sheet's graph and, when the implementation
 * supports it, materializing schema-level type inferences back onto the graph. Implemented in
 * {@code kompile-app-main} (where ontology derivation + binding live) and consumed by the crawl
 * orchestration during ENRICHMENT - a strict no-op when the implementation bean is absent
 * (contexts without the app-main module on the classpath).
 *
 * <p>This is the seam that lets the crawl's {@code deriveOntology} step derive + bind a structural
 * ontology, then persist entity type memberships and type hierarchy metadata produced from the
 * crawled graph, without the lower crawl modules depending on app-main.</p>
 */
public interface OntologyAutoProvisioner {

    /**
     * Ensure the fact sheet's graph has a governing ontology: derive + bind a structural one when none
     * is bound, then materialize type inferences when available. Idempotent and best-effort -
     * implementations must not throw into the crawl pipeline.
     *
     * @param factSheetId the fact sheet whose graph to provision an ontology for
     */
    void provisionOntology(long factSheetId);
}
