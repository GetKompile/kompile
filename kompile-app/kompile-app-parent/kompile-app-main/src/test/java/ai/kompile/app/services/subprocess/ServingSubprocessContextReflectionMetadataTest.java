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

import ai.kompile.app.llm.pipeline.LlmGenerateController;
import ai.kompile.app.llm.pipeline.LlmModelController;
import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import ai.kompile.app.subprocess.SubprocessServingConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServingSubprocessContextReflectionMetadataTest {

    @Test
    void matchesTheFocusedPlainContextAgentTrace() throws Exception {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put(LlmGenerateController.class.getName(), Set.of(
                "<init>(ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl)"));
        expected.put(LlmModelController.class.getName(), Set.of(
                "<init>(ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl,"
                        + "org.springframework.boot.web.client.RestTemplateBuilder,"
                        + "com.fasterxml.jackson.databind.ObjectMapper,"
                        + "org.springframework.beans.factory.ObjectProvider)"));
        expected.put(SameDiffLanguageModelImpl.class.getName(), Set.of(
                "<init>(java.util.Optional,java.util.Optional)",
                "call(org.springframework.ai.chat.prompt.Prompt)",
                "call(org.springframework.ai.model.ModelRequest)",
                "generateResponse(java.lang.String,java.util.List)",
                "generateResponseWithPotentialToolCalls(java.lang.String,java.util.List)",
                "stream(org.springframework.ai.chat.prompt.Prompt)",
                "stream(org.springframework.ai.model.ModelRequest)"));
        expected.put(SubprocessServingConfiguration.class.getName(), Set.of(
                "<init>()",
                "metrics()",
                "objectMapper()",
                "profiler()",
                "restTemplateBuilder()"));

        Map<String, JsonNode> traced = new LinkedHashMap<>();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/native-image/serving/reflect-config.json")) {
            assertNotNull(input, "serving reflection metadata must be on the test classpath");
            for (JsonNode entry : new ObjectMapper().readTree(input)) {
                String name = entry.path("name").asText();
                if (expected.containsKey(name)) {
                    assertNull(traced.put(name, entry),
                            () -> "duplicate serving context registration: " + name);
                }
            }
        }

        assertEquals(expected.keySet(), traced.keySet());
        for (Map.Entry<String, Set<String>> expectation : expected.entrySet()) {
            Set<String> actualMethods = new TreeSet<>();
            for (JsonNode method : traced.get(expectation.getKey()).path("methods")) {
                actualMethods.add(signature(method));
            }
            assertEquals(new TreeSet<>(expectation.getValue()), actualMethods,
                    () -> expectation.getKey() + " must match the focused plain-context trace");
        }
    }

    private static String signature(JsonNode method) {
        String parameters = String.join(",", StreamSupport.stream(
                        method.path("parameterTypes").spliterator(), false)
                .map(JsonNode::asText)
                .toList());
        return method.path("name").asText() + "(" + parameters + ")";
    }
}
