/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SemanticMemoryEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void indexesActiveProjectAndKeepsTfidfVocabularyStable() throws Exception {
        Path project = tempDir.resolve("project");
        Path memoryDir = project.resolve(".kompile").resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryFile = memoryDir.resolve("project-sentinel.md");
        Files.writeString(memoryFile, """
                ---
                name: Project semantic sentinel
                description: Scope-specific retrieval marker
                type: project
                ---
                stable-vector-sentinel is only stored in this active project.
                """);

        SemanticMemoryEngine engine = new SemanticMemoryEngine(project);
        try {
            engine.initialize();

            List<SemanticMemoryEngine.RetrievedMemory> initial =
                    engine.query("stable vector sentinel", 5, 0.01);
            assertFalse(initial.isEmpty());
            assertEquals(memoryFile.toAbsolutePath().normalize(),
                    initial.get(0).entry.sourcePath.toAbsolutePath().normalize());

            engine.indexTurn("stable-test", 1, "user",
                    "unrelated vocabulary should not invalidate the project sentinel");

            List<SemanticMemoryEngine.RetrievedMemory> afterTurn =
                    engine.query("stable vector sentinel", 5, 0.01);
            assertFalse(afterTurn.isEmpty());
            assertEquals(memoryFile.toAbsolutePath().normalize(),
                    afterTurn.get(0).entry.sourcePath.toAbsolutePath().normalize());
        } finally {
            engine.shutdown();
        }
    }
}
