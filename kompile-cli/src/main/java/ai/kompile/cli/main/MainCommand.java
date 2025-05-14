/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main;

// Existing imports from your MainCommand.java (ensure these map to actual files you have)
// For classes like Info, BuildMain etc., I'm using the names as they appear in your pom.xml/uploaded files.
import ai.kompile.cli.main.build.BuildMain;
import ai.kompile.cli.main.config.ConfigMain;
import ai.kompile.cli.main.exec.ExecMain;
import ai.kompile.cli.main.helpers.HelperEntry;
import ai.kompile.cli.main.install.InstallMain;
import ai.kompile.cli.main.uninstall.UnInstallMain;
// import ai.kompile.cli.main.util.EnvironmentUtils; // Removed direct static .init() call
import ai.kompile.cli.plugin.api.CliCommandRegistrar; // New import for plugin API
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.ServiceLoader; // New import
import java.util.concurrent.Callable;


@Command(name = "kompile",
        subcommands = {
                InstallMain.class,    // Assuming this class exists in this package structure
                ConfigMain.class,
                ExecMain.class,
                Info.class,           // Assuming this class exists
                BuildMain.class,      // Assuming this class exists
                HelperEntry.class,    // Assuming this class exists
                UnInstallMain.class,  // Assuming this class exists
                CommandLine.HelpCommand.class
        },
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class, // Using the new VersionProvider
        description = "Kompile CLI for managing and executing AI pipelines.")
public class MainCommand implements Callable<Integer> {

    static {
        // The direct call to EnvironmentUtils.init() was removed as the provided
        // EnvironmentUtils.java does not contain a static init() method.
        // If any static initialization is needed at CLI startup, it should be placed here
        // or called from a dedicated bootstrap mechanism if EnvironmentUtils requires it.

        // Attempt to load the StaticLoggerBinder to trigger SLF4J initialization.
        // Class.forName(StaticLoggerBinder.class.getName());
    }


    @Override
    public Integer call() throws Exception {
        System.setProperty("picocli.ansi", "true");
        new CommandLine(this).usage(System.out);
        return 0;
    }

    public static void main(String[] args) {
        System.setProperty("picocli.ansi", "true");
        CommandLine cmd = new CommandLine(new MainCommand());

        ServiceLoader<CliCommandRegistrar> registrars = ServiceLoader.load(CliCommandRegistrar.class);
        for (CliCommandRegistrar registrar : registrars) {
            try {
                registrar.registerCommands(cmd);
            } catch (Exception e) {
                System.err.println("Error registering commands from plugin " + registrar.getClass().getName() + ": " + e.getMessage());
                // e.printStackTrace(); // Uncomment for debugging plugin loading issues
            }
        }

        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }
}