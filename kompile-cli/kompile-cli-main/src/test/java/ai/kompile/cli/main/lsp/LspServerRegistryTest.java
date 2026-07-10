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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspServerRegistryTest {

    @Test
    void resolvesBuiltinsByExtension(@TempDir Path tmp) {
        LspServerRegistry registry = new LspServerRegistry(tmp.resolve("none.json"));
        assertEquals("java", registry.resolveForFile(Path.of("Foo.java")).orElseThrow().language());
        assertEquals("rust", registry.resolveForFile(Path.of("lib.rs")).orElseThrow().language());
        assertEquals("go", registry.resolveForFile(Path.of("main.go")).orElseThrow().language());
        assertEquals("typescript", registry.resolveForFile(Path.of("app.tsx")).orElseThrow().language());
        assertTrue(registry.resolveForFile(Path.of("notes.unknownext")).isEmpty());
    }

    @Test
    void cudaExtensionMapsToCudaCppLanguageId(@TempDir Path tmp) {
        LspServerRegistry registry = new LspServerRegistry(tmp.resolve("none.json"));
        LspServerConfig cpp = registry.resolveForFile(Path.of("kernel.cu")).orElseThrow();
        assertEquals("cpp", cpp.language());
        assertEquals("cuda-cpp", cpp.languageIdFor(".cu"));
        assertEquals("c", cpp.languageIdFor(".c"));
        assertEquals("cpp", cpp.languageIdFor(".hpp"));
    }

    @Test
    void overlayReplacesPresentKeysOnly(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("lsp-servers.json");
        Files.writeString(config, """
                {"servers": {"java": {"command": ["my-jdtls"], "startupTimeoutMs": 12345}}}""");
        LspServerRegistry registry = new LspServerRegistry(config);
        LspServerConfig java = registry.forLanguage("java");
        assertEquals(1, java.command().size());
        assertEquals("my-jdtls", java.command().get(0));
        assertEquals(12345L, java.startupTimeoutMs());
        // untouched keys keep their built-in values
        assertTrue(java.extensions().contains(".java"));
        assertTrue(java.rootMarkers().contains("pom.xml"));
    }

    @Test
    void overlayCanDisableAndAddLanguages(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("lsp-servers.json");
        Files.writeString(config, """
                {"servers": {
                  "go": {"enabled": false},
                  "zig": {"command": ["zls"], "extensions": [".zig"]}
                }}""");
        LspServerRegistry registry = new LspServerRegistry(config);

        assertFalse(registry.forLanguage("go").enabled());
        assertTrue(registry.resolveForFile(Path.of("main.go")).isEmpty()); // disabled → not resolved

        Optional<LspServerConfig> zig = registry.resolveForFile(Path.of("main.zig"));
        assertTrue(zig.isPresent());
        assertEquals("zig", zig.get().language());
    }

    @Test
    void resolveRootWalksToNearestMarker(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        Path deep = root.resolve("src/main/java");
        Files.createDirectories(deep);
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path file = deep.resolve("Main.java");
        Files.writeString(file, "class Main {}");

        LspServerRegistry registry = new LspServerRegistry(tmp.resolve("none.json"));
        LspServerConfig java = registry.forLanguage("java");
        assertEquals(root.toAbsolutePath().normalize(), registry.resolveRoot(file, java, tmp));
    }

    @Test
    void resolveRootFallsBackWhenNoMarker(@TempDir Path tmp) throws Exception {
        Path deep = tmp.resolve("no/markers/here");
        Files.createDirectories(deep);
        Path file = deep.resolve("Main.java");
        Files.writeString(file, "class Main {}");
        Path fallback = tmp.resolve("fallback");

        LspServerRegistry registry = new LspServerRegistry(tmp.resolve("none.json"));
        LspServerConfig java = registry.forLanguage("java");
        assertEquals(fallback, registry.resolveRoot(file, java, fallback));
    }

    @Test
    void availabilityProbesAgainstPath(@TempDir Path tmp) throws Exception {
        Path binDir = tmp.resolve("bin");
        Files.createDirectories(binDir);
        Path exe = binDir.resolve("fakelsp");
        Files.writeString(exe, "#!/bin/sh\n");
        exe.toFile().setExecutable(true);

        LspServerRegistry registry = new LspServerRegistry(tmp.resolve("none.json"));
        LspServerConfig cfg = LspServerConfig.builder("fake").command(java.util.List.of("fakelsp")).build();

        assertTrue(registry.isAvailable(cfg, binDir.toString()));
        assertFalse(registry.isAvailable(cfg, tmp.resolve("empty").toString()));
        assertFalse(registry.isAvailable(cfg, ""));

        // absolute path form
        LspServerConfig abs = LspServerConfig.builder("fake").command(java.util.List.of(exe.toString())).build();
        assertTrue(registry.isAvailable(abs, ""));
    }
}
