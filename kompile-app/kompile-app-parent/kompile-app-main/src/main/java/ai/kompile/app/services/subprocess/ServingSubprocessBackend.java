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

package ai.kompile.app.services.subprocess;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.crawl.graph.LocalServingBackend;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;

/**
 * App-main {@link LocalServingBackend} that delegates to the {@link ServingSubprocessLauncher}
 * (loopback {@code POST /api/llm/generate}) and unwraps the {@code {finishReason, generatedText}}
 * response contract into plain generated text.
 *
 * <p>Lets the crawl {@code CrawlLlmDispatcher} (in {@code kompile-crawl-graph}) route a
 * {@code LOCAL_MODEL} backend marked {@code agentName="serving"} to the serving subprocess as a
 * quota-free extraction lane, without depending on app-main. When the serving subprocess isn't
 * running (its idle default), {@link #isAvailable()} returns {@code false} so the dispatcher skips it
 * and falls back — the wiring is inert until an operator loads a model into the serving subprocess.</p>
 */
@Service
public class ServingSubprocessBackend implements LocalServingBackend {

    private static final Logger log = LoggerFactory.getLogger(ServingSubprocessBackend.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    /** Optional: absent in contexts where the serving launcher bean isn't present. */
    @Autowired(required = false)
    private ServingSubprocessLauncher launcher;

    /**
     * Returns {@code true} when the serving subprocess is running AND has a model
     * fully loaded (ready to serve generation requests).
     *
     * <p>The subprocess-running check is instant (in-memory flag). The model-loaded
     * check calls {@code GET /api/llm/status} with a 3-second TTL cache so frequent
     * dispatcher polls do not spam the subprocess over HTTP. Any HTTP error is treated
     * as "not available" so the dispatcher falls through to the next backend cleanly.</p>
     */
    @Override
    public boolean isAvailable() {
        return launcher != null && launcher.isRunning() && launcher.isModelLoaded();
    }

    @Override
    public String generate(String prompt) throws Exception {
        if (launcher == null) {
            throw new IllegalStateException("Serving subprocess launcher not available");
        }
        // Raw JSON body from POST /api/llm/generate — same contract LocalStagingLlmService parses.
        String raw = launcher.generate(prompt);
        JsonNode response = MAPPER.readTree(raw);
        String finishReason = response.path("finishReason").asText("");
        if (finishReason.toLowerCase(Locale.ROOT).startsWith("error")) {
            throw new IOException("Serving subprocess generation failed: " + finishReason);
        }
        String text = response.path("generatedText").asText("");
        log.debug("[serving-backend] generated {} chars (finishReason={})", text.length(), finishReason);
        return text;
    }
}
