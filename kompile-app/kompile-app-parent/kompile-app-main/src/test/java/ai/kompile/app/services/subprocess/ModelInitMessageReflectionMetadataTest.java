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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInitMessageReflectionMetadataTest {

    @Test
    void exactlyMatchesFocusedProductionJsonRoundTripTrace() throws Exception {
        Class<?>[] protocolVariants = ModelInitMessage.class.getPermittedSubclasses();
        assertEquals(7, protocolVariants.length,
                "update the focused native-image trace when the sealed protocol changes");

        Set<Class<?>> recordTypes = new TreeSet<>((left, right) -> left.getName().compareTo(right.getName()));
        recordTypes.addAll(Arrays.asList(protocolVariants));
        recordTypes.add(ModelInitMessage.ModelMetrics.class);
        recordTypes.add(ModelInitMessage.ProgressDetails.class);
        assertTrue(recordTypes.stream().allMatch(Class::isRecord));

        Set<String> expectedClasses = new TreeSet<>();
        expectedClasses.add(ModelInitMessage.class.getName());
        recordTypes.stream().map(Class::getName).forEach(expectedClasses::add);
        expectedClasses.add(ModelInitMessage.Phase.class.getName());

        String protocolPrefix = ModelInitMessage.class.getName();
        Map<String, JsonNode> registrations = new TreeMap<>();
        Set<String> duplicates = new TreeSet<>();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/native-image/model-init/reflect-config.json")) {
            assertNotNull(input, "model-init reflection metadata must be on the test classpath");
            for (JsonNode entry : new ObjectMapper().readTree(input)) {
                String name = entry.path("name").asText();
                if (name.startsWith(protocolPrefix) && registrations.putIfAbsent(name, entry) != null) {
                    duplicates.add(name);
                }
            }
        }

        assertTrue(duplicates.isEmpty(), () -> "Duplicate model-init protocol metadata: " + duplicates);
        assertEquals(expectedClasses, registrations.keySet(),
                "model-init protocol metadata must exactly match the focused agent trace");

        JsonNode interfaceRegistration = registrations.get(ModelInitMessage.class.getName());
        assertEquals(Set.of("name", "allPermittedSubclasses", "queryAllDeclaredMethods"),
                fieldNames(interfaceRegistration));
        assertTrue(interfaceRegistration.path("allPermittedSubclasses").asBoolean());
        assertTrue(interfaceRegistration.path("queryAllDeclaredMethods").asBoolean());

        JsonNode phaseRegistration = registrations.get(ModelInitMessage.Phase.class.getName());
        assertEquals(Set.of("name", "allDeclaredFields", "queryAllDeclaredMethods"),
                fieldNames(phaseRegistration));
        assertTrue(phaseRegistration.path("allDeclaredFields").asBoolean());
        assertTrue(phaseRegistration.path("queryAllDeclaredMethods").asBoolean());

        for (Class<?> recordType : recordTypes) {
            assertRecordRegistration(recordType, registrations.get(recordType.getName()));
        }
    }

    private static void assertRecordRegistration(Class<?> recordType, JsonNode registration) {
        assertNotNull(registration, () -> "Missing registration for " + recordType.getName());
        assertEquals(Set.of(
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
        Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .forEach(expectedAccessors::add);
        List<String> expectedConstructorParameters = Arrays.stream(recordType.getRecordComponents())
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
