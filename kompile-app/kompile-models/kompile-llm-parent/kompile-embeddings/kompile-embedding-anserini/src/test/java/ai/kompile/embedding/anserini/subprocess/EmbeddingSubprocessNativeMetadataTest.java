/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.embedding.anserini.subprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EmbeddingSubprocessNativeMetadataTest {
    @Test
    void moduleOwnsReflectionMetadataForEveryProtocolType() throws Exception {
        Set<String> expected = new TreeSet<>();
        expected.add(EmbeddingSubprocessMessage.class.getName());
        Arrays.stream(EmbeddingSubprocessMessage.class.getPermittedSubclasses())
                .map(Class::getName).forEach(expected::add);
        expected.add(EmbeddingSubprocessMessage.BatchMetrics.class.getName());
        expected.add(EmbeddingSubprocessMessage.OpTimingStat.class.getName());
        expected.add(EmbeddingSubprocessMessage.ProgressStats.class.getName());
        expected.add(EmbeddingSubprocessMessage.RuntimeInfo.class.getName());

        Set<String> registered = new TreeSet<>();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/native-image/ai.kompile/kompile-embedding-anserini/reflect-config.json")) {
            assertNotNull(input, "embedding protocol metadata must ship with its owning module");
            for (JsonNode entry : new ObjectMapper().readTree(input)) {
                registered.add(entry.path("name").asText());
            }
        }
        assertEquals(expected, registered);
    }
}
