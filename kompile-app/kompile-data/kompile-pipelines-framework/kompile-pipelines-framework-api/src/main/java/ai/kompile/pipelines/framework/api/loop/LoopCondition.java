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

package ai.kompile.pipelines.framework.api.loop;

import ai.kompile.pipelines.framework.api.data.Data;

/**
 * Defines the termination condition for a loop node within a graph pipeline.
 * Used by autoregressive decoding loops to determine when to stop generating tokens.
 *
 * Implementations should check loop state (e.g., EOS token generated, max iterations reached)
 * and return whether the loop should continue.
 */
public interface LoopCondition {

    /**
     * Evaluates whether the loop should continue for another iteration.
     *
     * @param loopState The current state of the loop, containing outputs from the most recent iteration.
     * @param iteration The current iteration number (0-based).
     * @return true if the loop should continue, false if it should terminate.
     */
    boolean shouldContinue(Data loopState, int iteration);

    /**
     * Returns the maximum number of iterations allowed (safety bound).
     * The loop will terminate after this many iterations regardless of the condition.
     *
     * @return The maximum number of iterations.
     */
    int maxIterations();
}
