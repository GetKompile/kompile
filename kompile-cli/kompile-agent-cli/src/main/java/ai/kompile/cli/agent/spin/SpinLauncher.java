/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.agent.spin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Launches an installed spin through the ordinary Kompile chat/project commands. */
public final class SpinLauncher {
    private SpinLauncher() { }

    public static int launch(Path home, List<String> arguments) throws IOException, InterruptedException {
        return launch(home, arguments, System.getenv());
    }

    static int launch(Path home, List<String> arguments, Map<String, String> environment)
            throws IOException, InterruptedException {
        SpinInstallation.Current current = SpinInstallation.current(home);
        SpinWorkspace.Prepared prepared = SpinWorkspace.prepare(current.definition(), current.home());
        List<String> args = arguments == null ? List.of() : List.copyOf(arguments);

        if (!args.isEmpty() && ("version".equals(args.get(0))
                || "--version".equals(args.get(0)) || "-V".equals(args.get(0)))) {
            System.out.println(current.definition().commandName() + " "
                    + current.definition().version());
            return 0;
        }
        if (!args.isEmpty() && "doctor".equals(args.get(0))) {
            return doctor(current, prepared, environment);
        }
        if (!args.isEmpty() && "knowledge".equals(args.get(0))
                && (args.size() == 1 || "paths".equals(args.get(1)))) {
            printPaths(current, prepared);
            return 0;
        }

        CommandResolution kompile = resolveKompile(current, environment);
        verifyModelRuntime(current.definition(), kompile, environment);
        Map<String, String> childEnvironment = runtimeEnvironment(
                current.definition(), prepared, kompile, environment);
        List<String> command = commandFor(current.definition(), prepared, kompile.commandPrefix(), args);
        ensureProjectInitialized(current.definition(), prepared, kompile,
                childEnvironment);

        ProcessBuilder processBuilder = new ProcessBuilder(command).inheritIO();
        processBuilder.directory(prepared.workspace().toFile());
        processBuilder.environment().clear();
        processBuilder.environment().putAll(childEnvironment);

        return waitFor(processBuilder.start());
    }

    static List<String> commandFor(SpinDefinition definition, SpinWorkspace.Prepared prepared,
                                   List<String> kompilePrefix, List<String> args) throws IOException {
        if (definition.projectBacked() && definition.project().entrypoint() != null) {
            rejectProductOverrides(args);
            List<String> command = new ArrayList<>(executableCommand(definition.project().entrypoint()));
            if (!args.isEmpty() && Set.of("chat", "web", "exec").contains(args.get(0))) {
                if ("exec".equals(args.get(0)) && args.size() == 1) {
                    throw new IOException(definition.commandName() + " exec requires prompt text");
                }
                command.addAll(args);
            } else {
                if (!args.isEmpty() && Set.of("project", "crawl", "spin").contains(args.get(0))) {
                    throw new IOException("Product entrypoints support chat, web and exec, not " + args.get(0));
                }
                command.add(args.isEmpty() && definition.project().distribution().getWeb().isEnabled() ? "web" : "chat");
                command.addAll(args);
            }
            return List.copyOf(command);
        }
        List<String> command = new ArrayList<>(kompilePrefix);
        if (!args.isEmpty() && "project".equals(args.get(0))) {
            command.add("project");
            command.addAll(args.subList(1, args.size()));
        } else if (!args.isEmpty() && "crawl".equals(args.get(0))) {
            List<String> crawlArgs = normalizeMemoryOptions(args.subList(1, args.size()));
            command.add("crawl");
            if (!hasMemoryOption(crawlArgs)) {
                command.add("--memory=false");
            }
            command.addAll(crawlArgs);
        } else {
            List<String> chatArgs = args;
            if (!args.isEmpty() && ("chat".equals(args.get(0)) || "exec".equals(args.get(0)))) {
                if ("exec".equals(args.get(0)) && args.size() == 1) {
                    throw new IOException(definition.commandName()
                            + " exec requires prompt text");
                }
                chatArgs = args.subList(1, args.size());
            }
            chatArgs = normalizeMemoryOptions(chatArgs);
            rejectManagedIdentityOptions(chatArgs);
            command.add("chat");
            command.add("--working-dir");
            command.add(prepared.workspace().toString());
            command.add("--role");
            command.add(prepared.roleName());
            if (!hasMemoryOption(chatArgs)) {
                command.add("--memory=false");
            }
            if (args.isEmpty() && definition.projectBacked()
                    && definition.project().distribution().getWeb().isEnabled()) {
                command.add("--web");
                command.add("--open-browser");
            }
            command.addAll(chatArgs);
        }

        return List.copyOf(command);
    }

