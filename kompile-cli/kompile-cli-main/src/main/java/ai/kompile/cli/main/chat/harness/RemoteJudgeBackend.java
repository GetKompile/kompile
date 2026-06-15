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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.config.DirectLlmClient;

/**
 * Judge backend that calls a remote LLM provider via HTTP.
 * <p>
 * Wraps {@link DirectLlmClient#streamOneShot(String, String, String)} for
 * providers like Anthropic, OpenAI, Gemini, ollama, OpenRouter, etc.
 */
public class RemoteJudgeBackend implements JudgeBackend {

    private final DirectLlmClient client;
    private final String modelOverride;
    private final String providerName;

    /**
     * @param client        the HTTP LLM client (dedicated judge client or main chat fallback)
     * @param modelOverride model to use if overriding the client's default (null = use client default)
     * @param providerName  human-readable provider name for diagnostics
     */
    public RemoteJudgeBackend(DirectLlmClient client, String modelOverride, String providerName) {
        this.client = client;
        this.modelOverride = modelOverride;
        this.providerName = providerName != null ? providerName : "remote";
    }

    @Override
    public String generate(String userPrompt, String systemPrompt) throws Exception {
        DirectLlmClient.StreamResult result = client.streamOneShot(
                userPrompt, systemPrompt, modelOverride);
        return result.text;
    }

    @Override
    public boolean isAvailable() {
        return client != null;
    }

    @Override
    public String describe() {
        return "remote(" + providerName + (modelOverride != null ? "/" + modelOverride : "") + ")";
    }
}
