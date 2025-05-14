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

import java.util.List;

@CommandLine.Command(name = "generate-serving-binary",
        mixinStandardHelpOptions = false,
        description = "Generate a binary meant for serving models. This will be a static linked binary meant for execution of konduit serving pipelines." +
                " This command may require additional tools such as graalvm, maven and a local compiler such as gcc to run correctly.")
public class GenerateServingBinary extends BaseGenerateImageAndSdk {

    @CommandLine.Option(names = {"--protocol"},description = "The protocol to use with serving",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String protocol;


    public GenerateServingBinary() {
    }


    @Override
    public void setCustomDefaults() {
        //build static shared lib that serves models
        server = true;
        buildSharedLibrary = false;
        mainClass ="ai.konduit.pipelinegenerator.main.ServingMain";
    }

    @Override
    public void doCustomCommands(List<String> commands) {
        addCommand(protocol,"--protocol",commands);
    }
}