    static Map<String, String> runtimeEnvironment(
            SpinDefinition definition,
            SpinWorkspace.Prepared prepared,
            CommandResolution kompile,
            Map<String, String> environment) {
        Map<String, String> child = new LinkedHashMap<>(environment);
        child.put("KOMPILE_MCP_TRUSTED_WORKSPACE", prepared.workspace().toString());
        child.put("KOMPILE_SPIN_ROOT", prepared.releaseRoot().toString());
        child.put("KOMPILE_SPIN_HOME", prepared.home().toString());
        child.put("KOMPILE_SPIN_WORKSPACE", prepared.workspace().toString());
        if (kompile.installRoot() != null) {
            child.put("KOMPILE_INSTALL_DIR", kompile.installRoot().toString());
        }
        SpinDefinition.ModelAsset model = prepared.defaultModel();
        if (model != null && "kompile-local".equalsIgnoreCase(model.provider())) {
            child.put("KOMPILE_CHAT_MODEL_PATH", model.path().toString());
            child.put("KOMPILE_MODEL_STAGE_DIR", prepared.workspace()
                    .resolve(".kompile/model-cache").toString());
            if (model.tokenizer() != null) {
                child.put("KOMPILE_CHAT_TOKENIZER_PATH", model.tokenizer().toString());
            }
        }
        if (definition.projectBacked()) {
            for (String key : List.of("KOMPILE_CLI", "KOMPILE_AGENT", "KOMPILE_CHAT_MODEL_PATH",
                    "KOMPILE_CHAT_TOKENIZER_PATH", "KOMPILE_MODEL_SERVING_EXECUTABLE", "KOMPILE_MODEL_SERVING_JAR")) {
                child.remove(key);
            }
            child.put("KOMPILE_DIST_HOME", kompile.installRoot().toString());
            child.put("KOMPILE_CHAT_ADDRESS", definition.project().distribution().getWeb().getBind());
            Path java = kompile.installRoot().resolve("runtime/bin/java" + (isWindows() ? ".exe" : ""));
            if (Files.isExecutable(java)) child.put("KOMPILE_JAVA", java.toString());
        }
        return child;
    }

    private static void verifyModelRuntime(
            SpinDefinition definition,
            CommandResolution kompile,
            Map<String, String> environment) throws IOException {
        if (!definition.requiresLocalModelRuntime()) return;
        if (!definition.projectBacked() && (validConfiguredFile(environment.get("KOMPILE_MODEL_SERVING_EXECUTABLE"))
                || validConfiguredFile(environment.get("KOMPILE_MODEL_SERVING_JAR")))) {
            return;
        }
        if (kompile.installRoot() != null
                && SpinDefinition.hasModelServingRuntime(kompile.installRoot())) {
            return;
        }
        throw new IOException("The spin's default kompile-local model requires "
                + "kompile-model-serving; use a local/full embedded runtime or install "
                + "a Kompile distribution that includes model serving");
    }

