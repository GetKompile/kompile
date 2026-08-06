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

import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingSubprocessMessageReflectionMetadataTest {

    @Test
    void coversExactlyEveryProtocolVariantAndNestedPayloadOnce() throws Exception {
        Class<?>[] protocolVariants = EmbeddingSubprocessMessage.class.getPermittedSubclasses();
        assertEquals(19, protocolVariants.length,
                "update the focused native-image trace when the sealed protocol changes");

        Set<String> expectedClasses = new TreeSet<>();
        expectedClasses.add(EmbeddingSubprocessMessage.class.getName());
        Arrays.stream(protocolVariants)
                .map(Class::getName)
                .forEach(expectedClasses::add);
        expectedClasses.add(EmbeddingSubprocessMessage.BatchMetrics.class.getName());
        expectedClasses.add(EmbeddingSubprocessMessage.OpTimingStat.class.getName());
        expectedClasses.add(EmbeddingSubprocessMessage.ProgressStats.class.getName());
        expectedClasses.add(EmbeddingSubprocessMessage.RuntimeInfo.class.getName());

        String protocolPrefix = EmbeddingSubprocessMessage.class.getName();
        Set<String> registeredClasses = new TreeSet<>();
        Set<String> duplicateClasses = new TreeSet<>();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/native-image/main/reflect-config.json")) {
            assertNotNull(input, "app-main reflection metadata must be on the test classpath");
            JsonNode entries = new ObjectMapper().readTree(input);
            for (JsonNode entry : entries) {
                JsonNode name = entry.get("name");
                if (name != null && name.isTextual()
                        && name.asText().startsWith(protocolPrefix)
                        && !registeredClasses.add(name.asText())) {
                    duplicateClasses.add(name.asText());
                }
            }
        }

        assertTrue(duplicateClasses.isEmpty(),
                () -> "Duplicate embedding protocol reflection metadata: " + duplicateClasses);
        assertEquals(expectedClasses, registeredClasses,
                "embedding protocol reflection metadata must exactly match the focused agent trace");
    }
}
