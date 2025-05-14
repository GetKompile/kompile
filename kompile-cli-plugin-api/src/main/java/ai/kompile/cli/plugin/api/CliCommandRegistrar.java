package ai.kompile.cli.plugin.api;

import picocli.CommandLine;

/**
 * Interface for modules to contribute CLI subcommands to the main application.
 * Implementations of this interface will be discovered using ServiceLoader.
 */
@FunctionalInterface
public interface CliCommandRegistrar {
    /**
     * Registers subcommands with the main application's CommandLine instance.
     * Implementations are responsible for adding their commands to the appropriate
     * parent command (e.g., "config", "exec", or the root command).
     *
     * @param mainAppCommandLine The picocli CommandLine instance of the main application.
     * Use mainAppCommandLine.getSubcommands().get("groupName")
     * to get a specific command group.
     */
    void registerCommands(CommandLine mainAppCommandLine);
}