/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.agent.spin;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/** Create, validate, package, install, and launch custom Kompile distributions. */
@CommandLine.Command(name = "spin",
        description = "Build and run installable Kompile spins with custom prompts, MCP tools, and models.",
        mixinStandardHelpOptions = true,
        subcommands = {
                SpinCommand.Init.class,
                SpinCommand.Keygen.class,
                SpinCommand.Sign.class,
                SpinCommand.Validate.class,
                SpinCommand.Build.class,
                SpinCommand.Install.class,
                SpinCommand.Launch.class,
                SpinCommand.Exec.class,
                SpinCommand.Doctor.class
        })
public final class SpinCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        new CommandLine(this).usage(System.err);
        return 0;
    }

    @CommandLine.Command(name = "init", description = "Create a new spin source directory.",
            mixinStandardHelpOptions = true)
    public static final class Init implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Directory to create")
        private Path directory;
        @CommandLine.Option(names = "--id", description = "Stable lowercase spin id")
        private String id;
        @CommandLine.Option(names = "--version", defaultValue = "0.1.0",
                description = "Initial spin version")
        private String version;
        @CommandLine.Option(names = "--display-name", description = "Human-readable name")
        private String displayName;

        @Override
        public Integer call() {
            try {
                Path root = directory.toAbsolutePath().normalize();
                String resolvedId = id == null || id.isBlank()
                        ? slug(root.getFileName().toString()) : id.trim();
                String resolvedDisplay = displayName == null || displayName.isBlank()
                        ? title(resolvedId) : displayName.trim();
                if (!resolvedId.matches("[a-z][a-z0-9-]{0,62}")) {
                    throw new IOException("Invalid spin id: " + resolvedId);
                }
                if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")) {
                    throw new IOException("Invalid spin version: " + version);
                }
                if (Files.isSymbolicLink(root)) {
                    throw new IOException("Spin directory must not be a symbolic link: " + root);
                }
                if (Files.exists(root)) {
                    try (var files = Files.list(root)) {
                        if (files.findAny().isPresent()) {
                            throw new IOException("Spin directory is not empty: " + root);
                        }
                    }
                }
                Files.createDirectories(root.resolve("tools"));
                Files.createDirectories(root.resolve("models"));
                Files.createDirectories(root.resolve("workspace/data/input_documents"));
                Files.writeString(root.resolve("spin.yaml"), """
                        schemaVersion: '1'
                        metadata:
                          id: %s
                          version: %s
                          displayName: %s
                          command: %s
                        runtime:
                          delivery: thin
                        """.formatted(resolvedId, version, yamlScalar(resolvedDisplay), resolvedId));
                Files.writeString(root.resolve("agent.yaml"), """
                        schemaVersion: '1'
                        metadata:
                          name: %s
                          description: %s
                        engine: cli-loop
                        systemPrompt:
                          path: prompt.md
                        instructions:
                          - instructions.md
                        role:
                          name: %s
                          displayName: %s
                          canSpawn: false
                          tools: '*'
                        models: []
                        mcp:
                          servers: []
                        """.formatted(resolvedId,
                        yamlScalar("Custom Kompile spin " + resolvedDisplay), resolvedId,
                        yamlScalar(resolvedDisplay)));
                Files.writeString(root.resolve("prompt.md"),
                        "You are " + resolvedDisplay + ". Follow the packaged instructions and use tools when needed.\n");
                Files.writeString(root.resolve("instructions.md"),
                        "Use only authoritative tool results for stateful claims. Never embed credentials in this spin.\n");
                Files.writeString(root.resolve("tools/README.md"), """
                        # Bundled MCP tools

                        Put an MCP stdio executable here, then declare it in `agent.yaml`:

                        ```yaml
                        mcp:
                          servers:
                            - id: my-tools
                              command: tools/my-mcp-server
                              bundled: true
                              executable: true
                              required: true
                        ```
                        """);
                Files.writeString(root.resolve("models/README.md"), """
                        # Bundled models

                        Put model artifacts here, then declare them in `agent.yaml`:

                        ```yaml
                        models:
                          - id: local-model
                            path: models/model.gguf
                            tokenizer: models/tokenizer.json
                            provider: kompile-local
                            default: true
                        ```
                        """);
                SpinDefinition definition = SpinDefinition.load(root);
                System.out.println("Created spin " + definition.id() + " at " + root);
                return 0;
            } catch (Exception e) {
                System.err.println("Could not initialize spin: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "keygen",
            description = "Generate an Ed25519 publisher key pair for spin releases.",
            mixinStandardHelpOptions = true)
    public static final class Keygen implements Callable<Integer> {
        @CommandLine.Option(names = "--private-key", required = true,
                description = "PEM private-key output (keep outside the spin source)")
        private Path privateKey;
        @CommandLine.Option(names = "--public-key", required = true,
                description = "PEM public-key output to distribute to users")
        private Path publicKey;
        @CommandLine.Option(names = "--force",
                description = "Replace existing key files")
        private boolean force;

        @Override
        public Integer call() {
            try {
                SpinSignature.KeyFiles keys = SpinSignature.generateKeyPair(
                        privateKey, publicKey, force);
                System.out.println("Generated Ed25519 publisher key pair");
                System.out.println("  private: " + keys.privateKey());
                System.out.println("  public:  " + keys.publicKey());
                System.out.println("  key sha256: " + keys.publicKeySha256());
                return 0;
            } catch (Exception e) {
                System.err.println("Could not generate publisher keys: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "sign",
            description = "Create a detached Ed25519 signature for a .kspin archive.",
            mixinStandardHelpOptions = true)
    public static final class Sign implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = ".kspin archive to sign")
        private Path archive;
        @CommandLine.Option(names = "--private-key", required = true,
                description = "Publisher PEM private key")
        private Path privateKey;
        @CommandLine.Option(names = {"-o", "--output"},
                description = "Detached signature output (default: <archive>.sig)")
        private Path output;

        @Override
        public Integer call() {
            try {
                SpinArchive.Identity identity = SpinArchive.readIdentity(archive);
                Path target = output == null ? SpinSignature.defaultSignaturePath(archive)
                        : output.toAbsolutePath().normalize();
                SpinSignature.Signed signed = SpinSignature.sign(archive, privateKey, target);
                System.out.println("Signed spin: " + identity.id() + " " + identity.version());
                System.out.println("  signature: " + signed.signatureFile());
                System.out.println("  sha256:    " + signed.archiveSha256());
                return 0;
            } catch (Exception e) {
                System.err.println("Could not sign spin: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "validate", description = "Validate a spin directory or .kspin archive.",
            mixinStandardHelpOptions = true)
    public static final class Validate implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Spin directory or .kspin archive")
        private Path source;
        @CommandLine.Option(names = "--signature",
                description = "Detached signature (default with --trusted-key: <archive>.sig)")
        private Path signature;
        @CommandLine.Option(names = "--trusted-key",
                description = "Trusted publisher PEM public key; requires a valid signature")
        private Path trustedKey;

        @Override
        public Integer call() {
            Path temporary = null;
            try {
                SpinDefinition definition;
                SpinSignature.Verification publisher = null;
                if (Files.isDirectory(source)) {
                    if (signature != null || trustedKey != null) {
                        throw new IOException("Publisher signatures apply to .kspin archives, not source directories");
                    }
                    definition = SpinDefinition.load(source);
                } else {
                    temporary = Files.createTempDirectory("kompile-spin-validate-");
                    try (SpinSignature.VerifiedArchive verified = stagePublisher(
                            source, signature, trustedKey)) {
                        Path archive = verified == null ? source : verified.archive();
                        publisher = verified == null ? null : verified.verification();
                        definition = SpinArchive.extractVerified(archive, temporary);
                    }
                }
                printDefinition(definition);
                if (publisher != null) {
                    System.out.println("Publisher key SHA-256: " + publisher.publicKeySha256());
                }
                System.out.println("Status: valid");
                return 0;
            } catch (Exception e) {
                System.err.println("Invalid spin: " + e.getMessage());
                return 1;
            } finally {
                SpinArchive.deleteTree(temporary);
            }
        }
    }

    @CommandLine.Command(name = "build", description = "Build a deterministic .kspin archive.",
            mixinStandardHelpOptions = true)
    public static final class Build implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = "Spin source directory")
        private Path source;
        @CommandLine.Option(names = {"-o", "--output"}, description = "Output .kspin path")
        private Path output;
        @CommandLine.Option(names = "--runtime-dir",
                description = "Extracted Kompile distribution to embed under runtime/")
        private Path runtimeDirectory;
        @CommandLine.Option(names = "--signing-key",
                description = "Publisher PEM private key; must be outside the spin source")
        private Path signingKey;
        @CommandLine.Option(names = "--signature-output",
                description = "Detached signature output (default: <archive>.sig)")
        private Path signatureOutput;

        @Override
        public Integer call() {
            try {
                Path root = source.toAbsolutePath().normalize();
                Path target = output == null
                        ? root.resolveSibling(root.getFileName() + ".kspin")
                        : output.toAbsolutePath().normalize();
                if (signatureOutput != null && signingKey == null) {
                    throw new IOException("--signature-output requires --signing-key");
                }
                Path signatureTarget = null;
                if (signingKey != null) {
                    SpinSignature.validatePrivateKey(signingKey);
                    if (signingKey.toRealPath().startsWith(root.toRealPath())) {
                        throw new IOException("Signing key must be outside the spin source so it cannot be bundled");
                    }
                    signatureTarget = signatureOutput == null
                            ? SpinSignature.defaultSignaturePath(target)
                            : signatureOutput.toAbsolutePath().normalize();
                    if (signatureTarget.startsWith(root)) {
                        throw new IOException("Signature output must be outside the spin source");
                    }
                }
                SpinArchive.BuildResult result = SpinArchive.build(root, target, runtimeDirectory);
                System.out.println("Built spin: " + result.archive());
                System.out.println("  id:      " + result.id());
                System.out.println("  version: " + result.version());
                System.out.println("  sha256:  " + result.sha256());
                System.out.println("  bytes:   " + result.bytes());
                if (signingKey != null) {
                    try {
                        SpinSignature.Signed signed = SpinSignature.sign(
                                result.archive(), signingKey, signatureTarget);
                        System.out.println("  signature: " + signed.signatureFile());
                    } catch (Exception e) {
                        Files.deleteIfExists(signatureTarget);
                        throw e;
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Could not build spin: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "install", description = "Verify and install a .kspin archive.",
            mixinStandardHelpOptions = true)
    public static final class Install implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", description = ".kspin archive")
        private Path archive;
        @CommandLine.Option(names = "--home", description = "Spin home (default: ~/.<spin-id>)")
        private Path home;
        @CommandLine.Option(names = "--signature",
                description = "Detached signature (default with --trusted-key: <archive>.sig)")
        private Path signature;
        @CommandLine.Option(names = "--trusted-key",
                description = "Trusted publisher PEM public key; requires a valid signature")
        private Path trustedKey;

        @Override
        public Integer call() {
            try {
                SpinInstallation.Installed installed = SpinInstallation.install(
                        archive, home, signature, trustedKey);
                System.out.println("Installed " + installed.definition().displayName()
                        + " " + installed.definition().version());
                System.out.println("  home:      " + installed.home());
                System.out.println("  release:   " + installed.release());
                System.out.println("  workspace: " + installed.workspace());
                if (installed.publisherKeySha256() != null) {
                    System.out.println("  publisher key sha256: "
                            + installed.publisherKeySha256());
                }
                System.out.println("  command:   " + installed.home().resolve("bin")
                        .resolve(installed.definition().commandName()));
                return 0;
            } catch (Exception e) {
                System.err.println("Could not install spin: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "launch", aliases = "run",
            description = "Launch the current installed spin.", mixinStandardHelpOptions = true)
    public static final class Launch implements Callable<Integer> {
        @CommandLine.Option(names = "--home", required = true, description = "Installed spin home")
        private Path home;
        @CommandLine.Unmatched
        private List<String> arguments = new ArrayList<>();

        @Override
        public Integer call() {
            try {
                return SpinLauncher.launch(home, arguments);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 130;
            } catch (Exception e) {
                System.err.println("Could not launch spin: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "exec", description = "Run one headless turn through an installed spin.",
            mixinStandardHelpOptions = true)
    public static final class Exec implements Callable<Integer> {
        @CommandLine.Option(names = "--home", required = true, description = "Installed spin home")
        private Path home;
        @CommandLine.Parameters(arity = "1..*", description = "Prompt and optional chat arguments")
        private List<String> prompt = new ArrayList<>();

        @Override
        public Integer call() {
            try {
                List<String> args = new ArrayList<>();
                args.add("exec");
                args.addAll(prompt);
                return SpinLauncher.launch(home, args);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 130;
            } catch (Exception e) {
                System.err.println("Could not execute spin: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "doctor", description = "Verify an installed spin and its artifacts.",
            mixinStandardHelpOptions = true)
    public static final class Doctor implements Callable<Integer> {
        @CommandLine.Option(names = "--home", required = true, description = "Installed spin home")
        private Path home;

        @Override
        public Integer call() {
            return SpinLauncher.doctor(home);
        }
    }

    private static void printDefinition(SpinDefinition definition) {
        System.out.println("Spin: " + definition.displayName());
        System.out.println("ID: " + definition.id());
        System.out.println("Version: " + definition.version());
        System.out.println("Delivery: " + definition.delivery());
        System.out.println("Role: " + definition.roleName());
        System.out.println("Bundled models: " + definition.models().size());
        System.out.println("MCP servers: " + definition.mcpServers().size());
    }

    private static SpinSignature.VerifiedArchive stagePublisher(
            Path archive, Path signature, Path trustedKey) throws IOException {
        if (trustedKey == null) {
            if (signature != null) {
                throw new IOException("--signature requires --trusted-key");
            }
            return null;
        }
        Path sidecar = signature == null ? SpinSignature.defaultSignaturePath(archive) : signature;
        return SpinSignature.stageVerified(archive, sidecar, trustedKey);
    }

    private static String slug(String value) {
        String result = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (result.isBlank()) return "my-spin";
        return result.length() > 63 ? result.substring(0, 63).replaceAll("-+$", "") : result;
    }

    private static String title(String id) {
        StringBuilder value = new StringBuilder();
        for (String part : id.split("-")) {
            if (part.isBlank()) continue;
            if (value.length() > 0) value.append(' ');
            value.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return value.toString();
    }

    private static String yamlScalar(String value) {
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return "'" + oneLine.replace("'", "''") + "'";
    }
}
