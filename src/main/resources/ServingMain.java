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
import org.bytedeco.javacpp.Loader;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@CommandLine.Command(name = "deploy",
        mixinStandardHelpOptions = false)
public class ServingMain implements Callable<Integer> {
    @CommandLine.Option(names = {"--configFile"},description = "Pipeline file path, must end in json, yml, or yaml",required = true)
    private File configFile;
    @CommandLine.Option(names = {"--pythonPath"},description = "Python path to use. Paths must be separated by the" +
            "path separator for the OS. Windows is ;, Unix (Mac and Linux) is :. Not recommended unless needing to add additional python paths.",required = false)
    private String pythonPath;

    @CommandLine.Option(names = {"--autoConfigurePythonPath"},description = "Auto configure python path based on the installed python found by kompile's install command.",required = false)
    private boolean autoConfigurePythonPath = false;
    @CommandLine.Option(names = {"--pythonExecutableForConfigure"},description = "Python executable for configuration. Specify this if you would like to " +
            "configure this serving binary to use the specified python executable for loading the python path for that python executable.",required = false)
    private File pythonExecutableForConfigure;

    public static void main(String...args) {
        CommandLine commandLine = new CommandLine(new ServingMain());
        int exec = commandLine.execute(args);
        System.out.println("Setup server.");
    }

    @Override
    public Integer call() throws Exception {
        System.setProperty("org.eclipse.python4j.numpyimport", "false");
        System.setProperty("org.eclipse.python4j.release_gil_automatically", "false");
        System.setProperty("org.eclipse.python4j.path.append", "none");

        if(autoConfigurePythonPath) {
            File kompilePythonExec = new File(System.getProperty("user.home"),".kompile");
            File pythonConfigureDir = new File(kompilePythonExec,"python");
            if(!kompilePythonExec.exists()) {
                System.err.println("Specified auto configuration of python path but no kompile install found. Please run kompile install python or kompile install all to fix this problem.");
            } else if(!pythonConfigureDir.exists()) {
                System.err.println("Specified auto configuration of python path but no kompile python install found. Please run kompile install python or kompile install all  to fix this problem.");
            }

            String pythonExecDir = System.getProperty("os.name").contains("win") ? "Scripts" : "bin";
            File condaPythonInstall = new File(pythonConfigureDir,pythonExecDir);
            File pythonExecutable = new File(condaPythonInstall,"python");
            String pythonPath = pythonPathForExecutable(pythonExecutable);
            System.setProperty("org.eclipse.python4j.path", pythonPath);

        } else if(pythonPath != null) {
            System.setProperty("org.eclipse.python4j.path", pythonPath);
        } else if(pythonExecutableForConfigure != null) {
            String pythonPath = pythonPathForExecutable(pythonExecutableForConfigure);
            System.setProperty("org.eclipse.python4j.path", pythonPath);
        }

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

    private String pythonPathForExecutable(File pythonExecutable) throws IOException, ExecutionException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(pythonExecutable.getAbsolutePath());
        command.add("-c");
        command.add("'import sys; import os; print(os.pathsep.join(sys.path)'");
        String output = new ProcessExecutor()
                .command(command)
                .readOutput(true)
                .redirectOutput(System.out)
                .start().getFuture().get().outputUTF8();
        return output;
    }

}
