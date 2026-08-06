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

import ai.kompile.app.MainApplication;
import ai.kompile.app.learning.subprocess.LearningSubprocessArgs;
import ai.kompile.app.learning.subprocess.ReasoningLearningSubprocessArgs;
import ai.kompile.app.runtime.SubprocessDispatcher;
import ai.kompile.app.subprocess.ServingSubprocessArgs;
import ai.kompile.app.subprocess.SubprocessArgs;
import ai.kompile.app.subprocess.VectorPopulationSubprocessArgs;
import ai.kompile.app.subprocess.VlmTestSubprocessArgs;
import ai.kompile.app.subprocess.model.ModelInitSubprocessArgs;
import ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessArgs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Closed-set guard for the unified native executable's subprocess contract. */
class NativeSubprocessDispatchInventoryTest {

    private static final List<String> EXPECTED_REGISTRY_ORDER = List.of(
            "ingest",
            "vector-population",
            "embedding",
            "model-init",
            "vlm-test",
            "graph-matrix",
            "serving",
            "learning",
            "pipeline-serving",
            "training");

    private static final Map<String, List<Class<?>>> FILE_ARGS_BY_MODE = fileArgsByMode();
    private static final Set<String> NON_FILE_NATIVE_MODES = Set.of("embedding", "graph-matrix");
    private static final Set<String> JVM_ONLY_MODES = Set.of("training");
    private static final Set<Class<?>> EXPECTED_FILE_ARGS_TYPES = Set.of(
            SubprocessArgs.class,
            VectorPopulationSubprocessArgs.class,
            ModelInitSubprocessArgs.class,
            VlmTestSubprocessArgs.class,
            ServingSubprocessArgs.class,
            LearningSubprocessArgs.class,
            ReasoningLearningSubprocessArgs.class,
            PipelineServingSubprocessArgs.class);
    private static final String BASELINE_CONFIG = "META-INF/native-image/main/reflect-config.json";
    private static final String SHARED_ARGS_CONFIG =
            "META-INF/native-image/subprocess-args-shared/reflect-config.json";
    private static final String MAIN_ARGS_CONFIG =
            "META-INF/native-image/subprocess-args-main/reflect-config.json";
    private static final List<String> REFLECTION_CONFIGS = List.of(
            BASELINE_CONFIG,
            SHARED_ARGS_CONFIG,
            MAIN_ARGS_CONFIG);

    @Test
    void everyRegisteredModeHasAnExplicitNativeLaunchClassification() throws Exception {
        Class.forName(MainApplication.class.getName(), true, MainApplication.class.getClassLoader());

        Set<String> registered = SubprocessDispatcher.registeredTypes();
        assertEquals(new LinkedHashSet<>(EXPECTED_REGISTRY_ORDER), registered,
                "update the native dispatch inventory when a subprocess mode is added or removed");

        Set<String> classified = new LinkedHashSet<>(FILE_ARGS_BY_MODE.keySet());
        classified.addAll(NON_FILE_NATIVE_MODES);
        classified.addAll(JVM_ONLY_MODES);
        assertEquals(registered, classified,
                "every registered subprocess must be classified as file args, non-file native, or JVM-only");
        assertEquals(Set.of("training"), JVM_ONLY_MODES,
                "training remains intentionally reflective and JVM-classpath-only");
    }

