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
package ai.kompile.graph.reasoning.embedding.kge;

/**
 * SPI for scoring {@code (head, relationType, tail)} triples with a learned
 * Knowledge Graph Embedding (KGE) model (e.g. RotatE, TransE, DistMult).
 *
 * <h3>Score semantics</h3>
 * <p>Implementations are responsible for converting the model-native distance or
 * energy (lower = more plausible in RotatE/TransE) into a calibrated
 * <em>plausibility score</em> in {@code [0, 1]} where {@code 1.0} = maximally
 * plausible and {@code 0.0} = impossible or completely unknown.
 * The default calibration formula used by {@link LinkPredictorKgeScorer} is
 * {@code score = 1 / (1 + distance)} (Platt-style inverse over L2 distance).</p>
 *
 * <h3>Consumption paths</h3>
 * <ul>
 *   <li><b>PSL per-grounding</b> — {@link KgeTripleScoreFunction} wraps this SPI as an
 *       {@link ai.kompile.graph.reasoning.psl.ExternalFunction} so rule bodies can reference
 *       {@code TripleScore(H, R, T)} inline.  Evaluated at grounding time for every candidate
 *       constant tuple produced by the other bound variables in the rule body.</li>
 *   <li><b>PSL bulk pre-score</b> — {@link KgePslBulkObserver} calls {@link #scoreTriple}
 *       up front for all candidate triples above a threshold and registers them as
 *       {@linkplain ai.kompile.graph.reasoning.psl.PslProgram#observe observed} atoms.
 *       Avoids per-grounding latency when the scorer requires IPC (subprocess / network).</li>
 *   <li><b>MEBN / OpinionStore</b> — {@link KgeOpinionStoreBridge} writes the score as
 *       {@link ai.kompile.graph.reasoning.confidence.Opinion#fromEmbeddingScore fromEmbeddingScore}
 *       into an {@link ai.kompile.graph.reasoning.confidence.OpinionStore}, available to
 *       {@link ai.kompile.graph.reasoning.prior.CascadePriorProvider} at tier (b) without any
 *       changes to MEBN / SSBNGenerator internals.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>Implementations MUST be safe for concurrent evaluation — the grounding engine may
 * call {@link #scoreTriple} from multiple threads simultaneously.</p>
 *
 * @see LinkPredictorKgeScorer
 * @see StubKgeTripleScorer
 * @see KgeTripleScoreFunction
 * @see KgePslBulkObserver
 * @see KgeOpinionStoreBridge
 */
public interface KgeTripleScorer {

    /**
     * Compute the calibrated plausibility score for the triple
     * {@code (headId, relationType, tailId)}.
     *
     * @param headId       head entity identifier
     * @param relationType relation type string
     * @param tailId       tail entity identifier
     * @return plausibility in {@code [0, 1]}; {@code 1.0} = most plausible,
     *         {@code 0.0} = impossible / entirely unknown triple
     */
    double scoreTriple(String headId, String relationType, String tailId);

    /**
     * Returns {@code true} when all three identifiers are known to this scorer.
     *
     * <p>A scorer that receives unknown ids in {@link #scoreTriple} SHOULD return
     * {@code 0.0} rather than throwing.  Callers may use this guard to skip triples
     * that cannot be scored rather than relying on the {@code 0.0} sentinel.</p>
     *
     * @param headId       head entity identifier
     * @param relationType relation type string
     * @param tailId       tail entity identifier
     * @return {@code true} if all ids are known; {@code false} otherwise
     */
    boolean knows(String headId, String relationType, String tailId);
}