    private static boolean validConfiguredFile(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return Files.isRegularFile(Path.of(value).toAbsolutePath().normalize());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void ensureProjectInitialized(
            SpinDefinition definition,
            SpinWorkspace.Prepared prepared,
            CommandResolution kompile,
            Map<String, String> environment) throws IOException, InterruptedException {
        if (Files.isRegularFile(prepared.workspace().resolve("kompile.project.json"))) return;
        List<String> command = new ArrayList<>(kompile.commandPrefix());
        command.addAll(List.of(
                "project", "init",
                "--root", prepared.workspace().toString(),
                "--name", definition.displayName(),
                "--description", "Persistent project for the " + definition.displayName() + " spin",
                "--backend", "local",
                "--no-auto-detect"));
        ProcessBuilder initializer = new ProcessBuilder(command).inheritIO();
        initializer.directory(prepared.workspace().toFile());
        initializer.environment().putAll(environment);
        int exit = waitFor(initializer.start());
        if (exit != 0) {
            throw new IOException("Kompile project initialization failed with exit code " + exit);
        }
        // Project initialization may provision generic role/MCP/chat files. Reapply
        // the checksummed spin-owned surface before the first model turn.
        SpinWorkspace.prepare(definition, prepared.home());
    }

    private static int waitFor(Process process) throws InterruptedException {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    public static int doctor(Path home) {
        try {
            SpinInstallation.Current current = SpinInstallation.current(home);
            SpinWorkspace.Prepared prepared = SpinWorkspace.prepare(
                    current.definition(), current.home());
            return doctor(current, prepared, System.getenv());
        } catch (Exception e) {
            System.err.println("Spin doctor failed: " + e.getMessage());
            return 1;
        }
    }

    private static int doctor(SpinInstallation.Current current,
                              SpinWorkspace.Prepared prepared,
                              Map<String, String> environment) {
        int failures = 0;
        System.out.println("Spin:              " + current.definition().displayName());
        System.out.println("ID:                " + current.definition().id());
        System.out.println("Version:           " + current.definition().version());
        System.out.println("Delivery:          " + current.definition().delivery());
        System.out.println("Home:              " + current.home());
        System.out.println("Release:           " + current.release());
        System.out.println("Workspace:         " + prepared.workspace());
        System.out.println("Role:              " + prepared.roleName());

        try {
            CommandResolution kompile = resolveKompile(current, environment);
            System.out.println("Kompile:           " + String.join(" ", kompile.commandPrefix()));
            verifyModelRuntime(current.definition(), kompile, environment);
            if (current.definition().requiresLocalModelRuntime()) {
                System.out.println("Model runtime:     ready");
            }
        } catch (IOException e) {
            System.out.println("Kompile/runtime:   MISSING");
            System.err.println("  " + e.getMessage());
            failures++;
        }

        for (SpinDefinition.McpServerAsset server : current.definition().mcpServers()) {
            if (!server.bundled()) continue;
            boolean ready = Files.isRegularFile(server.commandPath())
                    && (isWindows() || !server.executable()
                    || Files.isExecutable(server.commandPath()));
            System.out.println("MCP " + server.id() + ":" + spaces(server.id())
                    + (ready ? "ready" : "MISSING/NOT EXECUTABLE"));
            if (!ready) failures++;
        }
        for (SpinDefinition.ModelAsset model : current.definition().models()) {
            boolean ready = Files.exists(model.path())
                    && (model.tokenizer() == null || Files.isRegularFile(model.tokenizer()));
            System.out.println("Model " + model.id() + ":" + spaces(model.id())
                    + (ready ? model.path() : "MISSING"));
            if (!ready) failures++;
        }
        System.out.println("Status:            " + (failures == 0 ? "ready" : "failed"));
        return failures == 0 ? 0 : 1;
    }

    private static String spaces(String value) {
        return " ".repeat(Math.max(1, 16 - Math.min(15, value.length())));
    }

    private static void printPaths(SpinInstallation.Current current,
                                   SpinWorkspace.Prepared prepared) {
        System.out.println("spin_home=" + current.home());
        System.out.println("release=" + current.release());
        System.out.println("workspace=" + prepared.workspace());
        SpinDefinition.ModelAsset model = prepared.defaultModel();
        if (model != null) {
            System.out.println("default_model=" + model.path());
            if (model.tokenizer() != null) {
                System.out.println("default_tokenizer=" + model.tokenizer());
            }
        }
    }

    private static void rejectManagedIdentityOptions(List<String> arguments) throws IOException {
        for (String argument : arguments) {
            if (argument.equals("--working-dir") || argument.startsWith("--working-dir=")
                    || argument.equals("--role") || argument.startsWith("--role=")) {
                throw new IOException(argument + " is managed by the installed spin");
            }
        }
    }

    /** Product adapters pin identity/policy themselves; callers may tune ports and presentation only. */
    private static void rejectProductOverrides(List<String> arguments) throws IOException {
        Set<String> managed = Set.of("--product-profile", "--profile", "--working-dir", "--working-directory",
                "--workspace", "--root", "--home", "--role", "--roles", "--agent", "--mode", "--permissions", "--permission-mode",
                "--permission", "--dangerously-skip-permissions", "--skip-permissions", "--allow-all",
                "--yes", "--config", "--global-config", "--system-prompt", "--system-prompt-file",
                "--web-session-worker", "--web-controls");
        for (String argument : arguments) {
            if ("--".equals(argument)) break;
            String option = argument.split("=", 2)[0];
            if (managed.contains(option) || (argument.startsWith("-") && !argument.startsWith("--")
                    && argument.length() > 1 && "draCy".indexOf(argument.charAt(1)) >= 0)) {
                throw new IOException(option + " is managed by the installed spin product adapter");
            }
        }
    }

    private static boolean hasMemoryOption(List<String> arguments) {
        return arguments.stream().anyMatch(argument -> argument.equals("--memory")
                || argument.equals("--no-memory")
                || argument.startsWith("--memory=")
                || argument.startsWith("--no-memory="));
    }

    private static List<String> normalizeMemoryOptions(List<String> arguments) {
        List<String> normalized = new ArrayList<>(arguments.size());
        for (String argument : arguments) {
            normalized.add("--no-memory".equals(argument) ? "--memory=false" : argument);
        }
        return normalized;
    }

    static CommandResolution resolveKompile(SpinInstallation.Current current,
                                            Map<String, String> environment)
            throws IOException {
        if (current.definition().projectBacked()) {
            Path bundled = current.release().resolve("runtime");
            String delivery = current.definition().project().distribution().getDelivery();
            CommandResolution resolution = null;
            if (!"jar".equals(delivery)) {
                try {
                    Path binary = SpinDefinition.requireNative(bundled, "kompile");
                    resolution = new CommandResolution(executableCommand(binary), bundled);
                } catch (IOException noNative) {
                    if ("native".equals(delivery)) throw noNative;
                }
            }
            if (resolution == null && !"native".equals(delivery)) {
                resolution = jarResolution(bundled, true);
            }
            if (resolution == null) throw new IOException("Curated spin runtime is missing; global fallback is disabled");
            return resolution;
        }
        String explicit = environment.get("KOMPILE_CLI");
        if (explicit != null && !explicit.isBlank()) {
            Path path = Path.of(explicit).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)
                    || (!isWindowsScript(path) && !Files.isExecutable(path))) {
                throw new IOException("KOMPILE_CLI is not executable: " + path);
            }
            return new CommandResolution(executableCommand(path), inferInstallRoot(path));
        }

        Path embedded = current.release().resolve("runtime");
        CommandResolution found = executableResolution(embedded, "kompile");
        if (found != null) return found;
        found = jarResolution(embedded);
        if (found != null) return found;

        String configuredRoot = environment.get("KOMPILE_INSTALL_DIR");
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
            found = executableResolution(root, "kompile");
            if (found == null) found = jarResolution(root);
            if (found != null) return found;
        }

