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
 * SPI: provides PSL rule strings derived from OWL 2 RL entailments for a given fact sheet.
 *
 * <p>This interface lives in {@code kompile-app-core} so that {@code kompile-knowledge-graph}
 * (which depends on app-core) can inject it optionally into
 * {@code IncrementalReasoningOrchestrator} without creating a circular dependency on
 * {@code kompile-app-main}.</p>
 *
 * <p>The implementation in {@code kompile-app-main} ({@code OwlReasoningService}) runs the
 * OWL 2 RL forward-chaining pass over the bound {@link ai.kompile.process.ontology.OntologySchema}
 * and converts entailments (subClassOf transitivity, transitive property closure) into soft PSL
 * rules that the grounding cascade adds on top of the plain DOMAIN/RANGE rules already produced
 * by {@code OntologyToPslRuleCompiler}.</p>
 *
 * <p>Callers MUST use {@code @Autowired(required = false)} / {@code @Nullable} injection so that
 * plain-Java test contexts (which have no app-main beans) are unaffected.</p>
 */
public interface OwlDerivedRuleProvider {

    /**
     * Return PSL rule strings derived from OWL 2 RL entailments over the ontology bound to
     * {@code factSheetId}.
     *
     * <p>Each string is ready to be passed directly to {@code PslProgram.addRule(String)}.
     * The list is empty (never {@code null}) when no ontology is bound or the OWL RL pass
     * produced no additional rules beyond those already compiled by
     * {@code OntologyToPslRuleCompiler}.</p>
     *
     * @param factSheetId the fact sheet whose bound ontology to query
     * @param ruleWeight  soft PSL rule weight to assign (sourced from
     *                    {@code KbConfig.ontologyRuleWeight})
     * @return a possibly-empty list of PSL rule strings
     */
    List<String> owlDerivedPslRules(long factSheetId, double ruleWeight);
}
