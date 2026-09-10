/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.channel;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelAuthCommandTest {

    @Test
    void registersUnifiedRunAndTelegramLifecycleCommands() {
        CommandLine command = new CommandLine(new ChannelAuthCommand());

        assertFalse(command.getSubcommands().containsKey("setup"));
        assertTrue(command.getSubcommands().containsKey("run"));
        assertTrue(command.getSubcommands().containsKey("web-login"));
        assertTrue(command.getSubcommands().containsKey("login"));
        assertTrue(command.getSubcommands().containsKey("auth-status"));
        assertTrue(command.getSubcommands().containsKey("telegram"));
        CommandLine telegram = command.getSubcommands().get("telegram");
        assertTrue(telegram.getSubcommands().keySet().containsAll(java.util.Set.of(
                "pair", "status", "approve", "cancel", "diagnostics", "webhook",
                "delete-webhook")));

        CommandLine.ParseResult parsed = command.parseArgs(
                "connect", "slack", "--oauth", "--non-interactive");
        ChannelAuthCommand.Connect connect =
                (ChannelAuthCommand.Connect) parsed.subcommand().commandSpec().userObject();
        assertTrue(connect.oauth);

        CommandLine.ParseResult interactiveLogin = command.parseArgs("login");
        ChannelAuthCommand.Login login =
                (ChannelAuthCommand.Login) interactiveLogin.subcommand().commandSpec().userObject();
        assertNull(login.providerId);
    }
}