    @Test
    void everyNativeFileArgsRecordHasOneInvokableCanonicalConstructor() throws Exception {
        Map<String, List<JsonNode>> registrations = reflectionRegistrations();
        Set<Class<?>> argsTypes = new LinkedHashSet<>();
        FILE_ARGS_BY_MODE.values().forEach(argsTypes::addAll);
        assertEquals(EXPECTED_FILE_ARGS_TYPES, argsTypes,
                "update the focused agent trace and this guard when the file-args inventory changes");

        for (Class<?> argsType : argsTypes) {
            assertTrue(argsType.isRecord(), () -> argsType.getName() + " must remain a record");
            List<JsonNode> entries = registrations.getOrDefault(argsType.getName(), List.of());
            assertEquals(1, entries.size(),
                    () -> argsType.getName() + " must have exactly one focused agent registration");

            JsonNode methods = entries.get(0).path("methods");
            assertTrue(methods.isArray(),
                    () -> argsType.getName() + " needs invoked methods, not query-only constructor metadata");

            List<String> expectedConstructor = Arrays.stream(argsType.getRecordComponents())
                    .map(RecordComponent::getType)
                    .map(Class::getName)
                    .toList();
            int canonicalConstructorCount = 0;
            Set<String> registeredAccessors = new TreeSet<>();
            for (JsonNode method : methods) {
                String name = method.path("name").asText();
                List<String> parameterTypes = StreamSupport.stream(
                                method.path("parameterTypes").spliterator(), false)
                        .map(JsonNode::asText)
                        .toList();
                if ("<init>".equals(name) && expectedConstructor.equals(parameterTypes)) {
                    canonicalConstructorCount++;
                } else if (parameterTypes.isEmpty()) {
                    registeredAccessors.add(name);
                }
            }
            assertEquals(1, canonicalConstructorCount,
                    () -> argsType.getName() + " needs exactly one invokable canonical constructor");

            Set<String> expectedAccessors = new TreeSet<>();
            Arrays.stream(argsType.getRecordComponents())
                    .map(RecordComponent::getName)
                    .forEach(expectedAccessors::add);
            assertTrue(registeredAccessors.containsAll(expectedAccessors),
                    () -> argsType.getName() + " is missing traced record accessors: "
                            + difference(expectedAccessors, registeredAccessors));
        }
    }

    @Test
    void focusedMetadataPartitionsMatchNativePersonaReachability() throws Exception {
        Set<String> expectedShared = Set.of(
                SubprocessArgs.class.getName(),
                VectorPopulationSubprocessArgs.class.getName(),
                ModelInitSubprocessArgs.class.getName(),
                VlmTestSubprocessArgs.class.getName());
        Set<String> expectedMain = Set.of(
                ServingSubprocessArgs.class.getName(),
                LearningSubprocessArgs.class.getName(),
                ReasoningLearningSubprocessArgs.class.getName(),
                PipelineServingSubprocessArgs.class.getName());

        assertEquals(expectedShared, registrationNames(SHARED_ARGS_CONFIG),
                "shared persona images must receive only their reachable file-args records");
        assertEquals(expectedMain, registrationNames(MAIN_ARGS_CONFIG),
                "app-main-only file-args records must stay out of chat/crawl images");

        Set<String> staleBaselineArgs = registrationNames(BASELINE_CONFIG);
        staleBaselineArgs.retainAll(StreamSupport.stream(
                        EXPECTED_FILE_ARGS_TYPES.spliterator(), false)
                .map(Class::getName)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(staleBaselineArgs.isEmpty(),
                () -> "file-args records must have focused ownership, not baseline duplicates: "
                        + staleBaselineArgs);
    }

    private static Map<String, List<Class<?>>> fileArgsByMode() {
        Map<String, List<Class<?>>> modes = new LinkedHashMap<>();
        modes.put("ingest", List.of(SubprocessArgs.class));
        modes.put("vector-population", List.of(VectorPopulationSubprocessArgs.class));
        modes.put("model-init", List.of(ModelInitSubprocessArgs.class));
        modes.put("vlm-test", List.of(VlmTestSubprocessArgs.class));
        modes.put("serving", List.of(ServingSubprocessArgs.class));
        modes.put("learning", List.of(LearningSubprocessArgs.class, ReasoningLearningSubprocessArgs.class));
        modes.put("pipeline-serving", List.of(PipelineServingSubprocessArgs.class));
        return modes;
    }

    private Map<String, List<JsonNode>> reflectionRegistrations() throws Exception {
        Map<String, List<JsonNode>> registrations = new LinkedHashMap<>();
        for (String resource : REFLECTION_CONFIGS) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, () -> resource + " must be on the app-main test classpath");
                for (JsonNode entry : new ObjectMapper().readTree(input)) {
                    JsonNode name = entry.get("name");
                    if (name != null && name.isTextual()) {
                        registrations.computeIfAbsent(name.asText(), ignored -> new ArrayList<>()).add(entry);
                    }
                }
            }
        }
        return registrations;
    }

    private Set<String> registrationNames(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, () -> resource + " must be on the app-main test classpath");
            Set<String> names = new TreeSet<>();
            for (JsonNode entry : new ObjectMapper().readTree(input)) {
                JsonNode name = entry.get("name");
                if (name != null && name.isTextual()) {
                    names.add(name.asText());
                }
            }
            return names;
        }
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }
}
