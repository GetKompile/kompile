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

package ai.kompile.cli.main.build;

import picocli.CommandLine;

import java.util.concurrent.Callable;
@CommandLine.Command(name = "build",subcommands = {
        GenerateImageAndSDK.class,
        GenerateServingBinary.class,
        NativeImageBuilder.class,
        PomGenerator.class,
        PipelineCommandGenerator.class,
        CloneBuildComponents.class,
        GenerateDl4jBuild.class,
        GenerateNd4jBackend.class
}, mixinStandardHelpOptions = false,
        description = "Configuration namespace for commands related to building native image binaries and SDKs")
public class BuildMain implements Callable<Integer> {
    public BuildMain() {
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new BuildMain());
        commandLine.usage(System.err);
        return 0;
    }
}
