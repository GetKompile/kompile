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

package ai.kompile.process.discovery.mining.tree;

import java.util.List;

/**
 * A node in a process tree — the block-structured model the Inductive Miner produces.
 *
 * <p>A process tree is sound by construction: every internal node is one of four operators and every
 * leaf is either an activity or a silent step ({@code TAU}). Because the structure is blocks within
 * blocks, it can never deadlock and has no dead parts, which is exactly why it is safe to convert into
 * an executable {@code ProcessDefinition}.
 *
 * <ul>
 *   <li>{@code SEQUENCE} (→) — children execute in order.</li>
 *   <li>{@code XOR} (×) — exactly one child executes (exclusive choice).</li>
 *   <li>{@code AND} (∧) — all children execute concurrently, in any interleaving.</li>
 *   <li>{@code LOOP} (↺) — first child is the body (executed at least once); the remaining children
 *       are "redo" paths, giving {@code body (redo body)*}.</li>
 *   <li>{@code ACTIVITY} — a leaf carrying an activity label.</li>
 *   <li>{@code TAU} (τ) — a silent leaf, used to model skips/optionality.</li>
 * </ul>
 */
public final class ProcessTreeNode {

    public enum Operator {
        SEQUENCE("→"), // →
        XOR("×"),      // ×
        AND("∧"),      // ∧
        LOOP("↺"),     // ↺
        ACTIVITY(""),
        TAU("τ");      // τ

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    private final Operator operator;
    private final String activity;
    private final List<ProcessTreeNode> children;

    private ProcessTreeNode(Operator operator, String activity, List<ProcessTreeNode> children) {
        this.operator = operator;
        this.activity = activity;
        this.children = (children == null) ? List.of() : List.copyOf(children);
    }

    public static ProcessTreeNode activity(String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("activity label must be non-blank");
        }
        return new ProcessTreeNode(Operator.ACTIVITY, label, List.of());
    }

    public static ProcessTreeNode tau() {
        return new ProcessTreeNode(Operator.TAU, null, List.of());
    }

    public static ProcessTreeNode sequence(List<ProcessTreeNode> children) {
        return new ProcessTreeNode(Operator.SEQUENCE, null, children);
    }

    public static ProcessTreeNode xor(List<ProcessTreeNode> children) {
        return new ProcessTreeNode(Operator.XOR, null, children);
    }

    public static ProcessTreeNode and(List<ProcessTreeNode> children) {
        return new ProcessTreeNode(Operator.AND, null, children);
    }

    public static ProcessTreeNode loop(List<ProcessTreeNode> children) {
        return new ProcessTreeNode(Operator.LOOP, null, children);
    }

    public Operator operator() {
        return operator;
    }

    /** The activity label; non-null only for {@code ACTIVITY} nodes. */
    public String activity() {
        return activity;
    }

    public List<ProcessTreeNode> children() {
        return children;
    }

    public boolean isLeaf() {
        return operator == Operator.ACTIVITY || operator == Operator.TAU;
    }

    /** Renders standard process-tree notation, e.g. {@code →(a, ×(∧(b, c), e), d)}. */
    @Override
    public String toString() {
        if (operator == Operator.ACTIVITY) {
            return activity;
        }
        if (operator == Operator.TAU) {
            return operator.symbol();
        }
        StringBuilder sb = new StringBuilder(operator.symbol()).append('(');
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(children.get(i));
        }
        return sb.append(')').toString();
    }
}
