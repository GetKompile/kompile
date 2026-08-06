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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.subprocess;

import ai.kompile.app.subprocess.model.ModelInitMessage;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelInitMessageRoundTripTest {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Test
    void everyProductionProtocolVariantRoundTripsThroughTheSealedInterface() throws Exception {
        ModelInitMessage.ProgressDetails details = new ModelInitMessage.ProgressDetails(
                1L, 2L, 3.5, 4L, 5, 6, 7, 8L, 9L, 10, 11.5,
                true, 12L, 13L, 14.5);
        ModelInitMessage.ModelMetrics metrics = new ModelInitMessage.ModelMetrics(
                15L, 2, "/models/example", 16, 17, 18, "wordpiece",
                19L, 20.5, 21, 22);

        List<ModelInitMessage> messages = List.of(
                new ModelInitMessage.Progress(
                        "task", "model", ModelInitMessage.Phase.CREATING_ENCODER,
                        40, 60, "building graph", details),
                new ModelInitMessage.PhaseTransition(
                        "task", "model", ModelInitMessage.Phase.LOADING_MODEL,
                        ModelInitMessage.Phase.CREATING_ENCODER, 23L),
                new ModelInitMessage.Heartbeat(
                        "task", "model", 24L, 25.5, 26L, 27L,
                        ModelInitMessage.Phase.VALIDATING_MODEL),
                new ModelInitMessage.Completed(
                        "task", "model", "REGISTRY", "BGE", 768, 512, 28L,
                        Map.of(ModelInitMessage.Phase.LOADING_MODEL, 29L), metrics),
                new ModelInitMessage.Failed(
                        "task", "model", ModelInitMessage.Phase.CREATING_ENCODER,
                        "failed", "TokenizerException", "stack", false),
                new ModelInitMessage.Log(
                        "task", "model", "INFO", "encoder", "loaded", 30L),
                new ModelInitMessage.ModelInfo(
                        "task", "model", "dense_encoder", "BgeSameDiffEncoder",
                        768, 512, 30_522, "wordpiece", Map.of("source", "registry"))
        );

        assertEquals(ModelInitMessage.class.getPermittedSubclasses().length, messages.size(),
                "update this production round-trip trace whenever the sealed protocol changes");
        for (ModelInitMessage expected : messages) {
            String json = MAPPER.writeValueAsString(expected);
            ModelInitMessage actual = MAPPER.readValue(json, ModelInitMessage.class);
            assertEquals(expected, actual, expected.getClass().getName());
        }
    }
}
