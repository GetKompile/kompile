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