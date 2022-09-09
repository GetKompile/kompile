/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.build;

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