        Path defaultRoot = Path.of(System.getProperty("user.home"), ".kompile")
                .toAbsolutePath().normalize();
        found = executableResolution(defaultRoot, "kompile");
        if (found == null) found = jarResolution(defaultRoot);
        if (found != null) return found;

        Path fromPath = findOnPath("kompile", environment);
        if (fromPath != null) {
            return new CommandResolution(executableCommand(fromPath), inferInstallRoot(fromPath));
        }
        throw new IOException("No compatible Kompile executable was found; set KOMPILE_CLI "
                + "or install Kompile under ~/.kompile");
    }

    private static CommandResolution executableResolution(Path root, String name) {
        if (root == null) return null;
        for (String candidate : List.of(name, name + ".exe", name + ".cmd")) {
            Path executable = root.resolve("bin").resolve(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(executable) && (candidate.endsWith(".cmd")
                    || Files.isExecutable(executable))) {
                return new CommandResolution(executableCommand(executable), root);
            }
        }
        return null;
    }

    private static CommandResolution jarResolution(Path root) {
        return jarResolution(root, false);
    }

    private static CommandResolution jarResolution(Path root, boolean project) {
        if (root == null) return null;
        Path jar = root.resolve("lib/kompile-cli.jar").toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) return null;
        Path java = root.resolve("runtime/bin/java" + (isWindows() ? ".exe" : ""));
        String javaCommand = Files.isExecutable(java) ? java.toString() : "java";
        List<String> command = new ArrayList<>(List.of(javaCommand, "-Dkompile.dist.home=" + root));
        if (project) {
            command.add("-Djava.library.path=" + root.resolve("bin") + File.pathSeparator + root.resolve("lib"));
            command.add("-Dspring.config.additional-location=optional:file:" + root.resolve("conf") + "/");
        }
        command.addAll(List.of("-jar", jar.toString()));
        return new CommandResolution(List.copyOf(command), root);
    }

    private static Path findOnPath(String command, Map<String, String> environment) {
        String path = environment.get("PATH");
        if (path == null || path.isBlank()) return null;
        List<String> names = isWindows()
                ? List.of(command + ".exe", command + ".cmd", command + ".bat", command)
                : List.of(command);
        for (String directory : path.split(Patterns.pathSeparatorRegex())) {
            if (directory.isBlank()) continue;
            for (String name : names) {
                Path candidate = Path.of(directory).resolve(name).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate)
                        && (isWindows() || Files.isExecutable(candidate))) return candidate;
            }
        }
        return null;
    }

    private static Path inferInstallRoot(Path executable) {
        Path parent = executable.getParent();
        return parent != null && "bin".equalsIgnoreCase(parent.getFileName().toString())
                ? parent.getParent() : null;
    }

    private static List<String> executableCommand(Path executable) {
        return isWindowsScript(executable)
                ? List.of("cmd.exe", "/d", "/c", executable.toString())
                : List.of(executable.toString());
    }

    private static boolean isWindowsScript(Path executable) {
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        return isWindows() && (name.endsWith(".cmd") || name.endsWith(".bat"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    record CommandResolution(List<String> commandPrefix, Path installRoot) { }

    private static final class Patterns {
        private Patterns() { }
        private static String pathSeparatorRegex() {
            return java.util.regex.Pattern.quote(File.pathSeparator);
        }
    }
}
