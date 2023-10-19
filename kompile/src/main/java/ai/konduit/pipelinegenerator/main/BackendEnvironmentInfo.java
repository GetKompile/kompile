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

import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

import static ai.konduit.pipelinegenerator.main.util.EnvironmentFile.BACKEND_ENVS_DIR;

@CommandLine.Command(name = "backend-env-info",description = "Print environment info for a given backend")
public class BackendEnvironmentInfo implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        File newFile = new File(System.getProperty("user.home"),".kompile");
        File backendsDir = new File(newFile,BACKEND_ENVS_DIR);
        StringBuilder output = new StringBuilder();
        if(backendsDir.exists() && backendsDir.listFiles() != null) {
            for(File backend : backendsDir.listFiles()) {
                if(backend.listFiles() != null) {
                    for(File f : backend.listFiles()) {
                        output.append("Backend: " + f.getName() + "\n");
                        output.append("========================\n");
                        //only read from env files
                        if(f.getName().endsWith(".env")) {
                            output.append("Platform Classifier: " + f.getName() + "\n");
                            output.append(FileUtils.readFileToString(f, Charset.defaultCharset()));
                        }

                        output.append("========================\n");

                    }
                }
            }
        }

        System.out.println(output);

        return 0;
    }
}
