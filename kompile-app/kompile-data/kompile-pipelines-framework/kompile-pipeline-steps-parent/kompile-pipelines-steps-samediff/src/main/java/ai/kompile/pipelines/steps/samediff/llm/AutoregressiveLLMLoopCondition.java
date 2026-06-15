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

package ai.kompile.pipelines.steps.samediff.llm;

import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.loop.LoopCondition;

/**
 * Loop condition for autoregressive LLM token generation.
 *
 * <p>Terminates when an EOS token is generated or max iterations reached.
 * Checks the "is_eos" boolean key in the loop state Data.</p>
 *
 * <p>The maximum iterations can be configured via:</p>
 * <ul>
 *   <li>Constructor parameter</li>
 *   <li>System property "kompile.llm.maxNewTokens" (default: 2048)</li>
 * </ul>
 *
 * <p>Analogous to {@code ai.kompile.pipelines.steps.vlm.AutoregressiveLoopCondition}.</p>
 */
public class AutoregressiveLLMLoopCondition implements LoopCondition {

    private final int maxNewTokens;

    public AutoregressiveLLMLoopCondition() {
        String maxTokensStr = System.getProperty("kompile.llm.maxNewTokens", "2048");
        int parsed;
        try {
            parsed = Integer.parseInt(maxTokensStr);
        } catch (NumberFormatException e) {
            parsed = 2048;
        }
        this.maxNewTokens = parsed;
    }

    public AutoregressiveLLMLoopCondition(int maxNewTokens) {
        this.maxNewTokens = maxNewTokens;
    }

    @Override
    public boolean shouldContinue(Data loopState, int iteration) {
        if (iteration >= maxNewTokens) {
            return false;
        }

        // Check if EOS was generated
        Boolean isEos = loopState.getBoolean(SameDiffLLMConstants.KEY_IS_EOS, false);
        return !isEos;
    }

    @Override
    public int maxIterations() {
        return maxNewTokens;
    }
}
