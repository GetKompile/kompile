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

package ai.kompile.utils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Disjoint-set (union-find) with path compression and DETERMINISTIC root election: the cluster
 * representative is always the minimum member under the supplied comparator, so re-running the
 * same unions always elects the same canonical element regardless of union order. This is the
 * shared implementation behind alias canonicalization (WP14b projection, activity alias
 * unification) — anywhere "these keys are the same thing, pick one stable name" comes up.
 *
 * <p>Not thread-safe; build-and-read within one computation.
 */
public final class UnionFind<T> {

    private final Map<T, T> parent = new LinkedHashMap<>();
    private final Comparator<T> rootOrder;

    /** Natural-order root election (e.g. lexicographically-smallest String). */
    public static <C extends Comparable<C>> UnionFind<C> naturalOrder() {
        return new UnionFind<>(Comparator.<C>naturalOrder());
    }

    public UnionFind(Comparator<T> rootOrder) {
        this.rootOrder = rootOrder;
    }

    /** Union two members; the comparator-minimum of the two roots becomes the cluster root. */
    public void union(T a, T b) {
        T rootA = find(a);
        T rootB = find(b);
        if (rootA.equals(rootB)) {
            return;
        }
        if (rootOrder.compare(rootA, rootB) <= 0) {
            parent.put(rootB, rootA);
        } else {
            parent.put(rootA, rootB);
        }
    }

    /** The member's cluster root (itself when never unioned), with path compression. */
    public T find(T x) {
        T p = parent.getOrDefault(x, x);
        if (!p.equals(x)) {
            p = find(p);
            parent.put(x, p);
        }
        return p;
    }

    /** True when no union ever happened. */
    public boolean isEmpty() {
        return parent.isEmpty();
    }

    /**
     * Every touched member → its elected root, INCLUDING the roots themselves (root → root), so
     * lookups over the map need no miss-handling for cluster members.
     */
    public Map<T, T> resolved() {
        if (parent.isEmpty()) {
            return Map.of();
        }
        Map<T, T> canon = new LinkedHashMap<>(Math.max(16, parent.size() * 2));
        for (T member : parent.keySet().stream().toList()) {
            T root = find(member);
            canon.put(member, root);
            canon.putIfAbsent(root, root);
        }
        return canon;
    }
}
