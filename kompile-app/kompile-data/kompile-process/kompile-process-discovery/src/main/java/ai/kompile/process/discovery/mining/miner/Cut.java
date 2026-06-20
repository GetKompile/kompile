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

package ai.kompile.process.discovery.mining.miner;

import ai.kompile.process.discovery.mining.tree.ProcessTreeNode;

import java.util.List;
import java.util.Set;

/**
 * The result of cut detection on a directly-follows graph: an {@code operator} and an ordered list of
 * activity {@code partitions} the current sub-log splits into. Order is significant for
 * {@code SEQUENCE} (left-to-right) and {@code LOOP} (partition 0 is the body, the rest are redo paths).
 */
public record Cut(ProcessTreeNode.Operator operator, List<Set<String>> partitions) {

    public boolean isValid() {
        return operator != null && partitions != null && partitions.size() >= 2;
    }
}
