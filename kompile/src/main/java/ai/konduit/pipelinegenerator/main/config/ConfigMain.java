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

package ai.konduit.pipelinegenerator.main.config;

import ai.konduit.pipelinegenerator.main.config.python.GeneratePythonConfig;
import ai.konduit.pipelinegenerator.main.config.python.GeneratePythonVariableConfig;
import ai.konduit.pipelinegenerator.main.config.updater.*;
import picocli.CommandLine;

import java.util.concurrent.Callable;


@CommandLine.Command(name = "config",
        description = "Generate configuration objects for various classes within exec. These objects are used together with various pipeline steps.",
        subcommands = {
                GeneratePythonConfig.class,
                GeneratePythonVariableConfig.class,
                ScheduleGenerator.class,
                UpdaterGenerator.class,
                VariableDescriptorGenerator.class,
                LossDescriptorGenerator.class,
                TrainingConfigGenerator.class,
                RegularizationGenerator.class
        }, mixinStandardHelpOptions = false)
public class ConfigMain implements Callable<Integer> {
    public ConfigMain() {
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new ConfigMain());
        commandLine.usage(System.err);
        return 0;
    }

    public static void main(String...args) throws Exception {
        new ConfigMain().call();
    }

}
