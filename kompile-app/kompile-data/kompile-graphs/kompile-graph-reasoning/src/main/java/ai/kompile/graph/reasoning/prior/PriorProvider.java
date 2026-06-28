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
package ai.kompile.graph.reasoning.prior;

/**
 * SPI: resolves an informative prior for a random variable or edge strength.
 *
 * <p>Implementations are infra-free (no Spring, no JPA).
 * The reference implementation is {@link CascadePriorProvider}.
 * Callers that do not require richer priors use {@link DefaultPriorProvider#INSTANCE}.</p>
 *
 * <h3>Fixed cascade (implementations SHOULD honour this order)</h3>
 * <ol>
 *   <li><b>Hard finding/evidence</b> — returns {@code 1.0} (TRUE) or {@code 0.0} (FALSE).</li>
 *   <li><b>{@link ai.kompile.graph.reasoning.confidence.OpinionStore}</b>
 *       — {@code opinion.expectation()} when a non-vacuous opinion exists for {@code rvKey}.</li>
 *   <li><b>Embedding geometric prior</b> — when {@link PriorContext#embedding()} is non-null,
 *       the vector's L2 norm maps to a calibrated
 *       {@link ai.kompile.graph.reasoning.confidence.Opinion#fromEmbeddingScore(double, double)} expectation.</li>
 *   <li><b>EmpiricalPriorBlend</b> — Laplace-smoothed type-frequency shrinkage when
 *       {@link PriorContext#typeFrequencies()} is non-null.</li>
 *   <li><b>Temporal decay</b> — {@code P = 0.5 + 0.5·exp(−γ·Δt)} from
 *       {@link PriorContext#lastVerifiedAt()} / {@link PriorContext#occurredAt()}.</li>
 *   <li><b>Uniform 0.5</b> — true last resort (maximum uncertainty).</li>
 * </ol>
 */
public interface PriorProvider {

    /**
     * Prior probability for the random variable identified by {@code rvKey}.
     *
     * @param rvKey grounded variable name (e.g. {@code "isActive(alice)"})
     * @param ctx   lookup hints — never {@code null}; use {@link PriorContext#EMPTY} when no
     *              context is available
     * @return prior in {@code [0.0, 1.0]}
     */
    double priorFor(String rvKey, PriorContext ctx);

    /**
     * Conditional edge strength for the directed edge {@code parentRv → childRv}.
     *
     * @param parentRv parent random variable name
     * @param childRv  child random variable name
     * @param ctx      lookup hints
     * @return causal strength in {@code [0.0, 1.0]}
     */
    double strengthFor(String parentRv, String childRv, PriorContext ctx);
}
