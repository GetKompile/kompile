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

import java.util.List;

@CommandLine.Command(name = "generate-image-and-sdk",
        mixinStandardHelpOptions = false,
        description = "Generate and build a python SDK using an embedded shell script. " +
                "Pass parameters down to the shell script using the parameters below. This command may require additional tools such as graalvm, maven and a local compiler such as gcc to run correctly.")
public class GenerateImageAndSDK extends BaseGenerateImageAndSdk {

    public GenerateImageAndSDK() {
    }


    @Override
    public void setCustomDefaults() {
        //no-op
    }

    @Override
    public void doCustomCommands(List<String> commands) {
           //no-op
    }
}
