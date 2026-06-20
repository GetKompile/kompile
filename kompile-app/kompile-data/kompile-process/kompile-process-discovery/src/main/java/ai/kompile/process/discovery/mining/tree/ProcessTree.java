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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A discovered process tree: a {@link ProcessTreeNode} root plus convenience accessors. Immutable.
 */
public final class ProcessTree {

    private final ProcessTreeNode root;

    public ProcessTree(ProcessTreeNode root) {
        this.root = (root == null) ? ProcessTreeNode.tau() : root;
    }

    public ProcessTreeNode root() {
        return root;
    }

    /** All distinct activity labels appearing as leaves, in encounter order. */
    public Set<String> activities() {
        Set<String> acts = new LinkedHashSet<>();
        collectActivities(root, acts);
        return acts;
    }

    private void collectActivities(ProcessTreeNode node, Set<String> acc) {
        if (node.operator() == ProcessTreeNode.Operator.ACTIVITY) {
            acc.add(node.activity());
            return;
        }
        for (ProcessTreeNode child : node.children()) {
            collectActivities(child, acc);
        }
    }

    @Override
    public String toString() {
        return root.toString();
    }
}
