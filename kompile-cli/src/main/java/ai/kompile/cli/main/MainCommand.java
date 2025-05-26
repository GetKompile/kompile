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

package ai.kompile.cli.main;

import ai.kompile.cli.main.build.BuildMain;
import ai.kompile.cli.main.build.BuildRagApp;
import ai.kompile.cli.main.build.KompileApplicationBuilder;
import ai.kompile.cli.main.build.simplified.BuildHostedLlmRagApp;
import ai.kompile.cli.main.build.simplified.BuildSameDiffApp;
import ai.kompile.cli.main.config.ConfigMain;
import ai.kompile.cli.main.install.InstallMain;
import ai.kompile.cli.main.uninstall.UnInstallMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "kompile",
        subcommands = {
                Info.class,
                Bootstrap.class,
                ConfigMain.class,
                BuildMain.class,
                KompileApplicationBuilder.BuildKompileAppCommand.class,
                BuildRagApp.class,
                BuildHostedLlmRagApp.class,
                BuildSameDiffApp.class,
                InstallMain.class,
                UnInstallMain.class
        },
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        usageHelpAutoWidth = true,
        description = "Kompile CLI: Streamline AI/ML Application Development and Deployment")
public class MainCommand implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(MainCommand.class);


    public MainCommand() {
    }


    @Override
    public Integer call() throws Exception {
        File kompileDir = Info.homeDirectory();
        if(!kompileDir.exists()) {
            System.err.println("Kompile directory not initialized. Please run 'kompile bootstrap' first.");
            return 1;
        }
        System.err.println("No command specified. Showing help:\n");
        new CommandLine(new MainCommand()).usage(System.err);
        return 0;
    }



    public static void main(String...args) {
        new CommandLine(new MainCommand()).execute(args);
    }
}