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

/**
 * SPI for validating a fact sheet's knowledge graph against its governing ontology.
 *
 * <p>This is the dependency-inversion seam that lets the knowledge-graph layer (maintenance tasks,
 * write-time hooks) trigger ontology conformance <em>without</em> seeing the {@code OntologySchema}
 * model — that model lives in {@code kompile-process-engine}, a sibling module the graph layer does
 * not depend on. The interface is declared here in {@code kompile-app-core} (which both modules
 * depend on) and implemented in {@code kompile-app-main} (the only module that sees both worlds).
 *
 * <p>Consumers should inject it optionally ({@code @Autowired(required = false)} /
 * {@code ObjectProvider}) so the graph layer still functions when no ontology binding is wired.
 */
public interface GraphConformanceChecker {

    /**
     * Validate the given fact sheet's graph against its bound ontology.
     *
     * @param factSheetId the fact sheet whose graph to check
     * @return a summary; {@link GraphConformanceSummary#ontologyBound()} is {@code false} when nothing is bound
     */
    GraphConformanceSummary checkFactSheet(Long factSheetId);
}
