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

import ai.kompile.app.subprocess.ServingSubprocessArgs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServingSubprocessArgsReflectionMetadataTest {

    @Test
    void exactlyMatchesFocusedProductionJsonRoundTripTrace() throws Exception {
        JsonNode registration = null;
        int registrationCount = 0;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/native-image/subprocess-args-main/reflect-config.json")) {
            assertNotNull(input, "focused app-main args metadata must be on the test classpath");
            for (JsonNode entry : new ObjectMapper().readTree(input)) {
                if (ServingSubprocessArgs.class.getName().equals(entry.path("name").asText())) {
                    registration = entry;
                    registrationCount++;
                }
            }
        }

        assertEquals(1, registrationCount,
                "ServingSubprocessArgs must have exactly one agent-generated registration");
        assertNotNull(registration);
        assertEquals(
                Set.of(
                        "name",
                        "allDeclaredFields",
                        "queryAllDeclaredMethods",
                        "queryAllDeclaredConstructors",
                        "methods"),
                fieldNames(registration),
                "agent registration must not contain hand-authored top-level metadata");
        assertTrue(registration.path("allDeclaredFields").asBoolean());
        assertTrue(registration.path("queryAllDeclaredMethods").asBoolean());
        assertTrue(registration.path("queryAllDeclaredConstructors").asBoolean());

        Set<String> expectedAccessors = new TreeSet<>();
        Arrays.stream(ServingSubprocessArgs.class.getRecordComponents())
                .map(component -> component.getName())
                .forEach(expectedAccessors::add);
        List<String> expectedConstructorParameters =
                Arrays.stream(ServingSubprocessArgs.class.getRecordComponents())
                        .map(component -> component.getType().getName())
                        .toList();

        JsonNode methods = registration.path("methods");
        Set<String> registeredAccessors = new TreeSet<>();
        JsonNode constructor = null;
        int constructorCount = 0;
        for (JsonNode method : methods) {
            assertEquals(Set.of("name", "parameterTypes"), fieldNames(method),
                    "agent method registrations must not contain hand-authored metadata");
            if ("<init>".equals(method.path("name").asText())) {
                constructor = method;
                constructorCount++;
            } else {
                assertEquals(0, method.path("parameterTypes").size(),
                        "focused trace should register only record accessors besides the constructor");
                registeredAccessors.add(method.path("name").asText());
            }
        }

        assertEquals(1, constructorCount,
                "Jackson needs the canonical record constructor registered for invocation");
        assertNotNull(constructor);
        List<String> registeredConstructorParameters = StreamSupport.stream(
                        constructor.path("parameterTypes").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertEquals(expectedConstructorParameters, registeredConstructorParameters);
        assertEquals(expectedAccessors, registeredAccessors);
        assertEquals(expectedAccessors.size() + 1, methods.size(),
                "metadata must remain limited to the exact focused agent trace");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
