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

package ai.kompile.process.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Declares what data sources an {@link AgentSpec} may read from, write to, or never touch.
 * The {@code neverWrite} list is an explicit deny and takes precedence over {@code writeSources}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentPermissions {

    /** Data source identifiers the agent is allowed to read. */
    private List<String> readSources;
    /** Data source identifiers the agent is allowed to modify. */
    private List<String> writeSources;
    /**
     * Explicit deny list — agent must never write to these sources.
     * Example: "source_workbook" (original submission is immutable).
     */
    private List<String> neverWrite;
    /** Maximum number of tool actions the agent may perform in a single run. */
    private int maxActionsPerRun;
    /**
     * The agent may autonomously modify values only if their absolute magnitude
     * is below this threshold (in base currency units).
     */
    private int dollarThreshold;
}
