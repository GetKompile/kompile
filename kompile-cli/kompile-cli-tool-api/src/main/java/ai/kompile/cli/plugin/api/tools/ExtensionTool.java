/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.plugin.api.tools;

import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A local CLI tool that executes on the client side, comparable to OpenCode's
 * tool system. Tools can read/write files, execute shell commands, search
 * codebases, and delegate work to subagents.
 */
public interface ExtensionTool<C extends ToolInvocationContext> {

    /** Unique tool identifier (e.g. "read", "bash", "grep"). */
    String id();

    /** Human-readable description for the LLM system prompt. */
    String description();

    /** JSON Schema describing the tool's parameters. */
    JsonNode parameterSchema();

    /** The permission key for this tool (e.g. "read", "edit", "bash"). */
    String permissionKey();

    /**
     * Execute the tool with the given parameters and context.
     *
     * @param params supplied arguments; implementations must validate their inputs against {@link #parameterSchema()}
     * @param context execution context with session info, permissions, abort signal
     * @return result containing output text, title, and metadata
     * @throws ToolExecutionException if the tool fails
     */
    ToolResult execute(JsonNode params, C context) throws ToolExecutionException;

    /**
     * MCP tool annotations that hint to clients about this tool's behavior.
     * Defaults to {@link McpToolAnnotations#WRITE} — tools that modify local state
     * but are not destructive. Override in subclasses for more precise hints.
     */
    default McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.WRITE;
    }

    /**
     * A curated one-line "compact form" of this tool, used when the MCP schema
     * optimizer compresses tool definitions at the COMPACT/AGGRESSIVE levels —
     * which otherwise blind-truncate {@link #description()} to a few dozen
     * characters and strip every parameter description. Return the single most
     * useful line an agent needs to call the tool correctly: its purpose plus
     * its #1 gotcha (valid parameter values, the common failure mode). Keep it
     * to one dense line (≲180 chars); it is substituted verbatim for the
     * truncated description. Return {@code null} (the default) to fall back to
     * blind truncation of {@link #description()}.
     */
    default String compactHint() {
        return null;
    }

    /**
     * Whether the running chat loop may detach this tool's invocation in the
     * background (the agent via a {@code background} parameter, or the user via
     * Ctrl+B while the call blocks). Long-running tools whose work survives
     * losing the foreground return true; quick read-style tools keep the
     * default false. Declaring the capability here — instead of a tool-name
     * switch in the chat loop — lets the backgroundable phase follow the tools
     * themselves, including through wrapper implementations.
     */
    default boolean isBackgroundable() {
        return false;
    }
}
