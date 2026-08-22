/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.embedding.anserini.subprocess;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmbeddingSubprocessLauncherLocalModelTest {
    @Test
    void localModelRestrictionRemovesInheritedRemoteSourceOptions() {
        Map<String, String> environment = new HashMap<>();
        environment.put("JAVA_TOOL_OPTIONS", "-Dkompile.staging.url=https://remote.example");
        environment.put("_JAVA_OPTIONS", "-Dkompile.models.archivePath=/global/models.zip");
        environment.put("JDK_JAVA_OPTIONS", "-Dkompile.staging.apiKey=secret");
        environment.put("KOMPILE_STAGING_URL", "https://remote.example");
        environment.put("KOMPILE_MODEL_CACHE_DIR", "/project/data/models");

        EmbeddingSubprocessLauncher.restrictToLocalModelSources(environment);

        assertFalse(environment.containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(environment.containsKey("_JAVA_OPTIONS"));
        assertFalse(environment.containsKey("JDK_JAVA_OPTIONS"));
        assertFalse(environment.containsKey("KOMPILE_STAGING_URL"));
        assertEquals("/project/data/models", environment.get("KOMPILE_MODEL_CACHE_DIR"));
    }
}
