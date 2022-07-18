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
package ai.konduit.pipelinegenerator.main;

import ai.konduit.serving.vertx.api.DeployKonduitServing;
import ai.konduit.serving.vertx.config.InferenceConfiguration;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.VertxOptions;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "deploy",
        mixinStandardHelpOptions = false)
public class ServingMain implements Callable<Integer> {
    @CommandLine.Option(names = {"--configFile"},description = "Pipeline file path, must end in json, yml, or yaml",required = true)
    private File configFile;


    public static void main(String...args) {
        CommandLine commandLine = new CommandLine(new ServingMain());
        int exec = commandLine.execute(args);
        System.out.println("Setup server.");
    }

    @Override
    public Integer call() throws Exception {
        InferenceConfiguration configuration = InferenceConfiguration.fromJson(
                FileUtils.readFileToString(configFile, Charset.defaultCharset()));
        DeployKonduitServing.deploy(new VertxOptions(),
                new DeploymentOptions(),
                configuration,
                handler -> {
                    if(handler.succeeded()) {
                        System.out.println("Deployment succeeded.");
                    } else {
                        System.out.println("Deployment failed. Exiting. Reason:");
                        handler.cause().printStackTrace();
                        System.exit(1);
                    }
                });
        return 0;
    }

}
