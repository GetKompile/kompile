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

package ai.kompile.app.services.placement;

/**
 * Estimated throughput (items or tokens per second) for a (serviceType, execution-target) pair.
 * Real impl draws on measured rates, and MUST discard/deweight samples taken while the device is
 * in a degraded power state (see plan §Operational — a rate measured at 1/3 clock is ~3× too low).
 * Test impl returns fixed rates so the decision is deterministic.
 */
@FunctionalInterface
public interface ThroughputOracle {

    /** Where a candidate would execute — governs the rate lookup. */
    enum ExecTarget { CLI, LOCAL_GPU, LOCAL_CPU }

    double ratePerSec(String serviceType, ExecTarget target);
}
