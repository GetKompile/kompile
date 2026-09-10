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
package ai.kompile.agent.graph;

import java.io.IOException;
import java.util.Objects;

/**
 * Raised after an atomic graph move when the final directory fsync fails.
 *
 * <p>The replacement may already be current. Callers must reread the current revision and compare
 * it with {@link #candidateRevision()} before deciding whether to retry.</p>
 */
public final class IndeterminateAgentGraphCommitException extends IOException {

    private final AgentGraphRevision candidateRevision;

    public IndeterminateAgentGraphCommitException(
            AgentGraphRevision candidateRevision,
            IOException durabilityFailure) {
        super("Agent graph publication is indeterminate after the atomic move; candidate revision "
                + Objects.requireNonNull(candidateRevision, "candidateRevision").sha256()
                + ". Reread the current revision before retrying.", durabilityFailure);
        this.candidateRevision = candidateRevision;
    }

    public AgentGraphRevision candidateRevision() {
        return candidateRevision;
    }
}
