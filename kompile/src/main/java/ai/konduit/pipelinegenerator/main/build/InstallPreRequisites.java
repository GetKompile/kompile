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

import ai.konduit.pipelinegenerator.main.install.PropertyBasedInstaller;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "install-requisites",
        mixinStandardHelpOptions = false,
        description = "Installs pre requisites based on classifier for certain tools.")
public class InstallPreRequisites implements Callable<Integer> {
    @CommandLine.Option(names = {"--os"},description = "The os to install pre requisites for",required = false,scope = CommandLine.ScopeType.INHERIT)
    private String os;
    @CommandLine.Option(names = {"--architecture"},description = "The architecture to install pre requisites for",required = false,scope = CommandLine.ScopeType.INHERIT)
    private String architecture;
    @CommandLine.Option(names = {"--nd4jHelper"},description = "The nd4j backend to install pre requisites for",required = false,scope = CommandLine.ScopeType.INHERIT)
    private String nd4jHelper;

    @CommandLine.Option(names = {"--nd4jBackend"},description = "The nd4j backend to install pre requisites for",required = false,scope = CommandLine.ScopeType.INHERIT)
    private String nd4jBackend;
    private List<String> dependencies = new ArrayList<>();


    @Override
    public Integer call() throws Exception {
        addOpenBlasDepsIfNeeded();
        addNdkIfNeeded();
        addCudaIfNeeded();
        addNccIfNeeded();
        //cmake needed by default for every build
        dependencies.add("cmake");
        System.out.println("Determined dependencies for: helper " + nd4jHelper + " os: " + os + " arch: " + architecture + " nd4j backend: " + nd4jBackend + " to be " + dependencies);
        for(String dependency: dependencies) {
            CommandLine commandLine = new CommandLine(new PropertyBasedInstaller());
            int execute = commandLine.execute("--programName=" + dependency);
            if(execute != 0) {
                System.err.println("Failed to install " + dependency + " Exiting.");
                return execute;
            }
        }


        return 0;
    }


    private void addNccIfNeeded() {
        if(nd4jBackend.equals("nd4j-native") && nd4jHelper.contains("vednn")) {
            dependencies.add("ncc");
        }
    }

    private void addNdkIfNeeded() {
        if(os.equals("android")) {
            dependencies.add("ndk");
        }
    }


    private void addCudaIfNeeded() {
        if( nd4jBackend.contains("cuda")) {
            String cudaVersion = nd4jBackend.replace("nd4j-cuda-","");
            dependencies.add("cuda-" + cudaVersion);
        }
    }


    private void addOpenBlasDepsIfNeeded() {
        //only cpu and nd4j-native needs openblas
        if(nd4jHelper != null && !nd4jHelper.contains("vednn") || (nd4jHelper == null && nd4jBackend.equals("nd4j-native")) || nd4jHelper.contains("onednn") && nd4jBackend.equals("nd4j-native")) {
            StringBuilder resource = new StringBuilder();
            resource.append("openblas");
            resource.append("-");
            resource.append(os);
            resource.append("-");
            resource.append(architecture);
            dependencies.add(resource.toString());
        }
    }

}
