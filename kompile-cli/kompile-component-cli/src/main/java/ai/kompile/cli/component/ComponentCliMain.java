/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.component;

import ai.kompile.cli.component.cmd.ComponentConfigCommand;
import ai.kompile.cli.component.cmd.ComponentListCommand;
import ai.kompile.cli.component.cmd.ComponentStatusCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

import java.util.concurrent.Callable;

/**
 * Main entry point for the Kompile Component CLI.
 * Provides multi-format output (text, JSON, YAML, CSV, table) for component information.
 * 
 * Usage examples:
 *   kompile-component list
 *   kompile-component list --format json
 *   kompile-component status kompile-app-main --format yaml
 *   kompile-component config kompile-app-main --format csv
 */
@Command(name = "kompile-component",
        mixinStandardHelpOptions = true,
        versionProvider = ComponentCliMain.VersionProvider.class,
        description = "Kompile Component CLI - Query and output component information in multiple formats",
        subcommands = {
                ComponentListCommand.class,
                ComponentStatusCommand.class,
                ComponentConfigCommand.class
        })
public class ComponentCliMain implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        System.out.println("Kompile Component CLI");
        System.out.println("======================");
        System.out.println();
        System.out.println("Usage: kompile-component <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  list      - List all components with their details");
        System.out.println("  status    - Check status of specific component(s)");
        System.out.println("  config    - Show component configuration");
        System.out.println();
        System.out.println("Output Formats (use --format flag):");
        System.out.println("  text      - Human-readable text (default)");
        System.out.println("  json      - JSON format");
        System.out.println("  yaml      - YAML format");
        System.out.println("  csv       - CSV format");
        System.out.println("  table     - ASCII table format");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  kompile-component list");
        System.out.println("  kompile-component list --format json");
        System.out.println("  kompile-component list --format yaml");
        System.out.println("  kompile-component status kompile-app-main");
        System.out.println("  kompile-component status kompile-app-main --format json");
        System.out.println("  kompile-component config kompile-app-main --format csv");
        System.out.println("  kompile-component config --all --format table");

        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ComponentCliMain())
                .setExitCodeExceptionMapper(new ComponentExitCodeMapper())
                .execute(args);
        System.exit(exitCode);
    }

    /**
     * Version provider that reads from package metadata
     */
    public static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() throws Exception {
            String implTitle = ComponentCliMain.class.getPackage().getImplementationTitle();
            String implVersion = ComponentCliMain.class.getPackage().getImplementationVersion();
            
            if (implTitle == null) {
                implTitle = "Kompile Component CLI";
            }
            if (implVersion == null) {
                implVersion = "0.1.0-SNAPSHOT";
            }
            
            return new String[]{implTitle + " version " + implVersion};
        }
    }

    /**
     * Exit code mapper for better script integration
     */
    public static class ComponentExitCodeMapper implements CommandLine.IExitCodeExceptionMapper {
        @Override
        public int getExitCode(Throwable exception) {
            if (exception instanceof CommandLine.ParameterException) {
                return 2; // Usage error
            }
            return 1; // General error
        }
    }
}
