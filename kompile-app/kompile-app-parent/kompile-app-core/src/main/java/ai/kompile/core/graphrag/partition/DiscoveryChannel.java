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

/**
 * How a chunk came to be part of an entity partition.
 *
 * <p>Recording the channel is what makes membership auditable: "why is this chunk evidence about
 * Acme?" has an answer, and the answer has a strength. It also fixes processing order — the
 * {@link #priority()} here is deliberately evidence priority, not cost. Authoritative records
 * build the priors first; contradictions are weighed last, once there is something to weigh them
 * against.</p>
 */
public enum DiscoveryChannel {

    /** Explicitly named by the caller — the reason the partition exists at all. */
    SEED(0, "explicitly seeded"),

    /** A human put it here. Trusted like a seed and never silently dropped. */
    MANUAL(0, "manually attached"),

    /** Exact identifier, alias or normalized-name match. The strongest automatic signal. */
    DIRECT_IDENTIFIER(1, "direct identifier or alias match"),

    /** Reached over an existing graph edge or a structured record field. */
    STRUCTURED_RELATIONSHIP(2, "structured relationship in the graph"),

    /** Shares a process instance or case identifier with an already-admitted chunk. */
    PROCESS(3, "same process instance"),

    /** Shares a topic or category assignment. */
    TOPIC(4, "shared topic or category"),

    /** Embedding neighbourhood only — a proposal, not an identification. */
    SEMANTIC(5, "semantic similarity"),

    /**
     * Surfaced because it conflicts with something already admitted. Processed last on purpose:
     * a contradiction is only interpretable against an established prior.
     */
    CONTRADICTION(6, "contradicts admitted evidence");

    private final int priority;
    private final String defaultReason;

    DiscoveryChannel(int priority, String defaultReason) {
        this.priority = priority;
        this.defaultReason = defaultReason;
    }

    /** Evidence priority; lower is more authoritative and is processed earlier. */
    public int priority() {
        return priority;
    }

    /** Human-readable inclusion reason used when a provider supplies none. */
    public String defaultReason() {
        return defaultReason;
    }

    /**
     * Resolves which channel is recorded when the same chunk arrives twice. The more
     * authoritative one is kept, so a chunk that both matches an identifier and merely looks
     * similar is remembered as an identifier match.
     */
    public static DiscoveryChannel strongest(DiscoveryChannel a, DiscoveryChannel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.priority() <= b.priority() ? a : b;
    }
}
