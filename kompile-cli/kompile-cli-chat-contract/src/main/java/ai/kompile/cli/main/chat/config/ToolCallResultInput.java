/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/** A tool result fed back to the provider as a follow-up message. */
public class ToolCallResultInput {
    public String callId;
    public String name;
    public String output;
    public boolean isError;

    public ToolCallResultInput(String callId, String name, String output, boolean isError) {
        this.callId = callId;
        this.name = name;
        this.output = output;
        this.isError = isError;
    }
}
