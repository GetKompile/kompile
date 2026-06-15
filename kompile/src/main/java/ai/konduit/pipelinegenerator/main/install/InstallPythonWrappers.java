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

package ai.konduit.pipelinegenerator.main.install;

import ai.konduit.pipelinegenerator.main.Info;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "install-python-wrappers",mixinStandardHelpOptions = false,description = "Installs python wrappers for tensorflow and pytorch for the kompile SDK. Note this requires having already run ./kompile build generate-image-and-sdk and having the sdk installed in order to work.")
public class InstallPythonWrappers implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new InstallKompileComponents());
        File kompileDir = new File(Info.homeDirectory(),"kompile");
        int exitCode = commandLine.execute("--kompileLocation=" + kompileDir.getAbsolutePath());
        exitCode  = 0;
        File pythonDir = new File(Info.homeDirectory(),"python");
        File binDir = new File(pythonDir,"bin");
        File pythonExec = new File(binDir,"python");
        for(String library : new String[]{"kompile_pytorch","kompile_tensorflow"}) {
            exitCode =  new ProcessExecutor().environment(System.getenv())
                    .command(Arrays.asList(pythonExec.getAbsolutePath() ,"setup.py", "install"))
                    .directory(new File(kompileDir,File.separator + library))
                    .readOutput(true)
                    .redirectOutput(System.out)
                    .start().getFuture().get().getExitValue();

        }

        return exitCode;
    }
}
