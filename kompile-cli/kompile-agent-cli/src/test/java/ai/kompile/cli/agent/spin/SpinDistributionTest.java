/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.agent.spin;

import ai.kompile.cli.agent.AgentCliMain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpinDistributionTest {
    @TempDir
    Path temp;

    @Test
    void agentCliRegistersTheSpinLifecycle() {
        CommandLine command = new CommandLine(new AgentCliMain());
        CommandLine spin = command.getSubcommands().get("spin");
        assertTrue(spin != null);
        assertTrue(spin.getSubcommands().keySet().containsAll(List.of(
                "init", "keygen", "sign", "validate", "build", "install", "launch",
                "exec", "doctor")));
    }

    @Test
    void initCreatesAValidEditableSpinScaffold() throws Exception {
        Path scaffold = temp.resolve("support-assistant");
        int exit = new CommandLine(new SpinCommand()).execute(
                "init", scaffold.toString(), "--version", "2.0.0");

        assertEquals(0, exit);
        SpinDefinition definition = SpinDefinition.load(scaffold);
        assertEquals("support-assistant", definition.id());
        assertEquals("2.0.0", definition.version());
        assertTrue(Files.isRegularFile(scaffold.resolve("prompt.md")));
        assertTrue(Files.isDirectory(scaffold.resolve("tools")));
        assertTrue(Files.isDirectory(scaffold.resolve("models")));
    }

    @Test
    void initRejectsInvalidIdentityBeforeWritingAPartialScaffold() {
        Path scaffold = temp.resolve("invalid-spin");
        int exit = new CommandLine(new SpinCommand()).execute(
                "init", scaffold.toString(), "--id", "Invalid/Spin");

        assertEquals(1, exit);
        assertFalse(Files.exists(scaffold));
    }

    @Test
    void cliBuildSignsValidatesAndInstallsWithATrustedPublisher() throws Exception {
        Path source = createSpinSource("thin");
        Path privateKey = temp.resolve("publisher-private.pem");
        Path publicKey = temp.resolve("publisher-public.pem");
        Path archive = temp.resolve("signed.kspin");
        Path home = temp.resolve("signed-home");
        CommandLine command = new CommandLine(new SpinCommand());

        assertEquals(0, command.execute("keygen",
                "--private-key", privateKey.toString(),
                "--public-key", publicKey.toString()));
        assertEquals(0, command.execute("build", source.toString(),
                "--output", archive.toString(), "--signing-key", privateKey.toString()));
        Path signature = SpinSignature.defaultSignaturePath(archive);
        assertTrue(Files.isRegularFile(signature));
        SpinSignature.Verification verified = SpinSignature.verify(archive, signature, publicKey);

        assertEquals(0, command.execute("validate", archive.toString(),
                "--trusted-key", publicKey.toString()));
        assertEquals(0, command.execute("install", archive.toString(),
                "--home", home.toString(), "--trusted-key", publicKey.toString()));
        SpinInstallation.Installed installed = SpinInstallation.install(
                archive, home, null, publicKey);
        assertEquals(verified.publicKeySha256(), installed.publisherKeySha256());
    }

    @Test
    void trustedPublisherVerificationRejectsTamperingAndAnotherKey() throws Exception {
        Path source = createSpinSource("thin");
        Path archive = temp.resolve("publisher.kspin");
        SpinArchive.build(source, archive, null);
        SpinSignature.KeyFiles publisher = SpinSignature.generateKeyPair(
                temp.resolve("publisher-private.pem"), temp.resolve("publisher-public.pem"), false);
        SpinSignature.KeyFiles stranger = SpinSignature.generateKeyPair(
                temp.resolve("stranger-private.pem"), temp.resolve("stranger-public.pem"), false);
        Path signature = SpinSignature.defaultSignaturePath(archive);
        SpinSignature.sign(archive, publisher.privateKey(), signature);

        IOException wrongKey = assertThrows(IOException.class,
                () -> SpinSignature.verify(archive, signature, stranger.publicKey()));
        assertTrue(wrongKey.getMessage().contains("not valid"));

        Path tampered = temp.resolve("tampered-signed.kspin");
        Files.copy(archive, tampered);
        Files.write(tampered, new byte[]{0}, StandardOpenOption.APPEND);
        IOException changedArchive = assertThrows(IOException.class,
                () -> SpinSignature.verify(tampered, signature, publisher.publicKey()));
        assertTrue(changedArchive.getMessage().contains("SHA-256"));
        Path rejectedHome = temp.resolve("rejected-signed-home");
        assertThrows(IOException.class, () -> SpinInstallation.install(
                tampered, rejectedHome, signature, publisher.publicKey()));
        assertFalse(Files.exists(rejectedHome),
                "publisher verification must fail before creating the spin home");
    }

    @Test
    void stagedPublisherArchiveStaysBoundToTheVerifiedBytes() throws Exception {
        Path source = createSpinSource("thin");
        Path archive = temp.resolve("replaceable.kspin");
        SpinArchive.build(source, archive, null);
        SpinSignature.KeyFiles publisher = SpinSignature.generateKeyPair(
                temp.resolve("stable-private.pem"), temp.resolve("stable-public.pem"), false);
        Path signature = SpinSignature.defaultSignaturePath(archive);
        SpinSignature.sign(archive, publisher.privateKey(), signature);

        Path stagedPath;
        try (SpinSignature.VerifiedArchive verified = SpinSignature.stageVerified(
                archive, signature, publisher.publicKey())) {
            stagedPath = verified.archive();
            Files.writeString(archive, "replaced after verification",
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Path extracted = temp.resolve("staged-extracted");
            SpinDefinition definition = SpinArchive.extractVerified(stagedPath, extracted);
            assertEquals("demo-spin", definition.id());
        }
        assertFalse(Files.exists(stagedPath));
    }

    @Test
    void buildRejectsASigningKeyInsideTheSpinSource() throws Exception {
        Path source = createSpinSource("thin");
        SpinSignature.KeyFiles keys = SpinSignature.generateKeyPair(
                source.resolve("publisher-private.pem"),
                temp.resolve("publisher-public.pem"), false);
        Path archive = temp.resolve("must-not-build.kspin");

        int exit = new CommandLine(new SpinCommand()).execute(
                "build", source.toString(), "--output", archive.toString(),
                "--signing-key", keys.privateKey().toString());

        assertEquals(1, exit);
        assertFalse(Files.exists(archive));
    }

    @Test
    void buildsInstallsAndLaunchesPromptToolsAndModelWithoutPerRunCopies() throws Exception {
        Assumptions.assumeFalse(isWindows());
        Path source = createSpinSource("thin");
        Path first = temp.resolve("first.kspin");
        Path second = temp.resolve("second.kspin");

        SpinArchive.BuildResult built = SpinArchive.build(source, first, null);
        SpinArchive.build(source, second, null);

        assertEquals("demo-spin", built.id());
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "identical source must produce a byte-identical .kspin");

        Path home = temp.resolve("installed");
        SpinInstallation.Installed installed = SpinInstallation.install(first, home);
        Path release = installed.release();
        Path workspace = installed.workspace();

        assertTrue(Files.isRegularFile(release.resolve("models/demo.gguf")));
        assertTrue(Files.isRegularFile(release.resolve("models/tokenizer.json")));
        assertTrue(Files.isExecutable(release.resolve("tools/demo-mcp")));
        assertTrue(Files.readString(workspace.resolve(".kompile/roles/demo-role.md"))
                .contains("tools: mcp_tool_search, mcp_tool_call"));
        assertTrue(Files.readString(workspace.resolve("AGENTS.md"))
                .contains("Ground answers in packaged evidence"));
        assertTrue(Files.isRegularFile(workspace.resolve(".kompile/skills/demo/SKILL.md")));

        ObjectMapper mapper = new ObjectMapper();
        var mcp = mapper.readTree(workspace.resolve(".mcp.json").toFile())
                .path("mcpServers").path("demo-tools");
        assertEquals(release.resolve("tools/demo-mcp").toString(),
                mcp.path("command").asText());
        assertTrue(mcp.path("required").asBoolean());
        assertEquals("ping", mcp.path("includeTools").get(0).asText());
        assertEquals("${TEST_API_TOKEN}", mcp.path("env").path("API_TOKEN").asText());
        assertEquals(workspace.resolve("tool-state").toString(),
                mcp.path("env").path("STATE_DIR").asText());

        var chat = mapper.readTree(workspace.resolve(".kompile/chat-config.json").toFile());
        assertEquals("kompile-local", chat.path("provider").asText());
        assertEquals("demo-model", chat.path("model").asText());
        assertEquals("demo-role", chat.path("defaultAgent").asText());
        assertFalse(chat.path("defaultMemory").asBoolean(),
                "new spin personas must not inherit unrelated global memory by default");

        Path wrapperCapture = temp.resolve("wrapper-args.txt");
        Path fakeAgent = executable(temp.resolve("fake-kompile-agent"), """
                #!/usr/bin/env sh
                printf '%s\n' "$@" > "$SPIN_WRAPPER_CAPTURE"
                """);
        ProcessBuilder wrapper = new ProcessBuilder(
                home.resolve("bin/demo").toString(), "exec", "from wrapper");
        wrapper.environment().put("KOMPILE_AGENT", fakeAgent.toString());
        wrapper.environment().put("SPIN_WRAPPER_CAPTURE", wrapperCapture.toString());
        assertEquals(0, wrapper.start().waitFor());
        assertEquals(List.of("spin", "launch", "--home", home.toString(),
                        "exec", "from wrapper"),
                Files.readAllLines(wrapperCapture));
        String windowsLauncher = Files.readString(home.resolve("bin/demo.cmd"));
        assertTrue(windowsLauncher.contains("%~dp0.."));
        assertTrue(windowsLauncher.contains("%SPIN_HOME%\\components\\%SPIN_ID%\\current"));
        assertTrue(windowsLauncher.contains("%EMBEDDED%\\lib\\kompile-agent.jar"));
        assertTrue(windowsLauncher.contains("%KROOT%\\runtime\\bin\\java.exe"));

        Path argsCapture = temp.resolve("kompile-args.txt");
        Path envCapture = temp.resolve("kompile-env.txt");
        Path callsCapture = temp.resolve("kompile-calls.txt");
        Path fakeKompile = executable(temp.resolve("fake-kompile"), """
                #!/usr/bin/env sh
                printf '%s %s\n' "$1" "${2:-}" >> "$SPIN_CALLS_CAPTURE"
                printf '%s\n' "$@" > "$SPIN_ARGS_CAPTURE"
                printf '%s\n%s\n%s\n%s\n%s\n' \
                  "$KOMPILE_CHAT_MODEL_PATH" \
                  "$KOMPILE_CHAT_TOKENIZER_PATH" \
                  "$KOMPILE_MODEL_STAGE_DIR" \
                  "$KOMPILE_MCP_TRUSTED_WORKSPACE" \
                  "$KOMPILE_SPIN_ROOT" > "$SPIN_ENV_CAPTURE"
                """);
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("KOMPILE_CLI", fakeKompile.toString());
        environment.put("KOMPILE_MODEL_SERVING_EXECUTABLE", fakeKompile.toString());
        environment.put("SPIN_ARGS_CAPTURE", argsCapture.toString());
        environment.put("SPIN_ENV_CAPTURE", envCapture.toString());
        environment.put("SPIN_CALLS_CAPTURE", callsCapture.toString());

        assertEquals(0, SpinLauncher.launch(home, List.of("exec", "hello spin"), environment));
        List<String> args = Files.readAllLines(argsCapture);
        assertEquals("chat", args.get(0));
        assertTrue(args.contains("--working-dir"));
        assertTrue(args.contains(workspace.toString()));
        assertTrue(args.contains("--role"));
        assertTrue(args.contains("demo-role"));
        assertTrue(args.contains("--memory=false"));
        assertTrue(args.contains("hello spin"));
        assertEquals(List.of("project init", "chat --working-dir"),
                Files.readAllLines(callsCapture));
        assertEquals(List.of(
                        release.resolve("models/demo.gguf").toString(),
                        release.resolve("models/tokenizer.json").toString(),
                        workspace.resolve(".kompile/model-cache").toString(),
                        workspace.toString(),
                        release.toString()),
                Files.readAllLines(envCapture));

        assertEquals(0, SpinLauncher.launch(home, List.of(
                "crawl", "--offline", "--document", "tiny.md"), environment));
        List<String> crawlArgs = Files.readAllLines(argsCapture);
        assertEquals("crawl", crawlArgs.get(0));
        assertTrue(crawlArgs.contains("--memory=false"));
        assertTrue(crawlArgs.contains("tiny.md"));

        SpinInstallation.Installed repeated = SpinInstallation.install(first, home);
        assertEquals(installed.release(), repeated.release(),
                "reinstalling the same digest must reuse the qualified release");
        try (var releases = Files.list(home.resolve("components/demo-spin/releases"))) {
            assertEquals(1, releases.filter(path -> !path.getFileName().toString()
                    .startsWith(".staging-")).count());
        }
    }

    @Test
    void explicitMemoryOptInIsNotOverriddenByTheSpinLauncher() throws Exception {
        Assumptions.assumeFalse(isWindows());
        Path source = createSpinSource("thin");
        Path archive = temp.resolve("memory-opt-in.kspin");
        SpinArchive.build(source, archive, null);
        Path home = temp.resolve("memory-opt-in-home");
        SpinInstallation.install(archive, home);
        Path argsCapture = temp.resolve("memory-opt-in-args.txt");
        Path callsCapture = temp.resolve("memory-opt-in-calls.txt");
        Path fakeKompile = executable(temp.resolve("memory-opt-in-kompile"), """
                #!/usr/bin/env sh
                printf '%s %s\n' "$1" "${2:-}" >> "$SPIN_CALLS_CAPTURE"
                printf '%s\n' "$@" > "$SPIN_ARGS_CAPTURE"
                """);
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("KOMPILE_CLI", fakeKompile.toString());
        environment.put("KOMPILE_MODEL_SERVING_EXECUTABLE", fakeKompile.toString());
        environment.put("SPIN_ARGS_CAPTURE", argsCapture.toString());
        environment.put("SPIN_CALLS_CAPTURE", callsCapture.toString());

        assertEquals(0, SpinLauncher.launch(
                home, List.of("exec", "--memory", "use my memory"), environment));

        List<String> args = Files.readAllLines(argsCapture);
        assertTrue(args.contains("--memory"));
        assertFalse(args.contains("--memory=false"));
    }

    @Test
    void embeddedSpinUsesItsBundledKompileRuntime() throws Exception {
        Assumptions.assumeFalse(isWindows());
        Path source = createSpinSource("embedded");
        Path runtime = temp.resolve("runtime-source");
        Files.createDirectories(runtime.resolve("bin"));
        Files.createDirectories(runtime.resolve("lib"));
        Path capture = temp.resolve("embedded-args.txt");
        executable(runtime.resolve("bin/kompile"),
                "#!/usr/bin/env sh\nprintf '%s\\n' \"$@\" > '"
                        + capture.toString().replace("'", "'\\''") + "'\n");
        executable(runtime.resolve("bin/kompile-agent"), "#!/usr/bin/env sh\nexit 0\n");
        executable(runtime.resolve("bin/kompile-model-serving"), "#!/usr/bin/env sh\nexit 0\n");

        Path archive = temp.resolve("embedded.kspin");
        SpinArchive.build(source, archive, runtime);
        Path home = temp.resolve("embedded-home");
        SpinInstallation.Installed installed = SpinInstallation.install(archive, home);

        assertEquals(0, new CommandLine(new SpinCommand()).execute(
                "launch", "--home", home.toString(), "exec", "embedded"));
        assertEquals("chat", Files.readAllLines(capture).get(0));
        assertTrue(Files.isExecutable(installed.release().resolve("runtime/bin/kompile")));
    }

    @Test
    void embeddedDefaultLocalModelRequiresBundledModelServing() throws Exception {
        Path source = createSpinSource("embedded");
        Path runtime = temp.resolve("incomplete-runtime");
        Files.createDirectories(runtime.resolve("bin"));
        Files.createDirectories(runtime.resolve("lib"));
        Files.writeString(runtime.resolve("bin/kompile"), "runtime");
        Files.writeString(runtime.resolve("bin/kompile-agent"), "agent");

        IOException failure = assertThrows(IOException.class,
                () -> SpinArchive.build(source, temp.resolve("invalid-runtime.kspin"), runtime));
        assertTrue(failure.getMessage().contains("model-serving"));
    }

    @Test
    void rejectsLiteralSecretsInBundledMcpConfiguration() throws Exception {
        Path source = createSpinSource("thin");
        Path manifest = source.resolve("agent.yaml");
        Files.writeString(manifest, Files.readString(manifest)
                .replace("'${TEST_API_TOKEN}'", "'literal-secret'"));

        IOException failure = assertThrows(IOException.class,
                () -> SpinDefinition.load(source));
        assertTrue(failure.getMessage().contains("sensitive environment key API_TOKEN"));
    }

    @Test
    void rejectsUnsupportedDefaultLocalModelBeforeDistribution() throws Exception {
        Path source = createSpinSource("thin");
        Files.writeString(source.resolve("models/demo.bin"), "not-a-local-chat-model");
        Path manifest = source.resolve("agent.yaml");
        Files.writeString(manifest, Files.readString(manifest)
                .replace("models/demo.gguf", "models/demo.bin"));

        IOException failure = assertThrows(IOException.class,
                () -> SpinDefinition.load(source));
        assertTrue(failure.getMessage().contains(".gguf/.sdz"));
    }

    @Test
    void checksumVerificationDetectsAnInstalledModelMutation() throws Exception {
        Path source = createSpinSource("thin");
        Path archive = temp.resolve("verified.kspin");
        SpinArchive.build(source, archive, null);
        Path extracted = temp.resolve("extracted");
        SpinArchive.extractVerified(archive, extracted);
        Files.writeString(extracted.resolve("models/demo.gguf"), "tampered");

        IOException failure = assertThrows(IOException.class,
                () -> SpinArchive.verifyDirectory(extracted));
        assertTrue(failure.getMessage().contains("Checksum mismatch"));
    }

    @Test
    void rejectsOversizedChecksumManifestBeforeReadingItIntoMemory() throws Exception {
        Path release = Files.createDirectories(temp.resolve("oversized-inventory"));
        Path manifest = release.resolve("manifest.sha256");
        try (var channel = Files.newByteChannel(manifest,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(32L * 1024L * 1024L);
            channel.write(ByteBuffer.wrap(new byte[]{'x'}));
        }

        IOException failure = assertThrows(IOException.class,
                () -> SpinArchive.verifyDirectory(release));
        assertTrue(failure.getMessage().contains("checksum manifest exceeds"));
    }

    @Test
    void archiveExtractionRejectsTraversalBeforeWritingOutsideTarget() throws Exception {
        Path archive = temp.resolve("unsafe.kspin");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../escape"));
            zip.write("nope".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path destination = temp.resolve("unsafe-out");
        assertThrows(IOException.class, () -> SpinArchive.extractVerified(archive, destination));
        assertFalse(Files.exists(temp.resolve("escape")));
    }

    @Test
    void installerRejectsASymlinkedPersistentWorkspace() throws Exception {
        Assumptions.assumeFalse(isWindows());
        Path source = createSpinSource("thin");
        Path archive = temp.resolve("symlink.kspin");
        SpinArchive.build(source, archive, null);
        Path home = temp.resolve("symlink-home");
        Path outside = Files.createDirectories(temp.resolve("outside-workspace"));
        Files.createDirectories(home);
        Files.createSymbolicLink(home.resolve("workspace"), outside);

        IOException failure = assertThrows(IOException.class,
                () -> SpinInstallation.install(archive, home));
        assertTrue(failure.getMessage().contains("symbolic link"));
        assertFalse(Files.exists(outside.resolve("AGENTS.md")));
    }

    @Test
    void upgradeSwitchesImmutableReleaseAndPreservesWorkspaceData() throws Exception {
        Path source = createSpinSource("thin");
        Path firstArchive = temp.resolve("upgrade-1.kspin");
        SpinArchive.build(source, firstArchive, null);
        Path home = temp.resolve("upgrade-home");
        SpinInstallation.Installed first = SpinInstallation.install(firstArchive, home);
        Path userData = first.workspace().resolve("data/user-state.txt");
        Files.writeString(userData, "preserve me");

        Files.writeString(source.resolve("spin.yaml"),
                Files.readString(source.resolve("spin.yaml"))
                        .replace("version: 1.2.3", "version: 1.2.4"));
        Files.writeString(source.resolve("prompt.md"), "You are the upgraded assistant.\n");
        Files.writeString(source.resolve("agent.yaml"),
                Files.readString(source.resolve("agent.yaml"))
                        .replace("skills:\n  - path: skills/demo/SKILL.md\n    name: demo\n",
                                "skills: []\n")
                        .replace("name: demo-role", "name: upgraded-role"));
        Path secondArchive = temp.resolve("upgrade-2.kspin");
        SpinArchive.build(source, secondArchive, null);
        SpinInstallation.Installed second = SpinInstallation.install(secondArchive, home);

        assertFalse(first.release().equals(second.release()));
        assertEquals(second.releaseId(), Files.readString(
                home.resolve("components/demo-spin/current")).trim());
        assertEquals("preserve me", Files.readString(userData));
        assertFalse(Files.exists(second.workspace().resolve(".kompile/roles/demo-role.md")));
        assertFalse(Files.exists(second.workspace().resolve(".kompile/skills/demo/SKILL.md")));
        assertTrue(Files.readString(second.workspace().resolve(".kompile/roles/upgraded-role.md"))
                .contains("upgraded assistant"));
    }

    @Test
    void failedUpgradeRestoresPreviousManagedWorkspaceAndCurrentRelease() throws Exception {
        Path source = createSpinSource("thin");
        Path firstArchive = temp.resolve("rollback-1.kspin");
        SpinArchive.build(source, firstArchive, null);
        Path home = temp.resolve("rollback-home");
        SpinInstallation.Installed first = SpinInstallation.install(firstArchive, home);
        Path role = first.workspace().resolve(".kompile/roles/demo-role.md");
        Path skill = first.workspace().resolve(".kompile/skills/demo/SKILL.md");
        String previousRole = Files.readString(role);
        String previousSkill = Files.readString(skill);

        Files.writeString(source.resolve("spin.yaml"),
                Files.readString(source.resolve("spin.yaml"))
                        .replace("version: 1.2.3", "version: 1.2.4"));
        Files.writeString(source.resolve("skills/demo/SKILL.md"),
                "x".repeat(1024 * 1024 + 1));
        Path badArchive = temp.resolve("rollback-2.kspin");
        SpinArchive.build(source, badArchive, null);

        IOException failure = assertThrows(IOException.class,
                () -> SpinInstallation.install(badArchive, home));
        assertTrue(failure.getMessage().contains("exceeds"));
        assertEquals(first.releaseId(), Files.readString(
                home.resolve("components/demo-spin/current")).trim());
        assertEquals(previousRole, Files.readString(role));
        assertEquals(previousSkill, Files.readString(skill));
    }

    private Path createSpinSource(String delivery) throws IOException {
        Path source = temp.resolve("source-" + delivery);
        Files.createDirectories(source.resolve("tools"));
        Files.createDirectories(source.resolve("models"));
        Files.createDirectories(source.resolve("skills/demo"));
        Files.createDirectories(source.resolve("workspace/data/input_documents"));
        Files.writeString(source.resolve("spin.yaml"), """
                schemaVersion: '1'
                metadata:
                  id: demo-spin
                  version: 1.2.3
                  displayName: Demo Spin
                  command: demo
                runtime:
                  delivery: %s
                """.formatted(delivery));
        Files.writeString(source.resolve("agent.yaml"), """
                schemaVersion: '1'
                metadata:
                  name: demo-spin
                  description: Test spin
                engine: cli-loop
                systemPrompt:
                  path: prompt.md
                instructions:
                  - instructions.md
                skills:
                  - path: skills/demo/SKILL.md
                    name: demo
                role:
                  name: demo-role
                  displayName: Demo Role
                  canSpawn: false
                  tools:
                    - mcp_tool_search
                    - mcp_tool_call
                models:
                  - id: demo-model
                    path: models/demo.gguf
                    tokenizer: models/tokenizer.json
                    provider: kompile-local
                    default: true
                mcp:
                  servers:
                    - id: demo-tools
                      command: tools/demo-mcp
                      bundled: true
                      executable: true
                      required: true
                      timeoutSeconds: 12
                      includeTools: [ping]
                      env:
                        API_TOKEN: '${TEST_API_TOKEN}'
                        STATE_DIR: '${SPIN_WORKSPACE}/tool-state'
                """);
        Files.writeString(source.resolve("prompt.md"), "You are the packaged demo assistant.\n");
        Files.writeString(source.resolve("instructions.md"),
                "Ground answers in packaged evidence and tool results.\n");
        Files.writeString(source.resolve("skills/demo/SKILL.md"), """
                ---
                name: demo
                description: Demo packaged skill
                ---
                Use the bundled demo tool when requested.
                """);
        executable(source.resolve("tools/demo-mcp"), "#!/usr/bin/env sh\nexit 0\n");
        Files.writeString(source.resolve("models/demo.gguf"), "fake-gguf-model");
        Files.writeString(source.resolve("models/tokenizer.json"), "{\"model\":{}}\n");
        return source;
    }

    private static Path executable(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
