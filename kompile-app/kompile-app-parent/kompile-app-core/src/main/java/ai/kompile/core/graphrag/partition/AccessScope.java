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

package ai.kompile.core.graphrag.partition;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The set of access domains that may read a chunk, and the set a partition run holds.
 *
 * <p>Partitioning pools evidence across sources, which is exactly the operation that leaks a
 * restricted document into an answer built for someone who may not see it. The scope travels
 * with the chunk from discovery onward so the leak is caught at membership time rather than at
 * answer time.</p>
 *
 * <p>An empty scope on a chunk means unrestricted. An empty scope on a reader means it holds no
 * domains, and therefore may read only unrestricted chunks — the safe direction for the default.</p>
 */
public record AccessScope(Set<String> domains) {

    private static final AccessScope UNRESTRICTED = new AccessScope(Set.of());

    public AccessScope {
        if (domains == null || domains.isEmpty()) {
            domains = Set.of();
        } else {
            Set<String> normalized = new TreeSet<>();
            for (String domain : domains) {
                if (domain != null && !domain.isBlank()) {
                    normalized.add(domain.trim());
                }
            }
            domains = Set.copyOf(normalized);
        }
    }

    /** Readable by anyone; the correct scope for genuinely public material only. */
    public static AccessScope unrestricted() {
        return UNRESTRICTED;
    }

    public static AccessScope of(String... domains) {
        // Arrays.asList rather than Set.of: a caller repeating a domain is meaning it twice, not
        // making an error worth throwing over.
        return domains == null ? UNRESTRICTED
                : new AccessScope(new LinkedHashSet<>(Arrays.asList(domains)));
    }

    public static AccessScope of(Collection<String> domains) {
        return domains == null || domains.isEmpty() ? UNRESTRICTED : new AccessScope(new LinkedHashSet<>(domains));
    }

    public boolean isUnrestricted() {
        return domains.isEmpty();
    }

    /**
     * True when a reader holding {@code reader} may read material carrying this scope: either the
     * material is unrestricted, or the reader holds at least one of its domains.
     */
    public boolean isReadableBy(AccessScope reader) {
        if (isUnrestricted()) {
            return true;
        }
        if (reader == null || reader.isUnrestricted()) {
            return false;
        }
        for (String domain : domains) {
            if (reader.domains.contains(domain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Union used when a chunk contributes to a partition: the partition's material is at least as
     * restricted as the most restricted thing in it.
     */
    public AccessScope union(AccessScope other) {
        if (other == null || other.isUnrestricted()) {
            return this;
        }
        if (isUnrestricted()) {
            return other;
        }
        Set<String> merged = new TreeSet<>(domains);
        merged.addAll(other.domains);
        return new AccessScope(merged);
    }

    @Override
    public String toString() {
        return isUnrestricted() ? "unrestricted" : String.join(",", domains);
    }
}
