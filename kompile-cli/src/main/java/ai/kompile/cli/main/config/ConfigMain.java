/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

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