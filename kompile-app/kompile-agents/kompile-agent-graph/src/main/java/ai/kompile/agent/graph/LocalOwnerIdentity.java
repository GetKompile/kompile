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

import java.util.Objects;
import java.util.UUID;

/**
 * Stable server-owned scope for local private-graph agents.
 *
 * <p>The UUID is deliberately not an authentication secret. Authentication remains the concern of
 * the server control boundary; this value only partitions durable agent state after that boundary
 * has established trust.</p>
 */
public record LocalOwnerIdentity(UUID ownerId) {

    public LocalOwnerIdentity {
        Objects.requireNonNull(ownerId, "ownerId");
    }
}
