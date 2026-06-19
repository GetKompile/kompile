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
package ai.kompile.core.events;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Read-side SPI for empirical event priors derived from real observations.
 *
 * <p>Implemented by the {@code kompile-event-observation} module and consumed (optionally) by
 * the Bayesian/MEBN attribution layer so that structural priors can be blended with — or
 * replaced by — probabilities learned from how often events are actually observed across
 * crawls. Consumers inject this as an optional bean and fall back to purely structural priors
 * when it is absent or {@link #isEnabled()} is {@code false}.</p>
 *
 * <p>This interface lives in {@code kompile-app-core} (a shared dependency of both the producer
 * and the consumers) so that the attribution modules never depend on the observation module,
 * avoiding a dependency cycle.</p>
 */
public interface EmpiricalPriorSource {

    /** Whether empirical priors are enabled and should be consulted. */
    boolean isEnabled();

    /**
     * Empirical prior {@code P(node is observed/true)} for a KG node, if enough evidence exists.
     *
     * @return the prior in [0,1], or empty when the node has no (sufficient) observation evidence
     */
    OptionalDouble priorForNode(String nodeId);

    /**
     * Empirical prior for a connection — an edge of {@code edgeType} from {@code sourceNodeId} to
     * {@code targetNodeId} — i.e. {@code P(this connection is observed)}, if enough evidence exists.
     *
     * @return the prior in [0,1], or empty when the connection has no (sufficient) observation evidence
     */
    OptionalDouble priorForConnection(String sourceNodeId, String edgeType, String targetNodeId);

    /** Full empirical statistics for a node's occurrence event, if any. */
    Optional<ObservedEventStat> statForNode(String nodeId);

    /**
     * Blend strength {@code k} used when mixing an empirical prior with a structural prior:
     * {@code weight = evidence / (evidence + k)}. Larger k ⇒ more evidence needed before the
     * empirical prior dominates.
     */
    default double blendK() {
        return 5.0;
    }

    /** A disabled, no-op source — useful as a non-null default for consumers. */
    EmpiricalPriorSource DISABLED = new EmpiricalPriorSource() {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public OptionalDouble priorForNode(String nodeId) {
            return OptionalDouble.empty();
        }

        @Override
        public OptionalDouble priorForConnection(String sourceNodeId, String edgeType, String targetNodeId) {
            return OptionalDouble.empty();
        }

        @Override
        public Optional<ObservedEventStat> statForNode(String nodeId) {
            return Optional.empty();
        }
    };
}
