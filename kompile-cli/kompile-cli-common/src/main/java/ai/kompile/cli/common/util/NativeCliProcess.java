/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.util;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Spawn conventions for native CLI agent processes (opencode, and any future
 * native provider that follows the same contract).
 *
 * <p>Bun-based CLIs such as {@code opencode run} treat piped stdin as an
 * alternate prompt source and block until that pipe reaches EOF. Java
 * {@link ProcessBuilder}'s default stdin is an open pipe whose write end the
 * parent never closes, so such processes sit silent forever — observed as
 * zero-byte provider responses in Kompile Chat.</p>
 *
 * <p>Kompile's native-provider transports always pass the prompt as a command
 * argument or a short-lived write-then-close stdin, so unattended native CLI
 * spawns get an immediately-closed stdin (the platform null device).</p>
 *
 * <p>This does NOT apply to spawn sites that deliberately feed the child's
 * stdin (persistent agent processes, MCP stdio, LSP, PTY passthrough, JSON-RPC
 * app-servers): those must keep the piped stdin.</p>
 */
public final class NativeCliProcess {

    private NativeCliProcess() {
    }

    /**
     * ProcessBuilder template for unattended native CLI spawns: working
     * directory set, agent-provider environment applied, and stdin closed via
     * the platform null device so prompt-source stdin blocking cannot occur.
     */
    public static ProcessBuilder processBuilder(
            List<String> command, java.nio.file.Path workingDirectory) {
        return new ProcessBuilder(command)
                .directory(workingDirectory == null ? null : workingDirectory.toFile())
                .redirectInput(nullDeviceRedirect());
    }

    /** Platform null device as a ProcessBuilder stdin redirect. */
    public static ProcessBuilder.Redirect nullDeviceRedirect() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return ProcessBuilder.Redirect.from(
                new File(osName.contains("win") ? "NUL" : "/dev/null"));
    }
}
