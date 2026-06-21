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
package ai.kompile.graph.reasoning.mebn.type.owl;

import ai.kompile.graph.reasoning.model.ReasoningGraph;

/**
 * The unified entry-point for OWL reasoning over a
 * {@link ReasoningGraph} (ABox) given an {@link OwlOntology} (TBox).
 *
 * <p>Two implementations are planned:</p>
 * <ul>
 *   <li><strong>{@code OwlRlReasoner} (Phase O2, lib-internal)</strong> — compiles the TBox
 *       into {@link ai.kompile.graph.reasoning.fol.FolRule FolRule}s and runs them through
 *       the existing {@link ai.kompile.graph.reasoning.fol.FolInferenceService}, covering
 *       the OWL 2 RL entailment rules listed in §3.2 of the design document.</li>
 *   <li><strong>{@code OWLDLReasoningBridge} (Phase O4, client module)</strong> — delegates
 *       to HermiT or Openllet for full OWL DL reasoning, returning results in the same
 *       {@link OwlRlResult} format. Lives in {@code kompile-reasoning-owl-bridge}, which may
 *       depend on {@code org.semanticweb.owlapi}.</li>
 * </ul>
 *
 * <p>Callers (e.g. {@code KnowledgeGraphReasoningAdapter}) accept an {@code OwlReasoner}
 * — the DI container injects either implementation based on which module is on the classpath,
 * allowing transparent swap between lib-level RL inference and full OWL DL inference.</p>
 *
 * <p>This interface lives in {@code kompile-graph-reasoning} (infra-free) so that both
 * implementations share a common contract without the lib needing to depend on the client module.
 * </p>
 */
public interface OwlReasoner {

    /**
     * Run OWL inference over {@code graph} (the ABox) given {@code ontology} (the TBox).
     *
     * <p>Implementations must be pure: they must not mutate either argument. All inferred
     * facts are returned in the {@link OwlRlResult}; materialisation into the graph is
     * the caller's responsibility.</p>
     *
     * @param graph    the ABox: individuals, their types, and their relations (never {@code null})
     * @param ontology the TBox: class and property declarations (never {@code null})
     * @return a non-{@code null} result containing inferred relations, inferred types,
     *         and any detected inconsistency violations
     */
    OwlRlResult reason(ReasoningGraph graph, OwlOntology ontology);
}
