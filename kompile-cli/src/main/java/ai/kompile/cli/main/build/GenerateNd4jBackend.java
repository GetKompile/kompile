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

@CommandLine.Command(name = "generate-nd4j-backend",
        mixinStandardHelpOptions = false,
        description = "Generate and build a custom nd4j backend using an embedded shell script. " +
                "Pass parameters down to the shell script using the parameters below. This command may require additional tools such as graalvm, maven and a local compiler such as gcc to run correctly.")
public class GenerateNd4jBackend extends BaseGenerateImageAndSdk {

    public GenerateNd4jBackend() {
    }


    @Override
    public void setCustomDefaults() {
        assembly = true;
    }

    @Override
    public void doCustomCommands(List<String> commands) {
           //no-op
    }
}
