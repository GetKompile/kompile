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

package ai.kompile.filterchain.executor;

import ai.kompile.core.filter.FilterContext;
import ai.kompile.core.filter.FilterPhase;
import ai.kompile.core.filter.FilterResult;
import ai.kompile.filterchain.config.FilterConfig;

/**
 * Interface for filter executors.
 * Each executor handles a specific filter type (LOCAL, HTTP, MCP).
 */
public interface FilterExecutor {

    /**
     * Execute a filter.
     *
     * @param config The filter configuration
     * @param context The filter context
     * @param phase The current phase
     * @return The filter result
     */
    FilterResult execute(FilterConfig config, FilterContext context, FilterPhase phase);

    /**
     * Check if this executor supports the given filter configuration.
     */
    boolean supports(FilterConfig config);
}
