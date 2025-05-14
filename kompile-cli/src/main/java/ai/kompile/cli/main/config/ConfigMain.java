package ai.kompile.cli.main.config;

// All functional subcommands (Python-specific, DL4J/SameDiff specific like UpdaterGenerator, etc.)
// are now expected to be loaded dynamically via CliCommandRegistrar implementations
// found in their respective modules.

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "config",
        subcommands = {
                // Only universally applicable subcommands like HelpCommand remain static.
                CommandLine.HelpCommand.class
        },
        mixinStandardHelpOptions = true,
        description = "Configuration related commands for Kompile. Specific generators (Python, DL4J, etc.) are loaded dynamically.")
public class ConfigMain implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // This is called if 'kompile config' is run without any subcommand.
        // Since most subcommands are now dynamic, good help text is important.
        System.out.println("Available 'config' subcommands are loaded dynamically based on installed modules.");
        System.out.println("Use 'kompile config --help' to see currently available commands.");
        // Alternatively, throw an exception if a subcommand is always required.
        // throw new CommandLine.ParameterException(spec.commandLine(), "Missing subcommand for 'config'. Use --help to see available options.");
    }
}