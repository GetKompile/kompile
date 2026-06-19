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
package ai.kompile.app.ontology;

/**
 * Progress sink for a derivation run. The synchronous path uses {@link #NOOP}; the async job path
 * supplies an implementation that streams live logs and persists the generation transcript, so the
 * UI can watch the agent generate in real time.
 */
public interface DerivationProgress {

    /** Emit a human-readable progress line. */
    void log(String message);

    /**
     * Record the full generation transcript (the exact prompt sent and the raw model response).
     *
     * @param provider the effective provider id used (e.g. {@code "claude-cli"}, {@code "default"})
     * @param model    the effective model id used
     * @param prompt   the full prompt text sent to the model
     * @param response the raw response text returned by the model
     */
    void transcript(String provider, String model, String prompt, String response);

    /** No-op sink for synchronous derivation (no streaming/transcript capture). */
    DerivationProgress NOOP = new DerivationProgress() {
        @Override
        public void log(String message) {
            // no-op
        }

        @Override
        public void transcript(String provider, String model, String prompt, String response) {
            // no-op
        }
    };
}
