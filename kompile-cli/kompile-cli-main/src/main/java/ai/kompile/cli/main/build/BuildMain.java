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

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.build.generators.SampleProjectSync;
import picocli.CommandLine;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "build",subcommands = {
        BuildAppCommand.class,
        NativeImageBuilder.class,
        PomGenerator.class,
        PipelineCommandGenerator.class,
        CloneBuildComponents.class,
        GenerateDl4jBuild.class,
        BuildNd4jBackend.class,
        SampleProjectSync.class,
        BuildNativeDevCommand.class,
        BuildNativeDistCommand.class,
        BuildDistCommand.class,
        BuildPlatformCommand.class
}, mixinStandardHelpOptions = false,
        description = "Commands related to building Kompile applications.\n\n" +
                "Primary entry point:\n" +
                "  kompile build app --configName=myapp --preset=hosted-llm-rag\n" +
                "  kompile build app --configName=myapp --preset=full --exclude=graph-neo4j\n\n" +
                "For custom ND4J/DL4J backend builds:\n" +
                "  kompile build nd4j-backend --preset=minimal-inference\n" +
                "  kompile build nd4j-backend --backend=cuda --cuda-version=12.3 --helper-cudnn")
public class BuildMain implements Callable<Integer> {
    public BuildMain() {
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new BuildMain());
        commandLine.usage(System.err);
        System.err.println("\nTip: Use 'kompile build app --configName=myapp --preset=hosted-llm-rag' to build applications.");
        return 0;
    }
}