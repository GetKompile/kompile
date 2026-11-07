/*
 * Copyright 2026 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the guard plugin's {@code experimental.session.compacting} hook the way OpenCode
 * calls it ({@code (input, output)}, pushing onto {@code output.context}), in node with
 * TypeScript type stripping.
 */
class OpenCodeGuardPluginCompactionTest {
    private static final String DRIVER = """
            const { server } = await import(process.argv[2])
            const hooks = await server({ $: null })
            const output = { context: [] }
            await hooks["experimental.session.compacting"]({ sessionID: "s" }, output)
            process.stdout.write(JSON.stringify(output.context))
            """;
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path dir;

    private JsonNode runHook(Map<String, String> env) throws Exception {
        Path plugin = Files.writeString(dir.resolve("kompile-hooks.mts"), PlatformHooksInstaller.openCodePluginText());
        Path driver = Files.writeString(dir.resolve("driver.mjs"), DRIVER);
        ProcessBuilder builder = new ProcessBuilder(List.of("node", "--experimental-strip-types",
                driver.toString(), plugin.toUri().toString())).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().remove("KOMPILE_COMPACTION_RELOAD_FILE");
        builder.environment().putAll(env);
        Process process;
        try {
            process = builder.start();
        } catch (IOException noNode) {
            assumeTrue(false, "node is not installed: " + noNode.getMessage());
            return null;
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), out);
        return mapper.readTree(out);
    }

    @Test
    void theCompactionHookHandsTheSummarizerKompilesReload() throws Exception {
        Path reload = dir.resolve("reload.json");
        Files.writeString(reload, mapper.writeValueAsString(Map.of("context",
                "<kompile_compaction_reload>\n<skill name=\"fix\">\nFix it\n</skill>\n</kompile_compaction_reload>")));
        JsonNode context = runHook(Map.of("KOMPILE_COMPACTION_RELOAD_FILE", reload.toString()));
        assertEquals(1, context.size(), context.toString());
        String pushed = context.get(0).asText();
        assertTrue(pushed.contains("verbatim"), pushed);
        assertTrue(pushed.endsWith("<kompile_compaction_reload>\n<skill name=\"fix\">\nFix it\n</skill>\n"
                + "</kompile_compaction_reload>"), pushed);
    }

    @Test
    void aSessionOutsideAKompileLaunchOrWithoutAReloadAddsNothing() throws Exception {
        assertEquals(0, runHook(Map.of()).size());
        assertEquals(0, runHook(Map.of("KOMPILE_COMPACTION_RELOAD_FILE", dir.resolve("missing.json").toString())).size());
    }
}
