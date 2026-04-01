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

package ai.kompile.cli.app;

import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "kompile-app",
        subcommands = {
                AppStartCommand.class,
                AppStopCommand.class,
                AppStatusCommand.class,
                AppIngestCommand.class,
                AppQueryCommand.class,
                AppSearchCommand.class,
                AppConfigCommand.class,
                AppLogsCommand.class
        },
        mixinStandardHelpOptions = true,
        usageHelpAutoWidth = true,
        description = "Manage running Kompile RAG applications.")
public class AppCliMain implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("No command specified. Showing help:\n");
        new CommandLine(this).usage(System.err);
        return 0;
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new AppCliMain()).execute(args);
        System.exit(exitCode);
    }
}
