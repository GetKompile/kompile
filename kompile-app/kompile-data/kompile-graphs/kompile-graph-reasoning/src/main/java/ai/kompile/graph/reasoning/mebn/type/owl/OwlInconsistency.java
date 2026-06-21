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

import java.util.Objects;

/**
 * A constraint violation detected by the OWL RL reasoner (Phase O2) during inference.
 *
 * <p>Introduced in Phase O1 as a component of {@link OwlRlResult}. The most common
 * violation is a {@code cax-dw} (disjoint-class) inconsistency: an individual is typed
 * as two OWL classes that are declared {@code owl:disjointWith} each other.</p>
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@link #ruleId()} — the OWL RL rule identifier (e.g. {@code "cax-dw"}).</li>
 *   <li>{@link #entityId()} — the individual ID in the
 *       {@link ai.kompile.graph.reasoning.model.ReasoningGraph} that is involved in the
 *       violation.</li>
 *   <li>{@link #message()} — a human-readable description of the violation.</li>
 *   <li>{@link #violationDegree()} — a soft-truth score in [0, 1] indicating severity;
 *       1.0 = crisp violation, &lt; 1.0 = partial violation in a PSL soft-rule context.</li>
 * </ul>
 *
 * <p>Instances are immutable value objects. Use {@link #of} to construct them.</p>
 */
public final class OwlInconsistency {

    private final String ruleId;
    private final String entityId;
    private final String message;
    private final double violationDegree;

    private OwlInconsistency(String ruleId, String entityId, String message, double violationDegree) {
        this.ruleId          = Objects.requireNonNull(ruleId,   "ruleId");
        this.entityId        = Objects.requireNonNull(entityId, "entityId");
        this.message         = Objects.requireNonNull(message,  "message");
        if (violationDegree < 0.0 || violationDegree > 1.0) {
            throw new IllegalArgumentException(
                    "violationDegree must be in [0, 1], got: " + violationDegree);
        }
        this.violationDegree = violationDegree;
    }

    /**
     * The OWL RL rule identifier that produced this violation (e.g. {@code "cax-dw"},
     * {@code "cls-avf"}).
     */
    public String ruleId() { return ruleId; }

    /**
     * The ID of the entity in the {@link ai.kompile.graph.reasoning.model.ReasoningGraph}
     * that is the subject of this violation.
     */
    public String entityId() { return entityId; }

    /** A human-readable description of the violation. */
    public String message() { return message; }

    /**
     * The violation degree in [0, 1]:
     * <ul>
     *   <li>1.0 — crisp violation (hard rule violated)</li>
     *   <li>0 &lt; d &lt; 1 — soft violation, penalised by PSL at degree {@code d}</li>
     * </ul>
     */
    public double violationDegree() { return violationDegree; }

    // ─── Factory ─────────────────────────────────────────────────────────────────

    /**
     * Create a crisp (degree=1.0) inconsistency.
     *
     * @param ruleId   the RL rule identifier
     * @param entityId the entity involved in the violation
     * @param message  a human-readable description
     * @return an immutable {@link OwlInconsistency}
     */
    public static OwlInconsistency crisp(String ruleId, String entityId, String message) {
        return new OwlInconsistency(ruleId, entityId, message, 1.0);
    }

    /**
     * Create an inconsistency with an explicit violation degree.
     *
     * @param ruleId          the RL rule identifier
     * @param entityId        the entity involved in the violation
     * @param message         a human-readable description
     * @param violationDegree the degree in [0, 1]
     * @return an immutable {@link OwlInconsistency}
     */
    public static OwlInconsistency of(
            String ruleId, String entityId, String message, double violationDegree) {
        return new OwlInconsistency(ruleId, entityId, message, violationDegree);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OwlInconsistency that)) return false;
        return Double.compare(violationDegree, that.violationDegree) == 0
                && ruleId.equals(that.ruleId)
                && entityId.equals(that.entityId)
                && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruleId, entityId, message, violationDegree);
    }

    @Override
    public String toString() {
        return "OwlInconsistency{rule=" + ruleId
                + ", entity=" + entityId
                + ", degree=" + violationDegree
                + ", msg=" + message
                + "}";
    }
}
