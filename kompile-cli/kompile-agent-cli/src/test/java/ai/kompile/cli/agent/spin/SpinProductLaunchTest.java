/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.agent.spin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/** Packaging/launch ABI tests, not real distribution startup or model-inference qualification. */
class SpinProductLaunchTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENTRYPOINT = "components/product/bin/product";
    @TempDir Path temp;

    @Test void jarAndMixedWebUseEmbeddedJvmConfigurationAndOrdinaryRole() throws Exception {
        for (String delivery : List.of("jar", "mixed")) {
            Path source = source(delivery, false);
            SpinDefinition definition = SpinDefinition.load(source);
            var prepared = SpinWorkspace.prepareForInstall(definition, temp.resolve(delivery));
            var current = current(definition, prepared);
            Map<String, String> inherited = Map.of("KOMPILE_CLI", "/outside/kompile",
                    "KOMPILE_AGENT", "/outside/agent", "KOMPILE_INSTALL_DIR", "/outside",
                    "KOMPILE_MODEL_SERVING_JAR", "/outside/serving.jar",
                    "KOMPILE_CHAT_MODEL_PATH", "/outside/model", "KOMPILE_CHAT_TOKENIZER_PATH", "/outside/tokenizer");
            var resolution = SpinLauncher.resolveKompile(current, inherited);
            Path runtime = source.resolve("runtime");
            Path java = runtime.resolve("runtime/bin/java" + (isWindows() ? ".exe" : ""));
            assertEquals(List.of(java.toString(), "-Dkompile.dist.home=" + runtime,
                    "-Djava.library.path=" + runtime.resolve("bin") + File.pathSeparator + runtime.resolve("lib"),
                    "-Dspring.config.additional-location=optional:file:" + runtime.resolve("conf") + "/",
                    "-jar", runtime.resolve("lib/kompile-cli.jar").toString()), resolution.commandPrefix());
            List<String> expected = new ArrayList<>(resolution.commandPrefix());
            expected.addAll(List.of("chat", "--working-dir", prepared.workspace().toString(),
                    "--role", "spin-helper", "--memory=false", "--web", "--open-browser"));
            assertEquals(expected, SpinLauncher.commandFor(definition, prepared, resolution.commandPrefix(), List.of()));
            var environment = SpinLauncher.runtimeEnvironment(definition, prepared, resolution, inherited);
            assertEquals(source.toString(), environment.get("KOMPILE_SPIN_ROOT"));
            assertEquals(prepared.home().toString(), environment.get("KOMPILE_SPIN_HOME"));
            assertEquals(prepared.workspace().toString(), environment.get("KOMPILE_SPIN_WORKSPACE"));
            assertEquals(prepared.workspace().toString(), environment.get("KOMPILE_MCP_TRUSTED_WORKSPACE"));
            assertEquals(runtime.toString(), environment.get("KOMPILE_INSTALL_DIR"));
            assertEquals(runtime.toString(), environment.get("KOMPILE_DIST_HOME"));
            assertEquals(java.toString(), environment.get("KOMPILE_JAVA"));
            assertEquals("127.0.0.1", environment.get("KOMPILE_CHAT_ADDRESS"));
            for (String name : List.of("KOMPILE_CLI", "KOMPILE_AGENT", "KOMPILE_MODEL_SERVING_JAR",
                    "KOMPILE_CHAT_MODEL_PATH", "KOMPILE_CHAT_TOKENIZER_PATH")) {
                assertFalse(environment.containsKey(name), name);
            }
        }
    }

    @Test void productAdapterReceivesTerminalWebAndExecWithoutRoleOrProfileInjection() throws Exception {
        Path source = source("jar", true);
        SpinDefinition definition = SpinDefinition.load(source);
        var prepared = SpinWorkspace.prepareForInstall(definition, temp.resolve("adapter-home"));
        String adapter = source.resolve(ENTRYPOINT).toString();
        for (List<String> args : List.of(List.of("chat", "--offline"),
                List.of("web", "--web-port", "9081", "--open-browser", "--startup-timeout=30"),
                List.of("exec", "hello product"))) {
            List<String> expected = new ArrayList<>(List.of(adapter));
            expected.addAll(args);
            assertEquals(expected, SpinLauncher.commandFor(definition, prepared, List.of("ignored-kompile"), args));
        }
        assertEquals(List.of(adapter, "web"), SpinLauncher.commandFor(definition, prepared, List.of(), List.of()));
        assertEquals(List.of(adapter, "chat", "a prompt"),
                SpinLauncher.commandFor(definition, prepared, List.of(), List.of("a prompt")));
        assertThrows(IOException.class, () -> SpinLauncher.commandFor(definition, prepared, List.of(), List.of("exec")));
        assertThrows(IOException.class, () -> SpinLauncher.commandFor(definition, prepared, List.of(), List.of("spin")));
    }

    @Test void productIdentityWorkspaceAndPermissionsCannotBeOverridden() throws Exception {
        SpinDefinition definition = SpinDefinition.load(source("jar", true));
        var prepared = SpinWorkspace.prepareForInstall(definition, temp.resolve("guard-home"));
        for (String option : List.of("--product-profile", "--profile", "--working-dir", "--workspace", "--root",
                "--home", "--role", "--roles", "--agent", "--permissions", "--permission-mode",
                "--dangerously-skip-permissions", "--global-config", "--system-prompt", "--web-session-worker")) {
            assertThrows(IOException.class, () -> SpinLauncher.commandFor(definition, prepared, List.of(),
                    List.of("web", option, "override")), option);
            assertThrows(IOException.class, () -> SpinLauncher.commandFor(definition, prepared, List.of(),
                    List.of("web", option + "=override")), option);
        }
        assertThrows(IOException.class, () -> SpinLauncher.commandFor(definition, prepared, List.of(), List.of("chat", "-rmalicious")));
        assertDoesNotThrow(() -> SpinLauncher.commandFor(definition, prepared, List.of(),
                List.of("exec", "--", "--role is ordinary prompt text")));
    }

    @Test void entrypointMustBeContainedSymlinkFreePresentAndExecutable() throws Exception {
        Path source = source("jar", true);
        for (String path : List.of("../escape", "components/../escape", "/outside/product", "components/./product",
                "components/product/bin/missing", "components/product")) {
            entrypoint(source, path);
            assertThrows(IOException.class, () -> SpinDefinition.load(source), path);
        }
        entrypoint(source, ENTRYPOINT);
        Path script = source.resolve(ENTRYPOINT);
        if (!isWindows()) {
            assertTrue(script.toFile().setExecutable(false, false));
            assertThrows(IOException.class, () -> SpinDefinition.load(source));
            assertTrue(script.toFile().setExecutable(true, false));
        }
        Assumptions.assumeFalse(isWindows());
        Path outside = executable(temp.resolve("outside-product"), "#!/bin/sh\nexit 0\n");
        Files.delete(script);
        Files.createSymbolicLink(script, outside);
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
        Files.delete(script);
        Files.delete(script.getParent());
        Files.createDirectories(temp.resolve("outside-bin"));
        Files.createSymbolicLink(script.getParent(), temp.resolve("outside-bin"));
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
    }

    @Test void missingDeclaredSoftwareAndUnsupportedAcquisitionNeverBecomeThinSpins() throws Exception {
        Path source = source("jar", true);
        ObjectNode manifest = manifest(source);
        ObjectNode distribution = (ObjectNode) manifest.path("distribution");
        var inventory = distribution.withArray("software");
        for (int index = 0; index < 12; index++) {
            String path = "components/helpers/asset-" + index;
            Files.createDirectories(source.resolve(path).getParent());
            Files.writeString(source.resolve(path), "required");
            inventory.addObject().put("id", "helper-" + index).put("component", "helper")
                    .put("version", "1.0.0").put("path", path);
        }
        save(source, manifest);
        assertDoesNotThrow(() -> SpinDefinition.load(source));
        Files.delete(source.resolve("components/helpers/asset-11"));
        assertTrue(assertThrows(IOException.class, () -> SpinDefinition.load(source)).getMessage().contains("helper-11"));
        Files.writeString(source.resolve("components/helpers/asset-11"), "required");
        ((ObjectNode) inventory.get(0)).put("materialization", "external");
        save(source, manifest);
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
        ((ObjectNode) inventory.get(0)).remove("materialization");
        for (String acquisition : List.of("provision", "external")) {
            distribution.put("materialization", acquisition);
            save(source, manifest);
            assertThrows(IOException.class, () -> SpinDefinition.load(source));
        }
        distribution.put("materialization", "bundle");
        distribution.put("buildTier", "java");
        save(source, manifest);
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
        distribution.put("buildTier", "assemble");
        save(source, manifest);
        Files.writeString(source.resolve("spin.yaml"), Files.readString(source.resolve("spin.yaml"))
                + "runtime:\n  delivery: thin\n");
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
    }

    @Test void jarRuntimeNeedsRunnableJarsConfigurationAndServingForLocalModels() throws Exception {
        for (String file : List.of("lib/kompile-cli.jar", "lib/kompile-agent.jar", "lib/kompile-chat.jar",
                "lib/kompile-model-serving.jar", "conf/application.properties")) {
            Path source = source("jar", false);
            Files.delete(source.resolve("runtime").resolve(file));
            assertThrows(IOException.class, () -> SpinDefinition.load(source), file);
        }
        Path source = source("jar", false);
        Files.writeString(source.resolve("runtime/lib/kompile-chat.jar"), "not a runnable JAR");
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
        Path nativeSource = source("native", false);
        assertThrows(IOException.class, () -> SpinDefinition.load(nativeSource));
        Path mixed = source("mixed", false);
        Files.delete(mixed.resolve("runtime/lib/kompile-chat.jar"));
        executable(mixed.resolve("runtime/bin/kompile-chat"), "#!/bin/sh\nexit 0\n");
        assertThrows(IOException.class, () -> SpinDefinition.load(mixed));
    }

    @Test void jarWebRejectsLibraryJarsAndEmptyConfiguration() throws Exception {
        Path library = source("jar", false);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(library.resolve("runtime/lib/kompile-chat.jar")))) {
            jar.putNextEntry(new JarEntry("fixture/resource.txt"));
            jar.write(1);
            jar.closeEntry();
        }
        assertThrows(IOException.class, () -> SpinDefinition.load(library));
        Path emptyConfig = source("jar", false);
        Files.writeString(emptyConfig.resolve("runtime/conf/application.properties"), "");
        assertThrows(IOException.class, () -> SpinDefinition.load(emptyConfig));
    }

    @Test void mixedWebCanCombineNativeChatWithPackagedJvmCliAndAgent() throws Exception {
        Path source = source("mixed", false);
        Files.delete(source.resolve("runtime/lib/kompile-chat.jar"));
        Files.createDirectories(source.resolve("runtime/bin"));
        Files.write(source.resolve("runtime/bin/kompile-chat"), new byte[]{0x7f, 'E', 'L', 'F', 0});
        assertDoesNotThrow(() -> SpinDefinition.load(source));
    }

    @Test void jarConfigurationAndRuntimeSymlinksAreRejected() throws Exception {
        Assumptions.assumeFalse(isWindows());
        for (String file : List.of("lib/kompile-chat.jar", "conf/application.properties")) {
            Path source = source("jar", false);
            Path target = source.resolve("runtime").resolve(file);
            Path outside = temp.resolve("outside-" + target.getFileName());
            Files.move(target, outside);
            Files.createSymbolicLink(target, outside);
            assertThrows(IOException.class, () -> SpinDefinition.load(source));
        }
    }

    @Test void archiveRelocationPreservesAdapterHelpersAndEmbeddedJavaWithoutGlobalFallback() throws Exception {
        Assumptions.assumeFalse(isWindows());
        Path source = source("jar", true);
        Path capture = temp.resolve("adapter-args");
        Path environmentCapture = temp.resolve("adapter-environment");
        executable(source.resolve(ENTRYPOINT), """
                #!/bin/sh
                printf '%s\n' "$@" > "$SPIN_TEST_ARGS"
                printf '%s\n' "$KOMPILE_SPIN_ROOT" "$KOMPILE_SPIN_HOME" "$KOMPILE_SPIN_WORKSPACE" "$KOMPILE_INSTALL_DIR" "$KOMPILE_DIST_HOME" "$PWD" > "$SPIN_TEST_ENV"
                """);
        Path helper = executable(source.resolve("components/product/libexec/helper"), "#!/bin/sh\nexit 0\n");
        Path javaCapture = temp.resolve("java-args");
        executable(source.resolve("runtime/runtime/bin/java"), "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$SPIN_TEST_JAVA\"\n");
        Path archive = temp.resolve("product.kspin");
        SpinArchive.build(source, archive, null);
        Path initialHome = temp.resolve("initial-home");
        var installed = SpinInstallation.install(archive, initialHome);
        Path home = temp.resolve("relocated home");
        Files.move(initialHome, home);
        var current = SpinInstallation.current(home);
        Path release = current.release();
        assertTrue(Files.isExecutable(release.resolve(ENTRYPOINT)));
        assertTrue(Files.isExecutable(release.resolve(source.relativize(helper))));
        assertTrue(Files.isExecutable(release.resolve("runtime/runtime/bin/java")));
        assertFalse(current.definition().project().entrypoint().startsWith(installed.release()));
        Map<String, String> inherited = new HashMap<>(System.getenv());
        inherited.put("SPIN_TEST_ARGS", capture.toString());
        inherited.put("SPIN_TEST_ENV", environmentCapture.toString());
        inherited.put("SPIN_TEST_JAVA", javaCapture.toString());
        inherited.put("KOMPILE_CLI", "/outside/kompile");
        inherited.put("KOMPILE_INSTALL_DIR", "/outside/runtime");
        assertEquals(0, SpinLauncher.launch(home, List.of("web", "--web-port", "19081"), inherited));
        assertEquals(List.of("web", "--web-port", "19081"), Files.readAllLines(capture));
        assertEquals(List.of(release.toString(), home.toString(), home.resolve("workspace").toString(),
                release.resolve("runtime").toString(), release.resolve("runtime").toString(),
                home.resolve("workspace").toString()), Files.readAllLines(environmentCapture));
        assertFalse(Files.exists(javaCapture), "product routing must not recurse through spin or an engine command");

        Path externalMarker = temp.resolve("external-agent-was-used");
        Path external = executable(temp.resolve("external-agent"), "#!/bin/sh\ntouch '" + externalMarker + "'\n");
        ProcessBuilder wrapper = new ProcessBuilder(home.resolve("bin/test-product").toString(), "chat");
        wrapper.environment().putAll(inherited);
        wrapper.environment().put("KOMPILE_AGENT", external.toString());
        assertEquals(0, wrapper.start().waitFor());
        assertEquals(List.of("-Dkompile.dist.home=" + release.resolve("runtime"),
                "-Djava.library.path=" + release.resolve("runtime/bin") + File.pathSeparator + release.resolve("runtime/lib"),
                "-Dspring.config.additional-location=optional:file:" + release.resolve("runtime/conf") + "/", "-jar",
                release.resolve("runtime/lib/kompile-agent.jar").toString(), "spin", "launch", "--home",
                home.toString(), "chat"), Files.readAllLines(javaCapture));
        assertFalse(Files.exists(externalMarker));
        Files.delete(release.resolve("runtime/lib/kompile-agent.jar"));
        assertNotEquals(0, wrapper.start().waitFor());
        assertFalse(Files.exists(externalMarker), "an incomplete curated release must not use a global agent");
        String windows = Files.readString(home.resolve("bin/test-product.cmd"));
        assertTrue(windows.contains("if \"true\"==\"true\" goto curated"));
        assertTrue(windows.contains("global fallback is disabled"));
    }

    private Path source(String delivery, boolean product) throws Exception {
        Path source = Files.createTempDirectory(temp, "release-");
        Files.writeString(source.resolve("spin.yaml"), "schemaVersion: 2\nmetadata:\n  id: test-product\n  version: 1.0.0\nproject:\n  template: project\n");
        Files.writeString(source.resolve("agent.yaml"), "schemaVersion: '1'\nmetadata:\n  name: test-product\nengine: cli-loop\nsystemPrompt: Be helpful.\nrole:\n  name: spin-helper\n");
        Files.createDirectories(source.resolve("project/data/models"));
        Files.writeString(source.resolve("project/data/models/chat.sdz"), "packaging fixture only");
        Files.writeString(source.resolve("project/data/models/tokenizer.json"), "{}");
        Files.writeString(source.resolve("project/kompile.project.json"), """
                {"schemaVersion":1,"name":"Test product","metadata":{"spin.defaultModel":"chat"},
                 "distribution":{"materialization":"bundle"},
                 "models":[{"id":"chat","path":"data/models/chat.sdz","metadata":{"tokenizer.path":"data/models/tokenizer.json"}}]}
                """);
        ObjectNode manifest = manifest(source);
        ObjectNode distribution = (ObjectNode) manifest.path("distribution");
        distribution.put("delivery", delivery);
        distribution.putObject("web").put("enabled", true).put("bind", "127.0.0.1");
        if (product) distribution.put("entrypoint", ENTRYPOINT);
        save(source, manifest);
        for (String name : List.of("kompile-cli", "kompile-agent", "kompile-chat", "kompile-model-serving")) {
            jar(source.resolve("runtime/lib/" + name + ".jar"));
        }
        Files.createDirectories(source.resolve("runtime/conf"));
        Files.writeString(source.resolve("runtime/conf/application.properties"), "server.address=127.0.0.1\n");
        executable(source.resolve("runtime/runtime/bin/java" + (isWindows() ? ".exe" : "")), "#!/bin/sh\nexit 0\n");
        if (product) executable(source.resolve(ENTRYPOINT), "#!/bin/sh\nexit 0\n");
        return source;
    }

    private static void jar(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "fixture.Main");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            // Synthetic entry validates archive structure only; never used as a real runtime.
            jar.putNextEntry(new JarEntry("fixture/Main.class"));
            jar.write(new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe});
            jar.closeEntry();
        }
    }

    private static Path executable(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        if (!isWindows() && !file.toFile().setExecutable(true, false)) throw new IOException("chmod failed: " + file);
        return file;
    }

    private static ObjectNode manifest(Path source) throws IOException {
        return (ObjectNode) JSON.readTree(source.resolve("project/kompile.project.json").toFile());
    }

    private static void save(Path source, ObjectNode manifest) throws IOException {
        Files.writeString(source.resolve("project/kompile.project.json"), JSON.writeValueAsString(manifest));
    }

    private static void entrypoint(Path source, String path) throws IOException {
        ObjectNode manifest = manifest(source);
        ((ObjectNode) manifest.path("distribution")).put("entrypoint", path);
        save(source, manifest);
    }

    private static SpinInstallation.Current current(SpinDefinition definition, SpinWorkspace.Prepared prepared) {
        return new SpinInstallation.Current(definition, prepared.home(), prepared.home().resolve("components/test-product"),
                "fixture", definition.root(), prepared.workspace());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
