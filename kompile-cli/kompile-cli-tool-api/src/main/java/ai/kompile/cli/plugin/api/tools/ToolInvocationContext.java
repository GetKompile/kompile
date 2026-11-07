/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api.tools;

import ai.kompile.cli.main.chat.tools.ToolExecutionException;

import java.nio.file.Path;

/** Host-independent session, permission and path access for one tool invocation. */
public interface ToolInvocationContext {
    String getSessionId();

    Path getWorkingDirectory();

    boolean isAborted();

    void checkPermission(String permissionKey, String description) throws ToolExecutionException;

    Path resolvePath(String path) throws ToolExecutionException;
}
